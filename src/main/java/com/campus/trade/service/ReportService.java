package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.report.ReportAddDTO;
import com.campus.trade.bean.DTO.request.report.ReportAdminQueryDTO;
import com.campus.trade.bean.vo.ReportVo;

public interface ReportService {

    //提交举报：只校"目标存在 + 不重复提交待处理举报"，不校"举报得对不对"（那是管理员的判断）
    void addReport(ReportAddDTO reportAddDTO, Long loginUserId);

    //管理端举报待办列表
    Page<ReportVo> getReportPageForAdmin(ReportAdminQueryDTO queryDTO);

    /**
     * 处置举报。accept=true 表示认定违规（已处理），false 表示不成立（已驳回）。
     * 只做状态迁移与通知，不代替管理员去删内容 —— 删除仍然走 /admin/comment/delete、
     * /admin/goods/status 那两个既有入口，"处置动作"不能有两份实现。
     */
    void handleReport(Long reportId, boolean accept, Long operatorId);
}
