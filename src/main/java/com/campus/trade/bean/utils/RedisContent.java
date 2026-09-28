package com.campus.trade.bean.utils;

public class RedisContent {
    public static final String Category_List_KEY = "category:list";

    // token 黑名单 key 前缀（实际 key = 前缀 + token）
    public static final String Token_Blacklist_KEY = "token:blacklist:";

    // 用户每日登录/登出计数 key 前缀（实际 key = 前缀 + userId + : + 日期）
    public static final String User_Loginout_Count_KEY = "user:loginout:count:";

    // 用户级 Token 版本号缓存 key 前缀（实际 key = 前缀 + userId），改密/禁用后自增版本使旧 Token 失效
    // value 格式：version|status，一次 GET 同时拿到版本号真值与账号状态（见 TokenVersionUtils）
    public static final String User_Token_Version_KEY = "user:tokenVersion:";

    // 登录连续失败计数 key 前缀（实际 key = 前缀 + userId），TTL = 锁定时长，到期自动解锁
    public static final String User_Login_Fail_KEY = "user:login:fail:";

    // 商品下单分布式锁 key 前缀（实际 key = 前缀 + goodsId）
    public static final String Goods_Lock_KEY = "lock:goods:";
    public static final String Order_NO_KEY = "order:no:";


    public static final String Goods_Detail_KEY = "goods:detail:";

    // 评论列表缓存 key 前缀（实际 key = 前缀 + goodsId）：一个商品一个 key，
    // 发/删评论、删商品都能精确算出要删哪个，所以值得缓存。
    // 缓存的是"与登录者无关"的评论行，isOwner 由读侧按当前登录人现算
    //（把 isOwner 写进共享 key，A 的"我的评论"就会返回给 B 看）
    public static final String Comment_List_KEY = "comment:list:";

    // 收藏排行榜缓存 key：故意不带 limit 后缀，只存 Top50 全集，读取时在内存里截断。
    // 若做成 collect:rank:{limit} 一堆 key，收藏/上下架后无法枚举失效，只能等 TTL 自然过期；
    // StatisticsServiceImp 的热门商品统计也复用本 key，不再另存一份（否则失效时机不一致）
    public static final String Collect_Rank_KEY = "collect:rank";

    // 浏览量增量（尚未落库的部分）：一张 hash，field = goodsId，value = 累计 PV。
    // 刻意不做成 goods:view:{id} 一堆独立 key：落库任务要枚举出"哪些商品有增量"，
    // 独立 key 只能靠 SCAN（库大后 SCAN 是禁项），一张 hash 一次 HGETALL 就扫完，
    // key 数量恒定为 1，不随商品数增长
    public static final String Goods_View_Delta_KEY = "goods:view:delta";

    // 商品详情缓存 key：统一在此拼装，避免各 Service 各拼一份导致 evict 漏删
    public static String goodsDetailKey(Long goodsId) {
        return Goods_Detail_KEY + goodsId;
    }

    public static String commentListKey(Long goodsId) {
        return Comment_List_KEY + goodsId;
    }

    // 下面三个 key 定义了却从未被使用，这次直接删掉，而不是"留着以后用"。
    // 它们的共同毛病是"写侧枚举不出该失效的 key" —— 属于设计上就不该缓存的反例：
    //   user:info:{id}      卖家展示信息改为随详情读时现查（一次主键命中）。
    //                       缓存它就要在 user 更新时反向删缓存，多一份失效时机就多一个漏删点
    //   goods:page:{...}    列表 key 必然带上 分类/关键字/页码 的组合，任意一次商品写入
    //                       都要失效"所有组合"，只能等 TTL；和 collect:rank 不带 limit 后缀是同一条教训
    //   collect:list:{uid}  个人维度 + 跨商品的派生视图：改一次商品标题就得删掉所有收藏过它的
    //                       用户的 key，失效面不可枚举。收藏表有 uk + 索引，直接查库即可
    // 健康探测用 key（只读不写，EXISTS 一下即返回）
    public static final String Redis_Health_KEY = "health:redis";

    // 工具类，禁止实例化
    private RedisContent() {
    }
}
