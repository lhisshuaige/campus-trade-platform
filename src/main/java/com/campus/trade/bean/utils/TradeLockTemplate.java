package com.campus.trade.bean.utils;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.config.RedissonClientHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
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
 *
 * 降级立场：本类【刻意不降级】。下单链路对 Redis 是硬依赖（这里加锁 + RedisWorker 生成订单号），
 *          只降级其中一处只会制造“半可用”假象；交易 QPS 极低，明确 503 让用户重试，
 *          好过把并发全部下压给 DB CAS。与之对照：RedisLockUtils 是可降级的性能锁，见文档 5.7。
 */
@Slf4j
@Component
public class TradeLockTemplate {

    //抢锁等待时间：0 表示不等待，与原 SETNX 语义一致，不占着 Tomcat 线程排队
    private static final long LOCK_WAIT_SECONDS = 0L;

    private final RedissonClientHolder redissonClientHolder;
    private final RedisCircuitBreaker circuitBreaker;

    public TradeLockTemplate(RedissonClientHolder redissonClientHolder,
                             RedisCircuitBreaker circuitBreaker) {
        this.redissonClientHolder = redissonClientHolder;
        this.circuitBreaker = circuitBreaker;
    }

    public <T> T executeWithGoodsLock(Long goodsId, Supplier<T> action) {
        if (goodsId == null) {
            //不校验会拼出 lock:goods:null，所有非法请求共用一把锁互相排队
            throw new BusinessException(ErrorCode.PARAM_ERROR, "商品id不能为空");
        }
        RLock lock;
        try {
            // current() 只读：拿不到就是 0ms 的 503，不会在请求线程里建连
            lock = redissonClientHolder.current().getLock(RedisContent.Goods_Lock_KEY + goodsId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取交易锁失败 goodsId={}", goodsId, e);
            circuitBreaker.recordFailure("redisson.getLock");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            // 能正常执行到这说明 Redis 是通的（locked=false 只是竞争失败，不是故障）→ 闭合熔断
            circuitBreaker.recordSuccess();
        } catch (InterruptedException e) {
            //恢复中断标志，让上层线程池能正确感知中断
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        } catch (Exception e) {
            // Redisson 连不上 Redis 抛的是 RedisException/IllegalStateException 等中间件异常。
            // 这里做两件事：① 喂熔断器（后续请求直接快速失败，不再各吃一次超时）② 翻译成 503 不外泄细节
            log.error("获取交易分布式锁失败 goodsId={}", goodsId, e);
            circuitBreaker.recordFailure("redisson.tryLock");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
        if (!locked) {
            //抢不到锁是正常业务竞争，不是服务端故障，用 400 而非 500
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品正在交易中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            // 释放失败只记日志：持锁期间 Redis 断连时 unlock 必抛，往外抛会【盖掉真正的业务异常】；
            // 此时锁不再被续期，看门狗停止后 30s 自动过期，由 TTL 兜底
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.error("释放交易分布式锁失败，等待锁自然过期 goodsId={}", goodsId, e);
            }
        }
    }
}