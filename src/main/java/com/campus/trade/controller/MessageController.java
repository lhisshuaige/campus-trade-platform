package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.dto.request.message.MessageSendDTO;
import com.campus.trade.bean.dto.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RateLimit;
import com.campus.trade.bean.vo.BlockedUserVo;
import com.campus.trade.bean.vo.ConversationVo;
import com.campus.trade.bean.vo.DmStateVo;
import com.campus.trade.bean.vo.MessageVo;
import com.campus.trade.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 私信。与 NoticeController 同一条纪律：登录者 id 一律取 request.getAttribute("loginUserId")，
 * 没有任何"我的 userId"参数——别人的会话在这里拼不出来。
 * 全部端点默认需要登录：/message/** 不在 JwtInterceptor 排除名单里
 */
@RestController
@RequestMapping("/message")
@Tag(name = "私信接口", description = "买家↔卖家围绕商品的一对一消息")
public class MessageController {

    @Resource
    private MessageService messageService;

    @PostMapping("/send")
    @Operation(summary = "发送私信", description = "落库后经 /ws/chat 实时推给在线的收件人；返回体供发送方本地追加气泡")
    @Log("发送私信")
    //20 条/分钟：正常聊天按"打一段话说一句"的节奏足够，又明显高于脚本连点；
    //比下单(5)松是因为私信单条成本极低（一行 INSERT），骚扰风险主要靠频率而不是排队
    @RateLimit(maxCount = 20, key = "message:send", message = "发送私信过于频繁，请稍后再试")
    public MyResult<MessageVo> send(@Valid @RequestBody MessageSendDTO dto, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.send(loginUserId, dto));
    }

    @GetMapping("/conversations")
    @Operation(summary = "我的会话列表(分页)", description = "按各会话最后一条消息倒序，带未读数")
    public MyResult<Page<ConversationVo>> conversations(
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.conversations(loginUserId, pageNum, pageSize));
    }

    @GetMapping("/page")
    @Operation(summary = "与某人的一对一历史(分页)", description = "按 id 倒序返回（最新在前），前端展示时反转")
    public MyResult<Page<MessageVo>> page(
            @RequestParam("peerId") Long peerId,
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "20") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.pageBetween(loginUserId, peerId, pageNum, pageSize));
    }

    @GetMapping("/unreadCount")
    @Operation(summary = "私信未读数", description = "给导航角标用，只返回计数")
    public MyResult<Long> unreadCount(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.unreadCount(loginUserId));
    }

    @PutMapping("/read")
    @Operation(summary = "会话全部标记已读", description = "把 peer 发给我的未读全部置已读，返回真正改动的条数")
    public MyResult<Integer> read(@RequestParam("peerId") Long peerId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.readConversation(loginUserId, peerId));
    }

    @PostMapping("/block")
    @Operation(summary = "拉黑对方", description = "双向断开：之后双方都发不出私信，你取消拉黑后才能再联系对方；幂等，重复拉黑不报错")
    @Log("拉黑私信用户")
    public MyResult<Void> block(@RequestParam("userId") Long userId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        messageService.block(loginUserId, userId);
        return MyResult.success();
    }

    @PostMapping("/unblock")
    @Operation(summary = "取消拉黑", description = "没拉黑过也返回成功（幂等）")
    @Log("取消拉黑")
    public MyResult<Void> unblock(@RequestParam("userId") Long userId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        messageService.unblock(loginUserId, userId);
        return MyResult.success();
    }

    @GetMapping("/blockedList")
    @Operation(summary = "我的拉黑名单", description = "本人数据，带昵称头像；聊天页据此显示拉黑状态")
    public MyResult<List<BlockedUserVo>> blockedList(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.blockedList(loginUserId));
    }

    @GetMapping("/state")
    @Operation(summary = "与某人的私信规则状态", description = "解锁/招呼已发/双向拉黑位 + 招呼字数上限；聊天页据此精确控制输入，口径与 send() 同源")
    public MyResult<DmStateVo> state(@RequestParam("peerId") Long peerId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(messageService.dmState(loginUserId, peerId));
    }
}
