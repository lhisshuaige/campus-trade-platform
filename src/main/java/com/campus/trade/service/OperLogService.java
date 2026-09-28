package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.log.OperLogQueryDTO;
import com.campus.trade.bean.entry.OperLog;
import com.campus.trade.bean.vo.OperLogVo;

public interface OperLogService {

    /**
     * 落一条操作日志：入库前把每一列截到 init.sql 定义的长度，避免"URI 太长"这种理由让整条审计记录写不进去。
     * 调用方（LogAspect）负责兜住这里的异常 —— 日志失败绝不能影响业务返回。
     */
    void saveLog(OperLog operLog);

    //管理端按 操作人/结果/时间范围 翻日志
    Page<OperLogVo> pageForAdmin(OperLogQueryDTO queryDTO);
}
