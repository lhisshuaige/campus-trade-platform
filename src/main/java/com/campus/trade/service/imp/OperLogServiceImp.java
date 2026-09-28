package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.log.OperLogQueryDTO;
import com.campus.trade.bean.entry.OperLog;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.vo.OperLogVo;
import com.campus.trade.mapper.OperLogMapper;
import com.campus.trade.service.OperLogService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志服务。
 * 类上刻意不加 @Transactional：写日志的时机在 Controller 层的切面里，那时业务事务早已提交或回滚完毕，
 * 这里再套一层事务只会让"审计记录"和"业务生死"绑在一起（业务回滚把日志一起抹掉，是最不能接受的事）。
 */
@Service
public class OperLogServiceImp implements OperLogService {

    @Resource
    private OperLogMapper operLogMapper;

    @Override
    public void saveLog(OperLog operLog) {
        //每一列都截到 init.sql 定义的长度为止：审计日志宁可少几个字，也不能整条插不进来
        operLog.setAction(cut(operLog.getAction(), OperLog.ACTION_MAX_LENGTH));
        operLog.setUsername(cut(operLog.getUsername(), OperLog.USERNAME_MAX_LENGTH));
        operLog.setRequestUri(cut(operLog.getRequestUri(), OperLog.REQUEST_URI_MAX_LENGTH));
        operLog.setHttpMethod(cut(operLog.getHttpMethod(), OperLog.HTTP_METHOD_MAX_LENGTH));
        operLog.setMethod(cut(operLog.getMethod(), OperLog.METHOD_MAX_LENGTH));
        operLog.setParams(cut(operLog.getParams(), OperLog.PARAMS_MAX_LENGTH));
        operLog.setIp(cut(operLog.getIp(), OperLog.IP_MAX_LENGTH));
        operLog.setErrorMsg(cut(operLog.getErrorMsg(), OperLog.ERROR_MSG_MAX_LENGTH));
        if (operLog.getStatus() == null) {
            operLog.setStatus(OperLog.STATUS_SUCCESS);
        }
        //创建时间显式给值，不靠 MySQL 默认值：应用与库的时钟可能不一致，日志的先后顺序必须以业务侧为准
        if (operLog.getCreateTime() == null) {
            operLog.setCreateTime(LocalDateTime.now());
        }
        operLogMapper.insert(operLog);
    }

    @Override
    public Page<OperLogVo> pageForAdmin(OperLogQueryDTO queryDTO) {
        Integer status = queryDTO.getStatus();
        if (status != null && status != OperLog.STATUS_SUCCESS && status != OperLog.STATUS_FAIL) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "结果取值只能是 0-失败 / 1-成功");
        }
        LocalDateTime startTime = queryDTO.getStartTime();
        LocalDateTime endTime = queryDTO.getEndTime();
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "开始时间不能晚于结束时间");
        }
        LambdaQueryWrapper<OperLog> wrapper = new LambdaQueryWrapper<>();
        // 按操作人 + 时间范围、按结果 + 时间范围 各命中一张联合索引（idx_oper_log_user_time / idx_oper_log_status_time）
        wrapper.eq(queryDTO.getUserId() != null, OperLog::getUserId, queryDTO.getUserId());
        wrapper.eq(status != null, OperLog::getStatus, status);
        wrapper.like(queryDTO.getAction() != null && !queryDTO.getAction().isBlank(),
                OperLog::getAction, queryDTO.getAction());
        wrapper.ge(startTime != null, OperLog::getCreateTime, startTime);
        wrapper.le(endTime != null, OperLog::getCreateTime, endTime);
        //create_time 精度到秒，同一秒内的多条要靠 id 定序，否则翻页会出现重复行与漏行
        wrapper.orderByDesc(OperLog::getCreateTime).orderByDesc(OperLog::getId);

        Page<OperLog> logPage = operLogMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Page<OperLogVo> voPage = new Page<>(logPage.getCurrent(), logPage.getSize(), logPage.getTotal());
        List<OperLogVo> voList = new ArrayList<>(logPage.getRecords().size());
        for (OperLog operLog : logPage.getRecords()) {
            OperLogVo vo = new OperLogVo();
            BeanUtils.copyProperties(operLog, vo);
            voList.add(vo);
        }
        voPage.setRecords(voList);
        return voPage;
    }

    private String cut(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        //截断要留痕迹，否则管理员会以为"入参本来就这么短"
        return value.substring(0, maxLength - 3) + "...";
    }
}
