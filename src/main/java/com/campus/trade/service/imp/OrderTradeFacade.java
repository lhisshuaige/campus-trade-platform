package com.campus.trade.service.imp;

import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.TradeLockTemplate;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.service.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 交易编排层：只负责两件事 —— “锁的边界”和“消息的时机”，不做任何业务判断。
 *
 * 存在的唯一理由：分布式锁必须包住整个事务。
 *   旧写法在 @Transactional 方法内部 tryLock/unlock，unlock 早于 commit，
 *   锁释放到事务提交之间的窗口里，别的线程能拿到锁并读到提交前的旧数据，临界区形同虚设。
 *
 * 本类及其方法【绝对不能】加 @Transactional：
 *   一旦加上，事务又回到锁内部开启，改动白做。
 *
 * 业务校验（归属权、状态预检、CAS）全部留在 OrderServiceImp，facade 不重复判断，
 * 否则业务规则散落两处，迟早漂移。
 */
@Slf4j
@Component
public class OrderTradeFacade {

    @Resource
    private OrderService orderService;      //注入接口 → 拿到的是事务代理
    @Resource
    private OrderMapper orderMapper;        //只为读不可变的 goodsId 拼锁 key
    @Resource
    private TradeLockTemplate tradeLockTemplate;
    @Resource
    private OrderTimeoutProducer orderTimeoutProducer;

    public Long createOrder(OrderCreateDTO vo, Long buyerId) {
        Long orderId = tradeLockTemplate.executeWithGoodsLock(vo.getGoodsId(),
                () -> orderService.createOrder(vo, buyerId));
        //走到这里内层事务已提交（且缓存也已在 afterCommit 里删过一轮），才允许发消息。
        //放在锁之外：临界区越短越好，发消息是网络 IO，不该占着锁。
        orderTimeoutProducer.sendOrderTimeoutCheck(orderId);
        return orderId;
    }

    public void cancelOrder(Long orderId, Long userId) {
        //锁 key 需要 goodsId，只能先在事务外做一次快照读。
        //敢这么用的前提：order.goods_id 写入后永不修改（项目没有任何接口改它），
        //所以“预读到的 goodsId”与“事务里的 goodsId”必然一致，锁 key 是稳定的。
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        tradeLockTemplate.executeWithGoodsLock(order.getGoodsId(), () -> {
            orderService.cancelOrder(orderId, userId);
            return null;
        });
    }

    //确认(0->1)、完成(1->2) 是单表单行状态迁移，CAS 已足够，不加锁：
    //能只用 CAS 就别加锁 —— 锁保护“多步临界区”，CAS 保护“单步原子性”。
    public void confirmOrder(Long orderId, Long sellerId) {
        orderService.confirmOrder(orderId, sellerId);
    }

    public void completeOrder(Long orderId, Long buyerId) {
        orderService.completeOrder(orderId, buyerId);
    }

    //预留：如需按商品维度做其它多步操作，锁 key 在这里统一取，避免各 Service 自己拼
    static String goodsLockKey(Long goodsId) {
        return RedisContent.Goods_Lock_KEY + goodsId;
    }
}