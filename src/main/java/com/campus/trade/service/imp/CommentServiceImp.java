package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Comment;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.Notice;
import com.campus.trade.bean.entry.Role;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.DTO.request.comment.CommentAdminQueryDTO;
import com.campus.trade.bean.vo.CommentAdminVo;
import com.campus.trade.bean.vo.CommentVo;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.ContentAuditUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.mapper.CommentMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.CommentService;
import com.campus.trade.service.NoticeService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class CommentServiceImp extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private CacheUtils cacheUtils;
    //内容审核（长度 + 敏感词），与商品标题/描述共用同一个入口
    @Resource
    private ContentAuditUtils contentAuditUtils;
    //站内信：评论是典型的“需要被看到”的互动
    @Resource
    private NoticeService noticeService;

    //评论是"读多写少 + 展示型"数据，5 分钟逻辑 TTL 与统计同口径：
    //即使哪次 evict 漏了，最坏 5 分钟自愈
    private static final Duration COMMENT_LIST_TTL = Duration.ofMinutes(5);
    private static final long DEFAULT_PAGE_SIZE = 10;
    private static final long MAX_PAGE_SIZE = 50;

    //发表评论
    @Override
    public void addComment(CommentAddDTO commentAddDTO, Long loginUserId) {
        //先 trim 再校：@NotBlank 已经拦住了纯空格，这里自己再判一次是为了
        //“校的内容”与“入库的内容”是同一个值（不然后面长度按原值算、存储按 trim 后的算）
        String content = commentAddDTO.getContent() == null ? null : commentAddDTO.getContent().trim();
        if (content == null || content.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "评论内容不能为空");
        }
        //DTO 上的 @Size 是接口契约，这条是第二道：Service 也可能被以后的调用方直接用（不经 @Valid）
        //DB 列是 TEXT 不拦长度，真漏过去就是一行两万字的通知列表与缓存
        if (content.length() > ContentAuditUtils.COMMENT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "评论内容不能超过" + ContentAuditUtils.COMMENT_MAX_LENGTH + "字");
        }
        contentAuditUtils.assertClean(content, "comment");
        //预检商品存在：comment.goods_id 是 FK，传不存在的 id 会抛 DataIntegrityViolationException 落到兜底 500。
        //DB 约束是最后防线，Service 预检负责给人话（和"分类下有商品禁止删除"同一套做法）
        Goods goods = goodsMapper.selectById(commentAddDTO.getGoodsId());
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在,无法发表评论");
        }
        Comment comment = new Comment();
        BeanUtils.copyProperties(commentAddDTO, comment);
        //copyProperties 把未 trim 的原值带过来了，以校验过的那份为准
        comment.setContent(content);
        comment.setUserId(loginUserId);
        save(comment);
        //新评论进不了缓存，列表就会少一条：提交后删 + 延迟双删
        cacheUtils.evictAfterCommit(RedisContent.commentListKey(comment.getGoodsId()));
        //通知商品主人。在自己的商品下留言时 send 内部会因 receiver == actor 直接跳过
        noticeService.send(goods.getUserId(), loginUserId, Notice.TYPE_COMMENT,
                "你的商品收到新评论",
                "《" + goods.getTitle() + "》：" + summarize(content), comment.getGoodsId());
    }

    //通知里只带摘要不带全文：收件箱不是评论复读机，也给“把长文当站内消息发”留不了口子
    private String summarize(String content) {
        return content.length() <= 40 ? content : content.substring(0, 40) + "…";
    }

    //删除评论：本人删自己的、管理员删违规内容，判定收在这一处
    @Override
    public void deleteComment(Long commentId, Long loginUserId, String loginUserRole) {
        Comment comment = getById(commentId);
        if (comment==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"评论不存在");
        }
        //管理员可处置违规内容（内容治理）。
        //权限来源只有一处可信：端点上的 @RequireRole(ADMIN) 决定谁能调，这里决定能删谁的
        boolean isAdmin = Role.ADMIN.getCode().equals(loginUserRole);
        if (!isAdmin && !comment.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"不能删除别人的评论");
        }
        removeById(commentId);
        //删完必须让列表缓存失效，否则被删的评论还能展示 5 分钟
        cacheUtils.evictAfterCommit(RedisContent.commentListKey(comment.getGoodsId()));
    }

    //某商品的评论（分页 + 缓存）
    @Override
    public Page<CommentVo> getCommentVoList(Long goodsId, Long loginUserId, Integer pageNum, Integer pageSize) {
        //商品是否存在每次真查（一次主键命中）：缓存里没有商品行，
        //否则商品被删之后评论列表还能继续翻
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法查看评论");
        }
        long current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        long size = (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        //缓存"与登录者无关"的评论基础行；isOwner 是登录者维度的标记，读侧现算
        List<CommentCacheItem> items = cacheUtils.getOrLoad(
                RedisContent.commentListKey(goodsId), COMMENT_LIST_TTL,
                json -> JSONUtil.parseArray(json).toList(CommentCacheItem.class),
                () -> loadCommentItems(goodsId));
        if (items == null) {
            items = new ArrayList<>();
        }

        //单 key 存全量 + 内存切片，而不是 comment:list:{goodsId}:{pageNum} 一堆 key：
        //分页缓存必须能被写侧精确失效，带页码的 key 发/删一条评论就删不干净了。
        //total 取缓存窗口内的条数，页内数据与 total 自洽（校园场景单商品评论量天然有限，
        //将来真出现超长列表，正确做法是改成固定窗口 Top-N + 超出直接回源，而不是加页码后缀）
        long from = (current - 1) * size;
        List<CommentVo> records = new ArrayList<>();
        for (int i = (int) Math.min(from, items.size()); i < items.size() && records.size() < size; i++) {
            CommentCacheItem item = items.get(i);
            CommentVo commentVo = new CommentVo();
            //userId 不在 CommentVo 里，copyProperties 之后天然被裁掉
            BeanUtils.copyProperties(item, commentVo);
            commentVo.setIsOwner(loginUserId != null && loginUserId.equals(item.getUserId()));
            records.add(commentVo);
        }
        Page<CommentVo> voPage = new Page<>(current, size, items.size());
        voPage.setRecords(records);
        return voPage;
    }

    //管理端评论治理列表：跨商品，需要商品标题与评论人 id，所以不复用 CommentVo。
    //不缓存：治理视图读频率极低，且跨商品分页的 key 同样无法枚举失效
    @Override
    public Page<CommentAdminVo> getCommentPageForAdmin(CommentAdminQueryDTO queryDTO) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(queryDTO.getGoodsId() != null, Comment::getGoodsId, queryDTO.getGoodsId());
        wrapper.eq(queryDTO.getUserId() != null, Comment::getUserId, queryDTO.getUserId());
        wrapper.like(queryDTO.getKeyword() != null && !queryDTO.getKeyword().isBlank(),
                Comment::getContent, queryDTO.getKeyword());
        wrapper.orderByDesc(Comment::getCreateTime);
        Page<Comment> commentPage = page(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        Page<CommentAdminVo> voPage = new Page<>(commentPage.getCurrent(),
                commentPage.getSize(), commentPage.getTotal());
        voPage.setRecords(new ArrayList<>());
        List<Comment> rows = commentPage.getRecords();
        if (rows.isEmpty()) {
            return voPage;
        }
        //批量补商品标题与昵称，避免逐条查（和订单列表同一套做法）
        Map<Long, String> titleMap = goodsMapper.selectBatchIds(
                        rows.stream().map(Comment::getGoodsId).distinct().toList()).stream()
                .collect(Collectors.toMap(Goods::getId, g -> g.getTitle() != null ? g.getTitle() : ""));
        Map<Long, String> nickNameMap = userMapper.selectBatchIds(
                        rows.stream().map(Comment::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
        for (Comment comment : rows) {
            CommentAdminVo vo = new CommentAdminVo();
            BeanUtils.copyProperties(comment, vo);
            vo.setGoodsTitle(titleMap.getOrDefault(comment.getGoodsId(), ""));
            vo.setNickname(nickNameMap.getOrDefault(comment.getUserId(), ""));
            voPage.getRecords().add(vo);
        }
        return voPage;
    }

    //回源：评论 + 昵称一次批量查，避免逐条查用户的 N+1
    private List<CommentCacheItem> loadCommentItems(Long goodsId) {
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Comment::getGoodsId, goodsId);
        queryWrapper.orderByDesc(Comment::getCreateTime);
        List<Comment> commentList = list(queryWrapper);
        //没有评论属于正常情况：返回空列表（空列表也会写进缓存，顺带防穿透）
        if (commentList.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIdList = commentList.stream().map(Comment::getUserId).distinct().toList();
        Map<Long,String> nickNameMap = userMapper.selectBatchIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
        List<CommentCacheItem> items = new ArrayList<>(commentList.size());
        for (Comment comment : commentList) {
            CommentCacheItem item = new CommentCacheItem();
            BeanUtils.copyProperties(comment, item);
            item.setNickname(nickNameMap.getOrDefault(comment.getUserId(), ""));
            items.add(item);
        }
        return items;
    }

    /**
     * 评论列表在 Redis 里的存储结构。
     * 为什么不直接缓存 CommentVo：CommentVo 是公开出参，按规范不能带 userId；
     * 而读侧要拿 userId 才能算出 isOwner —— 所以"带 userId 的内部结构"和"不带的出参"必须分开。
     * 这个类的 JSON 只落在 Redis 里，永远不会出现在任何 HTTP 响应中。
     */
    @Data
    public static class CommentCacheItem {
        private Long id;
        private Long goodsId;
        private Long userId;
        private String nickname;
        private String content;
        private LocalDateTime createTime;
    }
}
