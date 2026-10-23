package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campus.trade.bean.dto.request.comment.CommentAddDTO;
import com.campus.trade.bean.entity.Comment;
import com.campus.trade.bean.entity.Goods;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.ContentAuditUtils;
import com.campus.trade.mapper.CommentMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.NoticeService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.vo.CommentVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2：楼中楼的写入归一化、读侧组装、级联删除与缓存协同。
 *
 * 手法沿用 UserServiceImplSecurityTest 的理由（MyBatis-Plus 的 save/getById/list/remove
 * 是 IService default 方法，@InjectMocks 管不到继承链）：spy 真实对象 + ReflectionTestUtils
 * 手工灌 @Resource，基类方法用 doReturn/doNothing 打桩。
 * CacheUtils 直接 mock —— 本类锁的是 Service 的分堆/排布/派生逻辑，缓存引擎自身有它自己的行为，
 * 打桩让"缓存"原样吐回源数据即可。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("L2 CommentServiceImpl：楼中楼组装 / 归一化 / 连坐删")
class CommentServiceImplTest {

    @Mock private CommentMapper commentMapper;
    @Mock private UserMapper userMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private CacheUtils cacheUtils;
    @Mock private ContentAuditUtils contentAuditUtils;
    @Mock private NoticeService noticeService;

    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new CommentServiceImpl());
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "goodsMapper", goodsMapper);
        ReflectionTestUtils.setField(service, "cacheUtils", cacheUtils);
        ReflectionTestUtils.setField(service, "contentAuditUtils", contentAuditUtils);
        ReflectionTestUtils.setField(service, "noticeService", noticeService);
        // MP 的 IService default 方法最终走 getBaseMapper()，spy 不打桩就是真跑
        ReflectionTestUtils.setField(service, "baseMapper", commentMapper);
    }

    /** 不带 replyToCommentId 的旧式构造（= V11 前的存量行，分节走 @人保守归组） */
    private CommentServiceImpl.CommentCacheItem item(Long id, Long goodsId, Long userId, Long parentId,
                                                     Long replyToUserId, String nickname, String content,
                                                     LocalDateTime time) {
        return item(id, goodsId, userId, parentId, replyToUserId, null, nickname, content, time);
    }

    private CommentServiceImpl.CommentCacheItem item(Long id, Long goodsId, Long userId, Long parentId,
                                                     Long replyToUserId, Long replyToCommentId,
                                                     String nickname, String content, LocalDateTime time) {
        CommentServiceImpl.CommentCacheItem it = new CommentServiceImpl.CommentCacheItem();
        it.setId(id); it.setGoodsId(goodsId); it.setUserId(userId);
        it.setParentId(parentId); it.setReplyToUserId(replyToUserId);
        it.setReplyToCommentId(replyToCommentId);
        it.setNickname(nickname); it.setContent(content); it.setCreateTime(time);
        return it;
    }

    /** 让 mock 的 getOrLoad 原样回传预置行（等价"缓存未命中、回源就是这批"）。
     *  CacheUtils 上 getOrLoad 有 Function/Class 两个重载，any() 判不了型，matcher 要显式带泛型 */
    private void stubCache(List<CommentServiceImpl.CommentCacheItem> rows) {
        when(cacheUtils.getOrLoad(anyString(), any(Duration.class),
                ArgumentMatchers.<Function<String, List<CommentServiceImpl.CommentCacheItem>>>any(),
                ArgumentMatchers.<Supplier<List<CommentServiceImpl.CommentCacheItem>>>any()))
                .thenReturn(rows);
    }

    private Comment comment(Long id, Long goodsId, Long userId, Long parentId) {
        Comment c = new Comment();
        c.setId(id); c.setGoodsId(goodsId); c.setUserId(userId); c.setParentId(parentId);
        c.setContent("x"); c.setCreateTime(LocalDateTime.now());
        return c;
    }

    private void stubGoods(Long goodsId, Long ownerId) {
        Goods g = new Goods();
        g.setId(goodsId); g.setUserId(ownerId); g.setTitle("高数教材");
        when(goodsMapper.selectById(goodsId)).thenReturn(g);
    }

    // ===================== 读侧组装 =====================

    @Test
    @DisplayName("列表按楼分页：顶层倒序、预览=楼内排布首条（回楼主主评论置顶组，组内最新在前）、total=楼数")
    void getCommentVoListAssemblesThreads() {
        stubGoods(5L, 2L);   // 列表每次真查商品存在（缓存里没有商品行的纪律）
        LocalDateTime t = LocalDateTime.of(2026, 10, 20, 10, 0);
        //回源口径 = createTime 倒序；13 是 V11 前存量（@楼主但没记目标行）→ 保守归置顶组
        List<CommentServiceImpl.CommentCacheItem> rows = new ArrayList<>(List.of(
                item(12L, 5L, 8L, 10L, 7L, "丁", "@丙的回复", t.plusMinutes(50)),
                item(11L, 5L, 7L, 10L, null, "丙", "第5条回复", t.plusMinutes(40)),
                item(13L, 5L, 7L, 10L, 2L, "丙", "回楼主主评论", t.plusMinutes(30)),
                item(14L, 5L, 9L, 10L, null, "戊", "第3条回复", t.plusMinutes(20)),
                item(15L, 5L, 8L, 10L, null, "己", "第1条回复", t.plusMinutes(10)),
                item(10L, 5L, 2L, null, null, "甲", "顶层A", t),
                item(20L, 5L, 3L, null, null, "卖家", "顶层B", t.minusDays(2))   // 更早的顶层
        ));
        stubCache(rows);

        Page<CommentVo> page = service.getCommentVoList(5L, 2L, 1, 10);

        assertEquals(2, page.getTotal());               // 2 栋楼，6 条行不算楼
        assertEquals(2, page.getRecords().size());
        CommentVo first = page.getRecords().get(0);
        assertEquals(10L, first.getId());               // 顶层按时间倒序，最新的楼在前
        assertTrue(first.getIsOwner());
        assertEquals(5, first.getReplyCount());
        //置顶组只有 13（回楼主主评论，无箭头）→ 它就是预览那条，哪怕接话组里有更新的丁
        assertEquals(List.of(13L), first.getReplies().stream().map(CommentVo::getId).toList());
        assertNull(first.getReplies().get(0).getReplyToNickname());
        CommentVo second = page.getRecords().get(1);
        assertEquals(20L, second.getId());
        assertEquals(0, second.getReplyCount());
        assertTrue(second.getReplies().isEmpty());
    }

    @Test
    @DisplayName("楼内排布：回楼主主评论一组置顶（无箭头），接话组靠后（有箭头）——"
            + "包括'接楼主在楼里的回复'这种被@人也是楼主、但必须露箭头的情形")
    void getRepliesSectionsRepliesToTopFirst() {
        Comment top = comment(10L, 5L, 2L, null);        // 楼主甲(2)
        doReturn(top).when(service).getById(10L);
        LocalDateTime t = LocalDateTime.of(2026, 10, 20, 10, 0);
        stubCache(new ArrayList<>(List.of(
                //接话组：丁 @ 丙 —— 被 @ 的行是楼里的 7（不是楼根 10），所以归接话组、带箭头
                item(12L, 5L, 8L, 10L, 7L, 7L, "丁", "@丙", t.plusMinutes(50)),
                //某某 @ 了一个查不到昵称的行（99）→ 箭头兜底"匿名用户"
                item(16L, 5L, 9L, 10L, 99L, 99L, "某某", "@一个查不到的人", t.plusMinutes(40)),
                //★ 核心用例：接"楼主甲在楼里的回复(17)" —— 被@人是楼主本人，但属接话组，必须带箭头
                item(13L, 5L, 7L, 10L, 2L, 17L, "丙", "@楼主的楼内回复", t.plusMinutes(30)),
                //回楼主【主评论】：明确 @ 的是楼根 10 → 置顶组，无箭头
                item(15L, 5L, 8L, 10L, 2L, 10L, "己", "回楼主主评论", t.plusMinutes(10)),
                item(10L, 5L, 2L, null, null, "甲", "顶层", t)
        )));

        Page<CommentVo> page = service.getReplies(10L, 8L, 1, 2);

        assertEquals(4, page.getTotal());
        //置顶组（只有 15）排最前，哪怕它是全楼最早的一条；其后接话组按时间倒序 12→16→13
        assertEquals(List.of(15L, 12L), page.getRecords().stream().map(CommentVo::getId).toList());
        CommentVo pinned = page.getRecords().get(0);
        assertNull(pinned.getReplyToNickname());         // 回楼主主评论 → 无箭头
        assertTrue(pinned.getIsOwner());                 // 登录者己(8) 的置顶回复
        CommentVo newest = page.getRecords().get(1);     // 丁 @ 丙
        assertEquals("丙", newest.getReplyToNickname());
        Page<CommentVo> page2 = service.getReplies(10L, 8L, 2, 2);
        assertEquals(List.of(16L, 13L), page2.getRecords().stream().map(CommentVo::getId).toList());
        assertEquals("匿名用户", page2.getRecords().get(0).getReplyToNickname());
        //丙 接的是楼主的楼内回复 → 被@人=楼主也不能吞箭头，这正是 reply_to_user_id 单独存不够用的原因
        assertEquals("甲", page2.getRecords().get(1).getReplyToNickname());
    }

    @Test
    @DisplayName("给回复的 id 调展开 = 调用方 bug，给人话 400 而不是把回复当楼根组装")
    void getRepliesRejectsNonTop() {
        Comment reply = comment(11L, 5L, 7L, 10L);
        doReturn(reply).when(service).getById(11L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getReplies(11L, 1L, 1, 10));
        assertTrue(ex.getMsg().contains("顶层"));
    }

    // ===================== 写入归一化 =====================

    @Test
    @DisplayName("回复一条回复：parentId 压平成楼根、replyToCommentId 记被 @ 的那一行，通知打给其作者")
    void addReplyFlattensAndNotifiesRepliedAuthor() {
        stubGoods(5L, 2L);
        Comment parentReply = comment(11L, 5L, 7L, 10L);   // 楼根10下的回复，作者丙
        doReturn(parentReply).when(service).getById(11L);
        doReturn(true).when(service).save(any(Comment.class));

        CommentAddDTO dto = new CommentAddDTO();
        dto.setGoodsId(5L);
        dto.setContent("   顶一下丙   ");
        dto.setParentId(11L);                               // 客户端把"回复的那条"原样传来
        service.addComment(dto, 8L);

        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(service).save(cap.capture());
        Comment saved = cap.getValue();
        assertEquals(10L, saved.getParentId());             // 压平成楼根，而不是 11
        assertEquals(7L, saved.getReplyToUserId());         // 默认 @ 被回复行作者
        assertEquals(11L, saved.getReplyToCommentId());     // @ 的具体那一行，楼内排布靠它分节
        assertEquals("顶一下丙", saved.getContent());       // trim 后进库
        verify(noticeService).send(eq(7L), eq(8L), eq("comment"),
                eq("你的评论收到新回复"), anyString(), eq(5L));
    }

    @Test
    @DisplayName("@ 一个不在本楼的评论 id：跨楼认领被拒")
    void addReplyRejectsCrossThreadAt() {
        stubGoods(5L, 2L);
        Comment topA = comment(10L, 5L, 2L, null);
        Comment topB = comment(20L, 5L, 3L, null);
        doReturn(topA).when(service).getById(10L);
        doReturn(topB).when(service).getById(20L);

        CommentAddDTO dto = new CommentAddDTO();
        dto.setGoodsId(5L); dto.setContent("ok"); dto.setParentId(10L); dto.setReplyToCommentId(20L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addComment(dto, 8L));
        assertTrue(ex.getMsg().contains("跨楼"));
        verify(service, never()).save(any(Comment.class));
    }

    @Test
    @DisplayName("顶层评论行为零变化：三个回复字段全空、通知仍打商品主人")
    void addTopLevelUnchanged() {
        stubGoods(5L, 2L);
        doReturn(true).when(service).save(any(Comment.class));

        CommentAddDTO dto = new CommentAddDTO();
        dto.setGoodsId(5L); dto.setContent("书还在吗");
        service.addComment(dto, 8L);

        ArgumentCaptor<Comment> cap = ArgumentCaptor.forClass(Comment.class);
        verify(service).save(cap.capture());
        assertNull(cap.getValue().getParentId());
        assertNull(cap.getValue().getReplyToUserId());
        assertNull(cap.getValue().getReplyToCommentId());
        verify(noticeService).send(eq(2L), eq(8L), eq("comment"),
                eq("你的商品收到新评论"), anyString(), eq(5L));
    }

    // ===================== 级联删除 =====================

    @Test
    @DisplayName("删顶层连坐整楼：removeById 之后还有一条按 parent_id 的批量逻辑删")
    void deleteTopCascadesThread() {
        Comment top = comment(10L, 5L, 2L, null);
        doReturn(top).when(service).getById(10L);
        doReturn(true).when(service).removeById(10L);
        doReturn(true).when(service).remove(any(Wrapper.class));

        service.deleteComment(10L, 2L, "user");

        verify(service).removeById(10L);
        verify(service).remove(any(Wrapper.class));
        verify(cacheUtils).evictAfterCommit("comment:list:v3:5");   // 楼与回复同一个 key，evict 一次
    }

    @Test
    @DisplayName("删回复只动自己：不许连坐（它下面根本没有楼）")
    void deleteReplyTouchesOnlyItself() {
        Comment reply = comment(11L, 5L, 7L, 10L);
        doReturn(reply).when(service).getById(11L);
        doReturn(true).when(service).removeById(11L);

        service.deleteComment(11L, 7L, "user");

        verify(service).removeById(11L);
        verify(service, never()).remove(any(Wrapper.class));
        verify(cacheUtils).evictAfterCommit("comment:list:v3:5");
    }

    @Test
    @DisplayName("权限判定没被楼中楼改动：删别人的楼仍是 403")
    void deletePermissionUnchangedForNonOwner() {
        Comment top = comment(10L, 5L, 2L, null);
        doReturn(top).when(service).getById(10L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteComment(10L, 999L, "user"));
        assertTrue(ex.getMsg().contains("不能删除"));
        verify(service, never()).removeById(anyLong());
    }
}
