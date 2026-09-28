package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.entry.Collect;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsAdminQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.mapper.CollectMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.GoodsService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.ContentAuditUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.ViewCountUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GoodsServiceImp extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

    @Resource
    private CacheUtils cacheUtils;

    //只用于"删除商品前预检是否已有订单引用"，注入 Mapper 而不是 Service，避免和 OrderServiceImp 形成循环依赖
    @Resource
    private OrderMapper orderMapper;
    //详情页要展示卖家昵称/头像，同样注入 Mapper：注入 UserService 会和 UserServiceImp 绕成环
    @Resource
    private UserMapper userMapper;
    //详情页要标记"我是否收藏过"，注入 CollectMapper 而不是 CollectService，理由同上
    @Resource
    private CollectMapper collectMapper;
    //UGC 文本审核：标题/描述/详情与评论同一个入口，不给两处各写一份规则
    @Resource
    private ContentAuditUtils contentAuditUtils;
    //浏览量计数（Redis 缓冲，DB 由 ViewCountFlushJob 定期归集）
    @Resource
    private ViewCountUtils viewCountUtils;

    private static final Duration GOODS_DETAIL_TTL = Duration.ofMinutes(30);

    // 排行榜一次取满上限并缓存，limit 只在内存里截断：避免每个 limit 一个 key 导致写侧无法枚举失效
    private static final int RANK_MAX_SIZE = 50;
    private static final int RANK_DEFAULT_SIZE = 10;
    // 榜单是派生视图（不是事本身），5 分钟足够；过长会让下架商品继续霸榜
    private static final Duration COLLECT_RANK_TTL = Duration.ofMinutes(5);

    private String detailKey(Long id) {
        return RedisContent.goodsDetailKey(id);
    }

    //添加商品
    @Override
    public void addGoods(GoodsAddDTO goodsAddDTO, Long loginUserId) {
        //二手关键字段与 UGC 文本的预检，与修改路径共用同一个方法
        checkGoodsContent(goodsAddDTO.getTitle(), goodsAddDTO.getDescription(),
                goodsAddDTO.getConditionLevel(), goodsAddDTO.getPrice(), goodsAddDTO.getExpectPrice());
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsAddDTO, goods);
        //绑定用户id
        goods.setUserId(loginUserId);
        //设置商品状态 新发布默认在售
        goods.setStatus(Goods.STATUS_ON_SALE);
        save(goods);
        //新商品 id 可能命中过"空值缓存(防穿透)"，这里一并失效
        cacheUtils.evictAfterCommit(detailKey(goods.getId()));
        //商品集合变了（不足 50 件时新商品也会进榜），榜单缓存一并失效
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
    }

    //修改商品
    @Override
    public void updateGoods(GoodsUpdateDTO goodsUpdateDTO, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsUpdateDTO, goods);
        //判断这个商品是否存在 如果存在再判断是否属于当前用户
        Goods existsGoods = getById(goods.getId());
        if(existsGoods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法修改");
        }
        if(!existsGoods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法修改");
        }
        //expectPrice 不传 = 不修改，那就要拿库里的旧值来校：
        //只校"本次传了什么"会漏掉"把标价改低到旧底线以下"这种组合非法（DB CHECK 会拦，但给的是 500）
        BigDecimal effectiveExpect = goodsUpdateDTO.getExpectPrice() != null
                ? goodsUpdateDTO.getExpectPrice() : existsGoods.getExpectPrice();
        checkGoodsContent(goodsUpdateDTO.getTitle(), goodsUpdateDTO.getDescription(),
                goodsUpdateDTO.getConditionLevel(), goodsUpdateDTO.getPrice(), effectiveExpect);
        updateById(goods);
        //写库后失效详情缓存，否则脏读窗口最长 30 分钟
        cacheUtils.evictAfterCommit(detailKey(goods.getId()));
        //榜单行里带 title/price，改名改价后不失效就会拿旧文案展示
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
    }

    //商品内容统一预检：成色取值域 + 议价底线合理性 + 敏感词。
    //add/update 两个入口共用，否则“只改一处”的漏洞迟早出现（和 doPageGoods 、 applyStatusChange 是同一条道理）
    private void checkGoodsContent(String title, String description, String conditionLevel,
                                   BigDecimal price, BigDecimal expectPrice) {
        //先校敏感词再校长度：长度由 DTO 的 @Size 拦，这里只管内容健不健康
        contentAuditUtils.assertClean(title, "goods.title");
        contentAuditUtils.assertClean(description, "goods.description");
        //成色是选填：不填 = “没说”，不等于“全新”，所以不能给默认值，也不能强拦
        if (conditionLevel != null && !conditionLevel.isBlank()
                && !Goods.CONDITIONS.contains(conditionLevel)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "成色只能是：" + String.join("/", Goods.CONDITIONS));
        }
        if (expectPrice != null) {
            if (price == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "设了期望成交价却没填标价");
            }
            //这条就是 chk_goods_expect_price 的应用层版本：预检给人话，DB 约束防绕过应用层的写入
            if (expectPrice.compareTo(BigDecimal.ZERO) < 0 || expectPrice.compareTo(price) > 0) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "期望成交价不能高于标价");
            }
        }
    }

    //修改商品状态（卖家本人）
    @Override
    public void changeStatus(Long GoodsId, Integer status, Long loginUserId) {
        Goods goods=getById(GoodsId);
        if(goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法修改商品状态");
        }
        if(!goods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法修改商品状态");
        }
        applyStatusChange(GoodsId, status);
    }

    //管理员强制改状态：只有 owner 校验这一步不同，其余规则必须和卖家自己改完全一致，
    //所以共用 applyStatusChange，绝不复制一份"看起来一样"的实现（两份规则迟早漂移）
    @Override
    public void forceChangeStatusByAdmin(Long goodsId, Integer status, Long operatorId) {
        Goods goods = getById(goodsId);
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在,无法修改商品状态");
        }
        applyStatusChange(goodsId, status);
        //治理动作必须留痕：谁下架了谁的商品，出问题时这是唯一线索
        log.info("管理员强制修改商品状态 operator={} goodsId={} status={}", operatorId, goodsId, status);
    }

    //商品状态变更的唯一实现：取值域校验 + CAS + 缓存失效
    private void applyStatusChange(Long goodsId, Integer status) {
        //取值域必须与 init.sql 的 chk_goods_status 一致，用常量避免两处漂移
        //手工只能 上架(1)/下架(0)；已售出(2) 由订单占用，只能由下单/取消流程维护
        //放开 2 等于允许卖家手工“宣布售出”，或把在途订单的商品捞回在售 → 一物多卖
        //（管理员也一样受这条约束：已售出的商品没有"继续曝光"的风险，不值得为它开后门）
        if(status==null||(status!=Goods.STATUS_OFF&&status!=Goods.STATUS_ON_SALE)){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"只能上架(1)或下架(0)，售出状态由系统维护");
        }
        // CAS：排除“已售出”。原来用 updateById 是按主键无脑覆盖，卖家一次上架就能击穿下单流程
        //用 update(null, wrapper) 只 SET status 一列，冲突面比写回整份快照小
        LambdaUpdateWrapper<Goods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Goods::getId, goodsId)
                .ne(Goods::getStatus, Goods.STATUS_SOLD)
                .set(Goods::getStatus, status);
        if (baseMapper.update(null, wrapper) == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "商品已售出或状态已变更，无法修改");
        }
        //上架/下架都会影响详情页展示
        cacheUtils.evictAfterCommit(detailKey(goodsId));
        //榜单只统计在售，下架/售出必须重算，否则最脏 5 分钟仍霸榜
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
    }

    //删除商品
    @Override
    public void deleteGoods(Long GoodsId, Long loginUserId) {
        Goods goods = getById(GoodsId);
        //判断商品是否存在
        if(goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法删除");
        }
        if(!goods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法删除");
        }
        //FK fk_order_goods 是 ON DELETE RESTRICT：有订单引用时数据库会直接报错，
        //不预检就删会抛 DataIntegrityViolationException 落到全局兜底 500，用户看不懂。
        //这里与"分类下有商品禁止删除"同理：DB 约束是最后防线，Service 预检负责给出人话。
        Long orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getGoodsId, GoodsId));
        if (orderCount != null && orderCount > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品已产生交易记录，不可删除，如需隐藏请改为下架");
        }
        //预检与删除之间存在竞态：可能刚好有并发下单落库引用了该商品，
        //此时 FK fk_order_goods 会拦下来，这里翻译成和业务提示，不落 500
        try {
            removeById(GoodsId);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品已产生交易记录，不可删除，如需隐藏请改为下架");
        }
        //商品已删除，缓存不清掉详情页还能展示 30 分钟
        cacheUtils.evictAfterCommit(detailKey(GoodsId));
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
        //评论随 FK CASCADE 一起没了，评论列表缓存也在这里失效（key 由 goodsId 决定，可枚举）
        cacheUtils.evictAfterCommit(RedisContent.commentListKey(GoodsId));
    }
    //获取自身商品（卖家自己的列表：不限状态，否则下架/已售出的商品在自己页面消失）
    @Override
    public Page<GoodsVo> getGoodsByOwner(GoodsPageQueryDTO goodsPageQueryDTO, Long loginUserId) {
        //userId 一律用登录态覆盖，不接受前端传值（防越权看别人的下架商品）
        return doPageGoods(goodsPageQueryDTO.getPageNum(), goodsPageQueryDTO.getPageSize(),
                loginUserId, goodsPageQueryDTO.getCategoryId(), goodsPageQueryDTO.getKeyword(), null);
    }

    //获取所有商品（公开列表：只看在售）
    @Override
    public Page<GoodsVo> getAllGoods(GoodsPageQueryDTO goodsPageQueryDTO) {
        //"仅在售"这条业务规则收在调用处，不再各写一份 wrapper
        return doPageGoods(goodsPageQueryDTO.getPageNum(), goodsPageQueryDTO.getPageSize(),
                goodsPageQueryDTO.getUserId(), goodsPageQueryDTO.getCategoryId(),
                goodsPageQueryDTO.getKeyword(), Goods.STATUS_ON_SALE);
    }

    //管理端商品治理列表：可跨卖家、可按任意状态筛
    @Override
    public Page<GoodsVo> getGoodsPageForAdmin(GoodsAdminQueryDTO queryDTO) {
        Integer status = queryDTO.getStatus();
        if (status != null && status != Goods.STATUS_OFF
                && status != Goods.STATUS_ON_SALE && status != Goods.STATUS_SOLD) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品状态取值只能是 0下架/1在售/2已售出");
        }
        return doPageGoods(queryDTO.getPageNum(), queryDTO.getPageSize(),
                queryDTO.getUserId(), queryDTO.getCategoryId(), queryDTO.getKeyword(), status);
    }

    /**
     * 商品分页的唯一实现。原先 公开列表/我的商品 各写一份 wrapper，现在再加管理端一份 ——
     * 三个入口只差"是否限定状态、按谁的 id 筛"，多写一份就多一处漏改的地方（加筛选条件必漏）。
     * status 传 null 表示不限状态；传 1 就是公开列表的"仅在售"。
     */
    private Page<GoodsVo> doPageGoods(Integer pageNum, Integer pageSize, Long userId,
                                      Long categoryId, String keyword, Integer status) {
        Page<Goods> page1 = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            queryWrapper.eq(Goods::getUserId, userId);
        }
        if (categoryId != null) {
            queryWrapper.eq(Goods::getCategoryId, categoryId);
        }
        //标题模糊匹配（前置通配走不了索引，属 P3-2 已记账的债）
        if (keyword != null && !keyword.isBlank()) {
            queryWrapper.like(Goods::getTitle, keyword);
        }
        //eq 的三参重载：条件为 false 就不拼这段，等价于"status 为 null 即不限"
        queryWrapper.eq(status != null, Goods::getStatus, status);
        queryWrapper.orderByDesc(Goods::getCreateTime);
        Page<Goods> goodsPage = page(page1, queryWrapper);
        //转成GoodsVo返回前端：VO 白名单裁掉实体内部字段
        return (Page<GoodsVo>) goodsPage.convert(goods -> {
                    GoodsVo vo = new GoodsVo();
                    BeanUtils.copyProperties(goods, vo);
                    return vo;
                });
    }

    //获取商品详情（一次返回 商品 + 卖家展示信息 + 当前登录者的归属/收藏标记）
    @Override
    public GoodsDetailVo getGoodsDetail(Long GoodsId, Long loginUserId) {
        Goods goods = cacheUtils.getOrLoad(detailKey(GoodsId), GOODS_DETAIL_TTL,
                Goods.class, () -> getById(GoodsId));
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在,无法获取详细信息");
        }
        //转成GoodsDetailVo返回前端,避免泄露userId等内部字段
        GoodsDetailVo vo = new GoodsDetailVo();
        BeanUtils.copyProperties(goods, vo);
        //浏览量：一次 HINCRBY 同时完成计数与"拿当前未落库增量"，DB 零写入；
        //存量的那一份来自缓存里的 Goods（最多滞后一个落库周期，job 落库后会主动 evict 详情缓存）
        long pendingView = viewCountUtils.recordView(GoodsId);
        vo.setViewCount((goods.getViewCount() == null ? 0 : goods.getViewCount()) + (int) pendingView);
        //联系方式按登录态决定是否给出（不能交给 copyProperties 顺手带出去）：
        //缓存里存的是原值，"谁在看" 的差异必须在读侧现算，和下面两个布尔标记同一条规矩
        vo.setContact(loginUserId == null ? null : goods.getContact());
        //卖家信息：昵称/头像可公开展示，id/手机号/账号状态不外泄。
        //刻意不把这两个字段塞进详情缓存 —— 一旦塞进去，"卖家改昵称"就得去删他所有商品的详情缓存，
        //凭空多一条 user→goods 的跨模块失效连带；这里只是缓存之外的一次主键命中，不值得换
        User seller = userMapper.selectById(goods.getUserId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerAvatar(seller.getAvatar());
        }
        //下面两个标记都是"谁在看"决定的，属于登录者维度，绝不能进共享缓存，读侧现算
        vo.setIsOwner(loginUserId != null && loginUserId.equals(goods.getUserId()));
        vo.setIsCollected(loginUserId != null && collectMapper.selectCount(
                new LambdaQueryWrapper<Collect>()
                        .eq(Collect::getUserId, loginUserId)
                        .eq(Collect::getGoodsId, GoodsId)) > 0);
        return vo;
    }

    //按收藏数从高到低排行（公开接口，无登录态也能看）
    @Override
    public List<GoodsVo> getCollectRank(int limit) {
        //limit 超上限的修正：从“掉回 10”改成“截断到 50”，调用方要 100 条给 50 条比给 10 条符合直觉
        if (limit <= 0) {
            limit = RANK_DEFAULT_SIZE;
        } else if (limit > RANK_MAX_SIZE) {
            limit = RANK_MAX_SIZE;
        }
        //只缓存 Top50 一份全集，按 limit 在内存截断：
        //榜单只有一个 key，收藏/上下架/删除都能一次 evict 干净，不会漏删某个 limit 的副本
        List<GoodsVo> top = cacheUtils.getOrLoad(RedisContent.Collect_Rank_KEY, COLLECT_RANK_TTL,
                json -> JSONUtil.parseArray(json).toList(GoodsVo.class),
                () -> loadCollectRank(RANK_MAX_SIZE));
        if (top == null || top.isEmpty()) {
            return new ArrayList<>();
        }
        //subList 返回的是原列表的视图，缓存内部又是反序列化的新列表，copy 一份避免调用方修改影响缓存值
        return top.size() <= limit ? top : new ArrayList<>(top.subList(0, limit));
    }

    //回源查榜单：只统计在售商品，collect_count 由 collect 表对账校准保证正确性
    private List<GoodsVo> loadCollectRank(int size) {
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Goods::getStatus, Goods.STATUS_ON_SALE)   // 只统计在售商品，如需要可去掉
               .orderByDesc(Goods::getCollectCount)
               .orderByDesc(Goods::getCreateTime)
               .last("LIMIT " + size);   // size 已做范围校验，无注入风险
        //转成GoodsVo返回前端
        return list(wrapper).stream().map(goods -> {
            GoodsVo vo = new GoodsVo();
            BeanUtils.copyProperties(goods, vo);
            return vo;
        }).collect(Collectors.toList());
    }
}
