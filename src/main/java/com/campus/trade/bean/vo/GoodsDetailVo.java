package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsDetailVo {
    private Long id;
    private String title;
    private BigDecimal price;
    private String imgUrl;
    private String description;
    private Long categoryId;
    //详情页要决定"能不能下单/按钮文案"，原来没有 status，前端只能再调一次接口或猜
    private Integer status;
    private Integer collectCount;
    //二手交易关键信息：标价与可议价底线、成色、交易地点
    private BigDecimal expectPrice;
    private String conditionLevel;
    private String tradeLocation;
    //所在校区（V9），存量商品为 null
    private String campus;
    //浏览量：详情是单条读，这里给的是 DB 存量 + Redis 未落库增量的实时值
    private Integer viewCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    //卖家展示信息：昵称/头像可公开；手机号、账号状态一律不外泄（脱敏白名单）
    private String sellerNickname;
    private String sellerAvatar;
    //「不暴露内部标识」规范的一次有意突破，理由是具体的：/user/profile/{userId} 本就是
    //公开可枚举的接口，sellerId 不扩大任何隐私面；而"访问卖家主页/私信卖家"两个入口
    //都需要它——没有它前端只能再去调一次 profile，为一个跳转绕一圈才是怪事。
    //只加在 DetailVo：列表 GoodsVo 一行一个卡片，用不上跳转参数，保持干净
    private Long sellerId;

    //发布者主动公开的联系方式：只对登录用户返回，匿名访客为 null（把入口留给"愿意注册来看"的人）
    //不返回的是用户表里的 phone，两者不同：一个是发布者愿意给陌生人的联系渠道，一个是账号隐私
    private String contact;

    //后端算好的布尔标记，替代"把 userId 给前端自己去比"（VO 不暴露内部标识规范）
    private Boolean isOwner;
    //当前登录者是否收藏过该商品，匿名访问为 false；与登录态有关，所以不参与缓存
    private Boolean isCollected;

}
