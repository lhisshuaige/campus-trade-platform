package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.Report;
import org.apache.ibatis.annotations.Mapper;

/**
 * 举报 Mapper。只用 BaseMapper：
 * 列表要显示"举报人昵称 + 被举报内容摘要"，但那两类数据分属两张表且按 targetType 二选一，
 * 写成一条联表 SQL 反而要为两种类型各拼一次 —— 所以照评论管理端的既有做法，批量查了在内存里拼。
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
