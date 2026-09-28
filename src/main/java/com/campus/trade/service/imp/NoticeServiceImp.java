package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.DTO.request.notice.NoticePageQueryDTO;
import com.campus.trade.bean.entry.Notice;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.vo.NoticeVo;
import com.campus.trade.mapper.NoticeMapper;
import com.campus.trade.service.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@Transactional
public class NoticeServiceImp extends ServiceImpl<NoticeMapper, Notice> implements NoticeService {

    //类型取值域，与 init.sql 的 chk_notice_type 同源
    private static final Set<String> TYPES = Set.of(
            Notice.TYPE_ORDER, Notice.TYPE_COLLECT, Notice.TYPE_COMMENT, Notice.TYPE_SYSTEM);
    private static final int TITLE_MAX_LENGTH = 100;

    @Override
    public void send(Long receiverId, Long actorId, String type, String title, String content, Long bizId) {
        //三种情况直接跳过，都不该让主业务失败：没收件人、自己给自己的商品互动、类型不认识
        //（类型不认识说明调用方写错了，记 warn 比抛异常合适：通知丢了可以补，下单不能因为文案写错回滚）
        if (receiverId == null || receiverId.equals(actorId)) {
            return;
        }
        if (!TYPES.contains(type)) {
            log.warn("站内信类型不合法，已跳过接收者通知 receiverId={} type={}", receiverId, type);
            return;
        }
        Notice notice = new Notice();
        notice.setUserId(receiverId);
        notice.setType(type);
        notice.setTitle(limit(title, TITLE_MAX_LENGTH));
        notice.setContent(limit(content, Notice.CONTENT_MAX_LENGTH));
        notice.setBizId(bizId);
        notice.setIsRead(Notice.UNREAD);
        save(notice);
    }

    @Override
    public Page<NoticeVo> pageMyNotices(Long loginUserId, NoticePageQueryDTO queryDTO) {
        String type = queryDTO.getType();
        if (type != null && !type.isBlank() && !TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "消息类型只能是 order/collect/comment/system");
        }
        long current = (queryDTO.getPageNum() == null || queryDTO.getPageNum() < 1) ? 1 : queryDTO.getPageNum();
        long size = (queryDTO.getPageSize() == null || queryDTO.getPageSize() < 1)
                ? 10 : Math.min(queryDTO.getPageSize(), 50);
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<>();
        //归属条件写在最前面：这个接口按登录态查，永远不给调用方传 userId 的机会
        wrapper.eq(Notice::getUserId, loginUserId);
        wrapper.eq(Boolean.TRUE.equals(queryDTO.getUnreadOnly()), Notice::getIsRead, Notice.UNREAD);
        wrapper.eq(type != null && !type.isBlank(), Notice::getType, type);
        wrapper.orderByDesc(Notice::getCreateTime);
        //直查库不缓存：收件箱是"个人维度 + 写后即变"的视图，缓存的收益抵不上每次互动多一次失效
        return (Page<NoticeVo>) page(new Page<>(current, size), wrapper).convert(notice -> {
            NoticeVo vo = new NoticeVo();
            BeanUtils.copyProperties(notice, vo);
            return vo;
        });
    }

    //角标只查计数不查列表：前端每次进页面都要读它，查列表再数长度是白白搬一堆正文回来
    @Override
    public long unreadCount(Long loginUserId) {
        return count(new LambdaQueryWrapper<Notice>()
                .eq(Notice::getUserId, loginUserId)
                .eq(Notice::getIsRead, Notice.UNREAD));
    }

    @Override
    public void markRead(Long noticeId, Long loginUserId) {
        //条件里带上 user_id 与 is_read：一条 SQL 同时完成"归属校验 + 幂等"，
        //不先查再改（读-判-写窗口里别人的操作会覆盖，虽然这里最坏只是多标一次已读）
        LambdaUpdateWrapper<Notice> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notice::getId, noticeId)
                .eq(Notice::getUserId, loginUserId)
                .eq(Notice::getIsRead, Notice.UNREAD)
                .set(Notice::getIsRead, Notice.READ);
        if (baseMapper.update(null, wrapper) > 0) {
            return;
        }
        //0 行有三种可能：消息不存在、不是你的、本来就是已读。前两种必须区分出来，第三种是幂等重放
        Notice exist = getById(noticeId);
        if (exist == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        if (!exist.getUserId().equals(loginUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作他人的消息");
        }
    }

    @Override
    public int markAllRead(Long loginUserId) {
        LambdaUpdateWrapper<Notice> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notice::getUserId, loginUserId)
                .eq(Notice::getIsRead, Notice.UNREAD)
                .set(Notice::getIsRead, Notice.READ);
        return baseMapper.update(null, wrapper);
    }

    //截断而不是报错：通知文案里嵌的是用户自定义的商品标题，不该因为标题长到超限就把主业务打回
    private String limit(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
