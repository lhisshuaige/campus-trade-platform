package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.trade.bean.entity.Message;
import com.campus.trade.bean.vo.ConversationVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 会话分页：一行 = 一个聊天对象。没有 conversation 表（V5 注释写了为什么），现场聚合：
     *   t  —— 内层把 (me→peer) 和 (peer→me) 两个方向用 CASE 折成同一个 peer_id，MAX(id) 取最后一条；
     *   m  —— 按 last_id 回 message 拿正文/时间/商品上下文；
     *   u  —— JOIN `user` 拿对方昵称头像（反引号是必须的，USER 在 MySQL8 是关键字）；
     *   un —— 未读数单独聚合，不能并进 t 的 GROUP BY：t 每对会话只剩一行，
     *         count 会被聚成恒等于 1 的假数字。
     * ORDER BY m.id DESC：最后一条越新越靠前。分页/计数由 MP 拦截器在外层包 LIMIT/COUNT，
     * 计数走派生表整体行数，正确性不依赖这里的方向条件（WHERE 已圈定"我参与的"，
     * 不存在看到别人聊天记录的路径）
     */
    @Select("SELECT t.peer_id AS peerId, u.nickname AS peerNickname, u.avatar AS peerAvatar, " +
            "m.content AS lastContent, m.create_time AS lastTime, m.goods_id AS goodsId, " +
            "COALESCE(un.unread, 0) AS unreadCount " +
            "FROM ( " +
            "  SELECT CASE WHEN from_id = #{userId} THEN to_id ELSE from_id END AS peer_id, MAX(id) AS last_id " +
            "  FROM message " +
            "  WHERE from_id = #{userId} OR to_id = #{userId} " +
            "  GROUP BY CASE WHEN from_id = #{userId} THEN to_id ELSE from_id END " +
            ") t " +
            "JOIN message m ON m.id = t.last_id " +
            "JOIN `user` u ON u.id = t.peer_id " +
            "LEFT JOIN ( " +
            "  SELECT from_id AS peer_id, COUNT(*) AS unread " +
            "  FROM message " +
            "  WHERE to_id = #{userId} AND is_read = 0 " +
            "  GROUP BY from_id " +
            ") un ON un.peer_id = t.peer_id " +
            "ORDER BY m.id DESC")
    IPage<ConversationVo> selectConversationVoPage(IPage<ConversationVo> page, @Param("userId") Long userId);
}
