package com.campus.trade.bean.utils;

public class RedisContent {
    public static final String Category_List_KEY = "category:list";

    // token 黑名单 key 前缀（实际 key = 前缀 + token）
    public static final String Token_Blacklist_KEY = "token:blacklist:";

    // 用户每日登录/登出计数 key 前缀（实际 key = 前缀 + userId + : + 日期）
    public static final String User_Loginout_Count_KEY = "user:loginout:count:";

    // 商品下单分布式锁 key 前缀（实际 key = 前缀 + goodsId）
    public static final String Goods_Lock_KEY = "lock:goods:";
    public static final String Order_NO_KEY="order:no:";


    public static final String Goods_Detail_KEY = "goods:detail:";
    public static final String Comment_List_KEY = "comment:list:";
    public static final String User_Info_KEY    = "user:info:";
    public static final String Goods_Page_KEY   = "goods:page:";
    public static final String Collect_List_KEY = "collect:list:";

}
