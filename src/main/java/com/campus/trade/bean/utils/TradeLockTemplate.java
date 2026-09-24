package com.campus.trade.bean.utils;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 交易分布式锁模板。做成模板方法而不是直接暴露 RLock，是为了让两个坑不可能被写错：
 * 1) 不传 leaseTime —— tryLock(waitTime, leaseTime, unit) 一旦指定 leaseTime 就【禁用看门狗】，
 *    等于白上 Redisson；这里用两参数版，Redisson 默认 30s 锁期 + 每 10s 自动续期。
 * 2) unlock 前判断持有者 —— RLock 非持有线程 unlock 会抛 IllegalMonitorStateException，
 *    发生在 finally 里会【盖掉真正的业务异常】，排查时看到的是假异常。
 *
 * 约束：加锁与解锁必须在同一线程（RLock 线程绑定），因此本类只用于业务临界区，
 *       CacheUtils 那种“请求线程加锁、重建线程解锁”的用法必须继续走 RedisLockUtils。
 */
@Component
public class TradeLockTemplate {

    //抢锁等待时间：0 表示不等待，与原 SETNX 语义一致，不占着 Tomcat 线程排队
    private static final long LOCK_WAIT_SECONDS = 0L;

    @Resource
    private RedissonClient redissonClient;

    public <T> T executeWithGoodsLock(Long goodsId, Supplier<T> action) {
        if (goodsId == null) {
            //不校验会拼出 lock:goods:null，所有非法请求共用一把锁互相排队
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品id不能为空");
        }
        RLock lock = redissonClient.getLock(RedisContent.Goods_Lock_KEY + goodsId);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //恢复中断标志，让上层线程池能正确感知中断
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "系统繁忙，请稍后重试");
        }
        if (!locked) {
            //抢不到锁是正常业务竞争，不是服务端故障，用 400 而非 500
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品正在交易中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}