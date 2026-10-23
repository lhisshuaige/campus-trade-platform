package com.campus.trade.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entity.Comment;
import com.campus.trade.bean.entity.Goods;
import com.campus.trade.bean.entity.Notice;
import com.campus.trade.bean.entity.Role;
import com.campus.trade.bean.entity.User;
import com.campus.trade.bean.dto.request.comment.CommentAddDTO;
import com.campus.trade.bean.dto.request.comment.CommentAdminQueryDTO;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {

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
    //每栋楼在列表里预览几条回复：双栏详情里右栏高度有限，1 条足够表意"这楼有人说话"，
    //看全的入口是展开（前端按 1→4→9→14 阶梯请求，步长逻辑归展示层，服务端只管前缀）。
    //预览取楼内排布（见 orderReplies：回楼主主评论的置顶一组）的第一条 —— 组内保持时间倒序，
    //所以它天然是"有人接楼主话就看最新接话，没人接就看楼里最新一句"
    private static final int PREVIEW_REPLY_COUNT = 1;

    //发表评论（顶层）或楼中楼回复。返回新行 id 给前端做精准插入（IdType.AUTO 在 insert 后回填）
    @Override
    public Long addComment(CommentAddDTO commentAddDTO, Long loginUserId) {
        //先 trim 再校：@NotBlank 已经拦住了纯空格，这里自己再判一次是为了
        //"校的内容"与"入库的内容"是同一个值（不然后面长度按原值算、存储按 trim 后的算）
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
        //预检商品存在：comment.goods_id 是 FK，传不存在的 id 会抛 DataIntegrityViolationException 落到兜底 500。
        //DB 约束是最后防线，Service 预检负责给人话（和"分类下有商品禁止删除"同一套做法）
        Goods goods = goodsMapper.selectById(commentAddDTO.getGoodsId());
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在,无法发表评论");
        }
        //楼中楼解析（放在审核之前：三层请求在这里被归一化成两层，非法挂楼在这一步就拒）
        ReplyContext reply = resolveReplyContext(commentAddDTO);
        contentAuditUtils.assertClean(content, reply == null ? "comment" : "comment.reply");
        Comment comment = new Comment();
        BeanUtils.copyProperties(commentAddDTO, comment);
        //copyProperties 把未 trim 的原值带过来了，以校验过的那份为准
        comment.setContent(content);
        //parentId 用归一化结果显式覆盖：DTO 里那个是客户端原样传的（可能是条回复的 id），不能直接落库
        comment.setParentId(reply == null ? null : reply.topId());
        comment.setReplyToUserId(reply == null ? null : reply.replyToUserId());
        comment.setReplyToCommentId(reply == null ? null : reply.replyToCommentId());
        comment.setUserId(loginUserId);
        save(comment);
        //新评论进不了缓存，列表就会少一条：提交后删 + 延迟双删（顶层与回复同吃 comment:list:{goodsId} 这一个 key）
        cacheUtils.evictAfterCommit(RedisContent.commentListKey(comment.getGoodsId()));
        //通知走哪条边：顶层→商品主人；回复→被回复的那个人。
        //在自己的商品下留言、自己回复自己，send 内部都因 receiver == actor 直接跳过。
        //回复刻意复用 TYPE_COMMENT 而不是新增 reply 类型：notice 表的 chk_notice_type 是已应用的
        //基线约束，加类型要再开一条迁移改 CHECK，而点击跳转本来就去商品详情（bizId 都是 goodsId），
        //通知种类对收件人是无感的 —— 省一条迁移，语义靠标题文案区分
        if (reply == null) {
            noticeService.send(goods.getUserId(), loginUserId, Notice.TYPE_COMMENT,
                    "你的商品收到新评论",
                    "《" + goods.getTitle() + "》：" + summarize(content), comment.getGoodsId());
        } else {
            noticeService.send(reply.replyToUserId(), loginUserId, Notice.TYPE_COMMENT,
                    "你的评论收到新回复",
                    "《" + goods.getTitle() + "》：" + summarize(content), comment.getGoodsId());
        }
        return comment.getId();
    }

    /** 一次回复解析出的三个落库值：楼根 id、被 @ 者的 userId、被 @ 的具体行 id（@ 昵称不落库，读侧现推） */
    private record ReplyContext(Long topId, Long replyToUserId, Long replyToCommentId) {
    }

    /**
     * parentId 为空 → 返回 null（顶层评论，零新代码路径）。否则：
     * 1) parent 必须存在（@TableLogic：已删=查不到=不能回复）、与商品一致
     * 2) parent 是回复 → 归一化到它所在楼根，"回复一条回复"自动压平成两层结构
     * 3) replyToCommentId 显式传了就校验同楼（防"跨楼认领"，把 @ 挂到别人家的对话上）；
     *    不传默认 @ 被回复的那一行本身
     */
    private ReplyContext resolveReplyContext(CommentAddDTO dto) {
        if (dto.getParentId() == null) {
            return null;
        }
        Comment parent = getById(dto.getParentId());
        if (parent == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "要回复的评论不存在或已删除");
        }
        if (!parent.getGoodsId().equals(dto.getGoodsId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能跨商品回复");
        }
        Long topId = parent.getParentId() == null ? parent.getId() : parent.getParentId();
        Comment replyTo = parent;
        if (dto.getReplyToCommentId() != null && !dto.getReplyToCommentId().equals(parent.getId())) {
            Comment target = getById(dto.getReplyToCommentId());
            Long targetTop = target == null ? null
                    : (target.getParentId() == null ? target.getId() : target.getParentId());
            if (!topId.equals(targetTop)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "不能跨楼回复");
            }
            replyTo = target;
        }
        return new ReplyContext(topId, replyTo.getUserId(), replyTo.getId());
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
        //逻辑删除（P3-5）：这里发出去的是 UPDATE comment SET deleted=1，行仍在库里。
        //"删掉"与"销毁"不再是同一件事：内容治理要能回答"这条违规内容当时写了什么"
        removeById(commentId);
        //删顶层 = 连整楼一起删（抖音同款）：回复行的 parent_id 在写入侧就归一化指到楼根，
        //所以一条 eq 就是全楼，不需要递归；管理员删违规评论时也顺带清掉楼里的附和
        if (comment.getParentId() == null) {
            remove(new LambdaQueryWrapper<Comment>().eq(Comment::getParentId, commentId));
        }
        //删完必须让列表缓存失效，否则被删的评论还能展示 5 分钟
        cacheUtils.evictAfterCommit(RedisContent.commentListKey(comment.getGoodsId()));
    }

    //某商品的评论（分页 + 缓存）。出参是"楼"：顶层分页，每栋楼带楼内排布首条的预览 + 回复总数
    @Override
    public Page<CommentVo> getCommentVoList(Long goodsId, Long loginUserId, Integer pageNum, Integer pageSize) {
        //商品是否存在每次真查（一次主键命中）：缓存里没有商品行，
        //否则商品被删之后评论列表还能继续翻
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法查看评论");
        }
        long current = clampPageNum(pageNum);
        long size = clampPageSize(pageSize);

        List<CommentCacheItem> items = loadItemsCached(goodsId);

        //单 key 存全量 + 内存分堆，而不是 comment:list:{goodsId}:{pageNum} 一堆 key：
        //分页缓存必须能被写侧精确失效，带页码的 key 发/删一条评论就删不干净了。
        //楼中楼后切片的单位从"行"变成"楼"：顶层按 createTime 倒序分页（回源即倒序），
        //回复按 parentId 归堆，total 的语义 = 顶层楼数（楼里多少条回复不参与翻页，展开走 /comment/replies）
        List<CommentCacheItem> tops = new ArrayList<>();
        Map<Long, List<CommentCacheItem>> repliesByTop = new HashMap<>();
        Map<Long, String> nickByUser = new HashMap<>();
        for (CommentCacheItem item : items) {
            if (item.getParentId() == null) {
                tops.add(item);
            } else {
                repliesByTop.computeIfAbsent(item.getParentId(), k -> new ArrayList<>()).add(item);
            }
            nickByUser.putIfAbsent(item.getUserId(), item.getNickname() == null ? "" : item.getNickname());
        }

        long from = (current - 1) * size;
        List<CommentVo> records = new ArrayList<>();
        for (int i = (int) Math.min(from, tops.size()); i < tops.size() && records.size() < size; i++) {
            CommentCacheItem top = tops.get(i);
            CommentVo vo = toVo(top, loginUserId);
            List<CommentCacheItem> replies = orderReplies(
                    repliesByTop.getOrDefault(top.getId(), List.of()), top.getId(), top.getUserId());
            //楼内排布 = orderReplies：回楼主主评论的置顶一组，接楼内话的靠后；组内时间倒序，组头即最新
            List<CommentVo> preview = new ArrayList<>();
            for (int r = 0; r < replies.size() && preview.size() < PREVIEW_REPLY_COUNT; r++) {
                preview.add(toReplyVo(replies.get(r), top.getId(), top.getUserId(), nickByUser, loginUserId));
            }
            vo.setReplies(preview);
            vo.setReplyCount(replies.size());
            records.add(vo);
        }
        Page<CommentVo> voPage = new Page<>(current, size, tops.size());
        voPage.setRecords(records);
        return voPage;
    }

    //楼中楼"展开"：某条顶层评论下的全部回复（楼内排布：回楼主主评论一组置顶、接话组靠后，组内最新在前）。
    //刻意与列表共用 comment:list:{goodsId} 这一个缓存 key 再内存过滤：
    //展开出来的数据和列表预览必须来自同一份快照、同一方向 —— 前端"一次拉前 N 条"的
    //阶梯展开（1→4→9→14）与收起 slice(0,1) 全都建立在"前缀同序"这个前提上，方向漂了就会撞重复行
    @Override
    public Page<CommentVo> getReplies(Long topCommentId, Long loginUserId, Integer pageNum, Integer pageSize) {
        //先主键查楼根：拿 goodsId 拼缓存 key，顺带完成"评论存在"与"确实是顶层"两道校验
        Comment top = getById(topCommentId);
        if (top == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        if (top.getParentId() != null) {
            //传回复 id 进这个端点是调用方的 bug（前端只会给顶层渲染展开按钮），给人话而不是 500
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能展开顶层评论下的回复");
        }
        long current = clampPageNum(pageNum);
        long size = clampPageSize(pageSize);

        List<CommentCacheItem> items = loadItemsCached(top.getGoodsId());
        List<CommentCacheItem> replies = new ArrayList<>();
        Map<Long, String> nickByUser = new HashMap<>();
        for (CommentCacheItem item : items) {
            if (item.getParentId() != null && item.getParentId().equals(topCommentId)) {
                replies.add(item);
            }
            nickByUser.putIfAbsent(item.getUserId(), item.getNickname() == null ? "" : item.getNickname());
        }
        //楼内排布与列表预览完全同序（orderReplies：回楼主主评论一组置顶，其余靠后，组内最新在前）
        //—— 前端"一次拉前 N 条"的阶梯展开（1→4→9→14）与收起 slice(0,1) 全建立在这个同前缀上，序漂了就撞重复行
        List<CommentVo> ordered = new ArrayList<>(replies.size());
        for (CommentCacheItem child : orderReplies(replies, topCommentId, top.getUserId())) {
            ordered.add(toReplyVo(child, topCommentId, top.getUserId(), nickByUser, loginUserId));
        }
        long from = Math.min((current - 1) * size, ordered.size());
        long to = Math.min(from + size, ordered.size());
        Page<CommentVo> voPage = new Page<>(current, size, ordered.size());
        voPage.setRecords(new ArrayList<>(ordered.subList((int) from, (int) to)));
        return voPage;
    }

    //缓存读取收口：列表与展开共用，同一 key、同一 TTL、同一降级方向（Redis 挂了 getOrLoad 回源 DB）
    private List<CommentCacheItem> loadItemsCached(Long goodsId) {
        //缓存"与登录者无关"的评论基础行；isOwner 是登录者维度的标记，读侧现算
        List<CommentCacheItem> items = cacheUtils.getOrLoad(
                RedisContent.commentListKey(goodsId), COMMENT_LIST_TTL,
                json -> JSONUtil.parseArray(json).toList(CommentCacheItem.class),
                () -> loadCommentItems(goodsId));
        return items == null ? new ArrayList<>() : items;
    }

    private static long clampPageNum(Integer pageNum) {
        return (pageNum == null || pageNum < 1) ? 1 : pageNum;
    }

    private static long clampPageSize(Integer pageSize) {
        return (pageSize == null || pageSize < 1)
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 一条回复在楼内属于哪一组：0=回楼主【主评论】的（置顶第一排），1=接楼内某句话的（跟在后面）。
     * 判定优先看 replyToCommentId（@ 的具体那一行，V11 起写入侧落库）；
     * 存量为 null 时退化成"@ 的是不是楼主本人"—— 老数据里 @ 楼主基本就是回主评论，保守归 0 不冤
     */
    private static int replySection(CommentCacheItem child, Long topCommentId, Long topAuthorId) {
        if (child.getReplyToCommentId() != null) {
            return child.getReplyToCommentId().equals(topCommentId) ? 0 : 1;
        }
        return topAuthorId != null && topAuthorId.equals(child.getReplyToUserId()) ? 0 : 1;
    }

    /**
     * 楼内排布（抖音口径）：回楼主主评论的一组整体置顶，其余接话组跟在后面；
     * 组内保持传入序（= 回源 createTime 倒序，最新在前 —— 稳定分节不打乱它）。
     * 预览与展开共用这一个函数：两处必须同一序列，前端"前缀替换"式阶梯展开才不撞行
     */
    private static List<CommentCacheItem> orderReplies(List<CommentCacheItem> replies,
                                                       Long topCommentId, Long topAuthorId) {
        if (replies.size() < 2) {
            return replies;
        }
        List<CommentCacheItem> ordered = new ArrayList<>(replies.size());
        for (CommentCacheItem child : replies) {
            if (replySection(child, topCommentId, topAuthorId) == 0) {
                ordered.add(child);
            }
        }
        for (CommentCacheItem child : replies) {
            if (replySection(child, topCommentId, topAuthorId) != 0) {
                ordered.add(child);
            }
        }
        return ordered;
    }

    //缓存行 → 公开 VO。userId 现在是随行进公开出参的（前端点头像进 TA 的主页要用）——
    //这不构成新的信息暴露：主页路由 /seller/:id 本来就任何登录用户可进，商品 VO 也一直带着 sellerId；
    //isOwner 仍在这里现算 —— 顶层与回复同一条规则（回复行也要"我的回复可自删"）
    private CommentVo toVo(CommentCacheItem item, Long loginUserId) {
        CommentVo commentVo = new CommentVo();
        BeanUtils.copyProperties(item, commentVo);
        commentVo.setIsOwner(loginUserId != null && loginUserId.equals(item.getUserId()));
        return commentVo;
    }

    //「回复人 ▶ 被回复人」的箭头只给接话组（section 1）：回楼主主评论的那一组（section 0）本来
    //就置顶在楼主正下方，位置即表意，署名反而是噪音；但"回楼主的楼内回复"虽然后者是楼主本人，
    //它属于接话组、跟在后面，必须有箭头说明接的是哪句 —— 这正是箭头与分节同源判定（replySection）
    //而不再按"被 @ 人是不是楼主"的原因。昵称为空兜底「匿名用户」，箭头不指向空白
    private CommentVo toReplyVo(CommentCacheItem child, Long topCommentId, Long topAuthorId,
                                Map<Long, String> nickByUser, Long loginUserId) {
        CommentVo vo = toVo(child, loginUserId);
        if (replySection(child, topCommentId, topAuthorId) == 1 && child.getReplyToUserId() != null) {
            String name = nickByUser.get(child.getReplyToUserId());
            vo.setReplyToNickname(name == null || name.isEmpty() ? "匿名用户" : name);
        }
        return vo;
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
        //批量补商品标题与昵称，避免逐条查（和订单列表同一套做法）。
        //selectBatchIds 会自带 deleted=0（@TableLogic），所以“查不到”从 P3-5 起有了一个明确含义：
        //商品已被逻辑删除，而这条评论还在（这正是逻辑删除要留下的那份痕迹）
        Map<Long, String> titleMap = goodsMapper.selectBatchIds(
                        rows.stream().map(Comment::getGoodsId).distinct().toList()).stream()
                .collect(Collectors.toMap(Goods::getId, g -> g.getTitle() != null ? g.getTitle() : ""));
        Map<Long, String> nickNameMap = userMapper.selectBatchIds(
                        rows.stream().map(Comment::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
        for (Comment comment : rows) {
            CommentAdminVo vo = new CommentAdminVo();
            BeanUtils.copyProperties(comment, vo);
            //留一行空标题比给一句说明更难查：后台看到“这条评论的商品叫什么”应该是能直接读出答案的
            vo.setGoodsTitle(titleMap.getOrDefault(comment.getGoodsId(), "（商品已删除）"));
            vo.setNickname(nickNameMap.getOrDefault(comment.getUserId(), ""));
            voPage.getRecords().add(vo);
        }
        return voPage;
    }

    //回源：评论 + 作者信息一次批量查，避免逐条查用户的 N+1
    private List<CommentCacheItem> loadCommentItems(Long goodsId) {
        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Comment::getGoodsId, goodsId);
        queryWrapper.orderByDesc(Comment::getCreateTime);
        //注意：这里拉的是【顶层+回复的全部行】—— 楼中楼的树在读侧现拼，缓存只存扁平行
        List<Comment> commentList = list(queryWrapper);
        //没有评论属于正常情况：返回空列表（空列表也会写进缓存，顺带防穿透）
        if (commentList.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> userIdList = commentList.stream().map(Comment::getUserId).distinct().toList();
        //一次批查取回整个 User：昵称 + 头像同一趟拿（头像列在 user 表早就有，只是评论链路从没通过）
        Map<Long, User> userMap = userMapper.selectBatchIds(userIdList).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        List<CommentCacheItem> items = new ArrayList<>(commentList.size());
        for (Comment comment : commentList) {
            CommentCacheItem item = new CommentCacheItem();
            BeanUtils.copyProperties(comment, item);
            User author = userMap.get(comment.getUserId());
            item.setNickname(author != null && author.getNickname() != null ? author.getNickname() : "");
            item.setAvatar(author != null ? author.getAvatar() : null);
            items.add(item);
        }
        return items;
    }

    /**
     * 评论列表在 Redis 里的存储结构。
     * 为什么不直接缓存 CommentVo：这是"组装输入端"，比出参多几个只服务于读侧派生的内部字段
     *（replyToUserId 用来现推 @ 昵称、userId 用来算 isOwner），而 CommentVo 是"组装输出端"，
     * 带派生结果（isOwner/replyToNickname/楼内分节）不带推导原料 —— 两个方向的形状本就该分开维护。
     * 楼中楼兼容：老缓存 JSON 缺 parent_id/reply_to_user_id/reply_to_comment_id/avatar 字段，
     * hutool 解析为 null —— "顶层、无 @、无头像、排布走 @ 人保守归组"，对存量数据恰好是事实。
     * （缓存语义每翻一次就换 key 版本，见 RedisContent 的 v2/v3 注释；这里是形状变化的兜底说明）
     */
    @Data
    public static class CommentCacheItem {
        private Long id;
        private Long goodsId;
        private Long userId;
        private Long parentId;
        private Long replyToUserId;
        private Long replyToCommentId;
        private String nickname;
        private String avatar;
        private String content;
        private LocalDateTime createTime;
    }
}
