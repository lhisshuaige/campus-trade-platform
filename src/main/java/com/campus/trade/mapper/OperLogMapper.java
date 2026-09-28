package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.OperLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper。只用 BaseMapper：写侧是 LogAspect 单条 insert，
 * 读侧是"按操作人/结果 + 时间范围筛 + 时间倒序"的单表分页，正好落在两张联合索引上，没有联表需求。
 */
@Mapper
public interface OperLogMapper extends BaseMapper<OperLog> {
}
