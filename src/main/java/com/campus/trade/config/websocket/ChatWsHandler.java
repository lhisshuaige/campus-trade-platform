package com.campus.trade.config.websocket;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 私信下行通道。协议极简：下行只有 {"type":"message",...} 与 pong，
 * 上行只有 "ping" 心跳——消息"发送"永远走 REST（要过 @RateLimit/@Log/校验/事务一整套），
 * 所以这里不处理任何业务消息，也就不需要 STOMP
 */
@Slf4j
@Component
public class ChatWsHandler extends TextWebSocketHandler {

    @Resource
    private ChatWsManager chatWsManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = resolveUserId(session);
        if (userId == null) {
            //握手拦截器已鉴权，走到这里还没有身份 = 装配写错了，直接关而不是"匿名挂着"
            log.warn("私信连接缺少身份信息，关闭连接 sessionId={}", session.getId());
            closeQuietly(session);
            return;
        }
        chatWsManager.addSession(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        //上行只认心跳；其余内容一律忽略（不是协议分支，不该被当成错误——前端发错文本没必要断线）
        if ("ping".equals(message.getPayload())) {
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage("pong"));
                }
            } catch (Exception e) {
                log.warn("心跳回包失败，断开连接 sessionId={}", session.getId(), e);
                closeQuietly(session);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = resolveUserId(session);
        if (userId != null) {
            chatWsManager.removeSession(userId, session);
            log.info("私信连接关闭 userId={} sessionId={} status={}", userId, session.getId(), status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        //传输层出错后 session 状态不可靠：摘掉重连，而不是留着继续往死连接里推
        log.warn("私信连接传输异常 sessionId={}：{}", session.getId(), exception.getMessage());
        Long userId = resolveUserId(session);
        if (userId != null) {
            chatWsManager.removeSession(userId, session);
        }
        closeQuietly(session);
    }

    private Long resolveUserId(WebSocketSession session) {
        Object v = session.getAttributes().get("wsUserId");
        return v instanceof Long ? (Long) v : null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignore) {
            //已经坏了，关不上也没有可做的
        }
    }
}
