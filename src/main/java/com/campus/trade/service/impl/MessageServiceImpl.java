package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.dto.request.message.MessageSendDTO;
import com.campus.trade.bean.entity.Goods;
import com.campus.trade.bean.entity.Message;
import com.campus.trade.bean.entity.User;
import com.campus.trade.bean.entity.UserBlock;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.vo.BlockedUserVo;
import com.campus.trade.bean.vo.ConversationVo;
import com.campus.trade.bean.vo.DmStateVo;
import com.campus.trade.bean.vo.MessageVo;
import com.campus.trade.config.websocket.ChatWsManager;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.MessageMapper;
import com.campus.trade.mapper.UserBlockMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements MessageService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private UserBlockMapper userBlockMapper;
    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private ChatWsManager chatWsManager;

    @Override
    public MessageVo send(Long loginUserId, MessageSendDTO dto) {
        Long toUserId = dto.getToUserId();
        if (toUserId.equals(loginUserId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能给自己发私信");
        }
        User recipient = userMapper.selectById(toUserId);
        if (recipient == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "收件人不存在");
        }
        //status==1 才算正常账号，口径与 JwtInterceptor/TokenVersionUtils 同源；
        //封禁/注销用户不该继续收消息
        if (recipient.getStatus() == null || recipient.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该用户当前不可私信");
        }
        //拉黑校验（双向断开）：一旦拉黑，双方都发不出私信——
        //对方把我拉黑 → 我发不过去；我把对方拉黑 → 我也发不过去，须先取消拉黑。
        //拉黑因此是一次完整的关系切断，而不是"只挡对方、自己还能随时反悔再骚扰"的单阀
        if (isBlocked(toUserId, loginUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "对方已将你拉黑，无法发送私信");
        }
        if (isBlocked(loginUserId, toUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "你已拉黑对方，取消拉黑后才能继续发送私信");
        }
        //交流门槛（替代旧"收件人必须是卖家"口径——那条把聊天锁死成单向，见 2026-10-01 复盘）：
        //  已解锁 = 对方给"我"发过任意一条消息（回复、或 TA 自己先开口都算搭话），此后随便聊；
        //  未解锁时"我"总共只有一次机会：发出一条 ≤FIRST_MSG_MAX_LENGTH 字的招呼。
        //  规则对双方对称，所以谁都能先开口；招呼已发、对方还没回 → 不能再发，
        //  骚扰被封顶在"一条短句"，这比"必须是卖家"更贴产品初衷也不误伤买家先开口的场景。
        if (!peerHasSpoken(loginUserId, toUserId)) {
            if (iHaveGreeted(loginUserId, toUserId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "你已发过招呼，对方回复前无法继续发送私信");
            }
            if (dto.getContent().trim().length() > Message.FIRST_MSG_MAX_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "首次打招呼请控制在" + Message.FIRST_MSG_MAX_LENGTH + "字以内，对方回复后即可自由交流");
            }
        }
        if (dto.getGoodsId() != null) {
            //上下文商品必须存在且属于聊天双方之一：买家带着卖家的商品开场、
            //卖家回话时继续挂自家商品 id，两个方向都合法。
            //旧口径"必须属于收件人"会把卖家携带自己 goodsId 的回话顶成 400，是隐藏 bug
            Goods goods = goodsMapper.selectById(dto.getGoodsId());
            if (goods == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "关联商品不存在");
            }
            if (!toUserId.equals(goods.getUserId()) && !loginUserId.equals(goods.getUserId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "关联商品与聊天双方不匹配");
            }
        }

        Message message = new Message();
        message.setFromId(loginUserId);
        message.setToId(toUserId);
        message.setGoodsId(dto.getGoodsId());
        message.setContent(dto.getContent());
        message.setIsRead(Message.UNREAD);
        //显式赋值而不是靠 DB DEFAULT：insert 不会回填 generated 列，
        //而下行的 MessageVo 此刻就在手里，createTime 留 null 前端时间渲染空白
        message.setCreateTime(LocalDateTime.now());
        save(message);

        MessageVo vo = new MessageVo();
        BeanUtils.copyProperties(message, vo);
        //推送排在事务提交后：回滚了消息根本不存在，却把气泡推上对方在线页面，比丢一条更糟
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                chatWsManager.pushToUser(toUserId, vo);
            }
        });
        return vo;
    }

    @Override
    public Page<ConversationVo> conversations(Long loginUserId, Integer pageNum, Integer pageSize) {
        Page<ConversationVo> page = new Page<>(normalizeCurrent(pageNum), normalizeSize(pageSize, 10));
        //会话列表直查库不缓存：与 Notice 同理——个人维度 + 写后即变，每次发消息多一次失效不划算
        return (Page<ConversationVo>) baseMapper.selectConversationVoPage(page, loginUserId);
    }

    @Override
    public Page<MessageVo> pageBetween(Long loginUserId, Long peerId, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        //两个方向都圈进来，但登录者 id 是唯一锚点：不存在"传别人的 id 翻别人聊天记录"的口子
        wrapper.and(w -> w
                        .eq(Message::getFromId, loginUserId).eq(Message::getToId, peerId)
                        .or()
                        .eq(Message::getFromId, peerId).eq(Message::getToId, loginUserId));
        wrapper.orderByDesc(Message::getId);
        return (Page<MessageVo>) page(new Page<>(normalizeCurrent(pageNum), normalizeSize(pageSize, 20)), wrapper)
                .convert(m -> {
                    MessageVo vo = new MessageVo();
                    BeanUtils.copyProperties(m, vo);
                    return vo;
                });
    }

    @Override
    public long unreadCount(Long loginUserId) {
        //角标只查计数不查列表：前端 60s 一次，查列表再数长度是白白搬一堆正文回来
        return count(new LambdaQueryWrapper<Message>()
                .eq(Message::getToId, loginUserId)
                .eq(Message::getIsRead, Message.UNREAD));
    }

    @Override
    public int readConversation(Long loginUserId, Long peerId) {
        //与 Notice.markRead 同款"一条 SQL 完成归属校验 + 幂等"：
        //先查再改只会多一个竞态窗口，且这里"谁发的、发给谁、还没读过"三个条件天然就是归属校验
        LambdaUpdateWrapper<Message> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Message::getToId, loginUserId)
                .eq(Message::getFromId, peerId)
                .eq(Message::getIsRead, Message.UNREAD)
                .set(Message::getIsRead, Message.READ);
        return baseMapper.update(null, wrapper);
    }

    @Override
    public void block(Long loginUserId, Long targetUserId) {
        if (targetUserId.equals(loginUserId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能拉黑自己");
        }
        if (userMapper.selectById(targetUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //幂等：重复拉黑静默成功。"先查再插"存在并发窗口，但撞 uk_block_pair 只会发生在
        //同一人毫秒级双击上，insert 抛异常反而让按钮报假失败，这里查一次放过即可
        boolean blocked = userBlockMapper.exists(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, loginUserId)
                .eq(UserBlock::getBlockedId, targetUserId));
        if (blocked) {
            return;
        }
        UserBlock record = new UserBlock();
        record.setBlockerId(loginUserId);
        record.setBlockedId(targetUserId);
        userBlockMapper.insert(record);
    }

    @Override
    public void unblock(Long loginUserId, Long targetUserId) {
        //删 0 行不报错：没拉黑过的人"取消拉黑"结果也是"没拉黑"，幂等语义前端按钮直接吃
        userBlockMapper.delete(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, loginUserId)
                .eq(UserBlock::getBlockedId, targetUserId));
    }

    @Override
    public List<BlockedUserVo> blockedList(Long loginUserId) {
        List<UserBlock> records = userBlockMapper.selectList(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, loginUserId)
                .orderByDesc(UserBlock::getId));
        if (records.isEmpty()) {
            return List.of();
        }
        //两次查询代替手写 JOIN：拉黑名单是个位数量级，回表 selectBatchIds 足够，
        //还省一段只为拿昵称头像的 SQL；用户不存在（注销级联删行后不可能，但防御一行）时留空昵称
        Map<Long, User> peers = userMapper.selectBatchIds(
                        records.stream().map(UserBlock::getBlockedId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return records.stream().map(r -> {
            BlockedUserVo vo = new BlockedUserVo();
            vo.setUserId(r.getBlockedId());
            vo.setCreateTime(r.getCreateTime());
            User peer = peers.get(r.getBlockedId());
            if (peer != null) {
                vo.setNickname(peer.getNickname());
                vo.setAvatar(peer.getAvatar());
            }
            return vo;
        }).toList();
    }

    @Override
    public DmStateVo dmState(Long loginUserId, Long peerId) {
        //和 send() 用同一批 exists 判定，保证"前端显示的规则"=="后端实际执行的规则"，
        //不会出现"UI 说能发、后端拒 400"或反过来的漂移
        DmStateVo vo = new DmStateVo();
        vo.setUnlocked(peerHasSpoken(loginUserId, peerId));
        vo.setGreetedByMe(iHaveGreeted(loginUserId, peerId));
        vo.setBlockedByMe(isBlocked(loginUserId, peerId));
        vo.setBlockedByPeer(isBlocked(peerId, loginUserId));
        vo.setFirstMsgMax(Message.FIRST_MSG_MAX_LENGTH);
        return vo;
    }

    //以下三个判定是 send() 与 dmState() 的共同口径，各自精确命中对应索引、探一行即停

    //对方是否给"我"发过任意消息（回复/先开口都算）→ 解锁依据，命中 idx_msg_between(from,to)
    private boolean peerHasSpoken(Long me, Long peer) {
        return exists(new LambdaQueryWrapper<Message>()
                .eq(Message::getFromId, peer).eq(Message::getToId, me));
    }

    //"我"是否已向对方发过那条唯一招呼
    private boolean iHaveGreeted(Long me, Long peer) {
        return exists(new LambdaQueryWrapper<Message>()
                .eq(Message::getFromId, me).eq(Message::getToId, peer));
    }

    //blocker 是否把 blocked 拉黑了
    private boolean isBlocked(Long blockerId, Long blockedId) {
        return userBlockMapper.exists(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, blockerId).eq(UserBlock::getBlockedId, blockedId));
    }

    private long normalizeCurrent(Integer pageNum) {
        return (pageNum == null || pageNum < 1) ? 1 : pageNum;
    }

    //与 NoticeServiceImpl 同口径：缺省给 default，上限 50，防"pageSize=99999 一把搬库"
    private long normalizeSize(Integer pageSize, int defaultValue) {
        return (pageSize == null || pageSize < 1) ? defaultValue : Math.min(pageSize, 50);
    }
}
