package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.notice.NoticePageQueryDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.vo.NoticeVo;
import com.campus.trade.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信。所有端点都以登录态为准：收件人 id 一律取 request.getAttribute("loginUserId")，
 * 接口上没有任何一个"接收方 userId"参数，所以不存在翻别人收件箱的写法。
 */
@RestController
@RequestMapping("/notice")
@Tag(name = "消息通知接口", description = "下单/确认/被收藏/被评论的站内通知")
public class NoticeController {

    @Resource
    private NoticeService noticeService;

    @GetMapping("/myPage")
    @Operation(summary = "我的消息(分页)", description = "可按类型筛选、可只看未读，按产生时间倒序")
    public MyResult<Page<NoticeVo>> myPage(@Valid NoticePageQueryDTO queryDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(noticeService.pageMyNotices(loginUserId, queryDTO));
    }

    @GetMapping("/unreadCount")
    @Operation(summary = "未读消息数", description = "给角标用，只返回计数")
    public MyResult<Long> unreadCount(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(noticeService.unreadCount(loginUserId));
    }

    @PutMapping("/read")
    @Operation(summary = "标记单条已读", description = "只有接收者本人可标记；重复标记幂等")
    public MyResult<Void> read(@RequestParam("id") Long noticeId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        noticeService.markRead(noticeId, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/readAll")
    @Operation(summary = "全部标记已读", description = "返回真正被改动的条数")
    public MyResult<Integer> readAll(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(noticeService.markAllRead(loginUserId));
    }
}
