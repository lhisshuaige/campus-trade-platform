package com.campus.trade.config.websocket;

import com.campus.trade.bean.vo.MessageVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 在线私信连接的注册与下行推送。
 * 一个用户可能开多个标签页 → userId 对一个 session 列表（不是单值，否则后来者踢掉前者）。
 * 推送失败只记日志不抛：发消息的主链路（REST 落库）已经成功，"对方不在线/连接半死"
 * 不该让发送方看到 500——离线的人登录后从会话列表自然看到
 */
@Slf4j
@Component
public class ChatWsManager {

    @Resource
    private ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    public void addSession(Long userId, WebSocketSession session) {
        userSessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
        log.info("私信连接建立 userId={} sessionId={} 在线连接数={}", userId, session.getId(),
                userSessions.get(userId).size());
    }

    public void removeSession(Long userId, WebSocketSession session) {
        CopyOnWriteArrayList<WebSocketSession> list = userSessions.get(userId);
        if (list == null) {
            return;
        }
        list.remove(session);
        if (list.isEmpty()) {
            //空列表必须摘掉，否则长期积累一堆空 key（用户注册远多于注销，残留会一直涨）
            userSessions.remove(userId, list);
        }
    }

    /**
     * 把一条私信推给该用户的所有在线连接。离线/推送失败都不影响调用方
     */
    public void pushToUser(Long userId, MessageVo message) {
        CopyOnWriteArrayList<WebSocketSession> sessions = userSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            //走 Jackson 而不是 hutool：MessageVo 的 @JsonFormat 生效，下行时间和 REST 列表接口同格式
            payload = objectMapper.writeValueAsString(Map.of("type", "message", "data", message));
        } catch (Exception e) {
            log.warn("私信下行序列化失败，已跳过推送 userId={} msgId={}", userId, message.getId(), e);
            return;
        }
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                removeSession(userId, session);
                continue;
            }
            try {
                //WebSocketSession 不保证并发 sendMessage 安全：同一 session 上锁串行写
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                log.warn("私信推送失败，断开该连接 userId={} sessionId={}", userId, session.getId(), e);
                closeQuietly(session);
                removeSession(userId, session);
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (Exception ignore) {
            //关闭失败没补救余地，连接表里已经摘掉了
        }
    }
}
