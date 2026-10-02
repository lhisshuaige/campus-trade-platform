package com.campus.trade.bean.vo;

import lombok.Data;

/**
 * 聊天页的"当前这对关系能怎么发"权威状态。
 * 前端不靠"消息第一页有没有对方气泡"去猜解锁态——那样当会话超过一页时会误判；
 * 这里的每个布尔都由后端和 send() 同源的 exists 查询算出，口径永不漂移。
 * 只暴露"我和某人的私信关系"这一本人维度，不含任何他人隐私
 */
@Data
public class DmStateVo {

    //对方是否给"我"发过任意消息（回复或TA自己先开口）→ true 则双方自由聊，不限字数
    private boolean unlocked;
    //未解锁时"我"是否已经发出过那条唯一招呼 → true 则招呼已用完，只能等对方回复
    private boolean greetedByMe;
    //我是否把对方拉黑了（双向断开：拉黑后我也发不出去，须先"取消拉黑"才能再联系）
    private boolean blockedByMe;
    //对方是否把我拉黑了（是则我这条通道被关闭，输入应禁用并说明原因）
    private boolean blockedByPeer;
    //未解锁状态下招呼的字数上限，前端计数器与后端校验共用同一个数，杜绝两侧不一致
    private int firstMsgMax;
}
