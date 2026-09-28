package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.notice.NoticePageQueryDTO;
import com.campus.trade.bean.vo.NoticeVo;

public interface NoticeService {

    /**
     * 发一条站内信。receiverId 与 actorId 相同时直接跳过（不给自己发"有人评论了你"的自言自语）。
     * 与各业务动作同事务：主业务回滚了消息不能留下，宁可少发也不发错。
     */
    void send(Long receiverId, Long actorId, String type, String title, String content, Long bizId);

    //我的收件箱（分页）
    Page<NoticeVo> pageMyNotices(Long loginUserId, NoticePageQueryDTO queryDTO);

    //未读角标
    long unreadCount(Long loginUserId);

    //标记单条已读：只有接收者本人能标
    void markRead(Long noticeId, Long loginUserId);

    //一键全读，返回真正被改动的条数
    int markAllRead(Long loginUserId);
}
