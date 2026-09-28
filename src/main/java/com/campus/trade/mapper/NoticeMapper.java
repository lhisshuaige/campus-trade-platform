package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.Notice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内信 Mapper。只用 BaseMapper：
 * 收件箱查询是单表 + 索引（user_id, is_read, create_time），没有联表需求，
 * 不像收藏列表要带商品快照才需要手写注解 SQL。
 */
@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {
}
