package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.dto.request.message.MessageSendDTO;
import com.campus.trade.bean.vo.BlockedUserVo;
import com.campus.trade.bean.vo.ConversationVo;
import com.campus.trade.bean.vo.DmStateVo;
import com.campus.trade.bean.vo.MessageVo;

import java.util.List;

public interface MessageService {

    /**
     * 发一条私信：落库 + 事务提交后把消息推给在线的收件人（离线的等登录后拉会话列表）。
     * 返回落库后的 MessageVo：发送方前端直接本地追加气泡，不用等回环
     */
    MessageVo send(Long loginUserId, MessageSendDTO dto);

    //我的会话列表（分页）
    Page<ConversationVo> conversations(Long loginUserId, Integer pageNum, Integer pageSize);

    //与某人的一对一历史（按 id 倒序分页——聊天窗先要最新一页，往前翻"更早"；
    //升序"翻页到尾"对聊天是反的）
    Page<MessageVo> pageBetween(Long loginUserId, Long peerId, Integer pageNum, Integer pageSize);

    //未读角标：只数"别人发给我的未读"，与 Notice.unreadCount 同口径
    long unreadCount(Long loginUserId);

    //进入某个会话时把对方发来的消息全部标已读，返回真正改动的条数
    int readConversation(Long loginUserId, Long peerId);

    //拉黑：双向断开——之后对方发不进我，我自己也发不出去，须先取消拉黑才能再联系对方。幂等——重复拉黑不报错
    void block(Long loginUserId, Long targetUserId);

    //取消拉黑：没拉黑过也当成功（删 0 行），前端按钮不该因竞态报错
    void unblock(Long loginUserId, Long targetUserId);

    //我拉黑的人（本人数据，带昵称头像）
    List<BlockedUserVo> blockedList(Long loginUserId);

    //聊天页权威状态：解锁/招呼已发/双向拉黑位 + 招呼字数上限，与 send() 同源
    DmStateVo dmState(Long loginUserId, Long peerId);
}
