package com.campus.trade.controller;

import com.campus.trade.bean.DTO.request.report.ReportAddDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报（用户侧）。只有"提交"这一个入口，没有"查看我的举报列表"：
 * 举报人想知道结果不该靠轮询自己的举报，处理结果由管理端处置时通过站内信送回（/notice/myPage）。
 * 管理员的待办列表与处置在 /admin/report/page、/admin/report/handle。
 */
@RestController
@RequestMapping("/report")
@Tag(name = "举报接口", description = "商品/评论违规内容举报")
public class ReportController {

    @Resource
    private ReportService reportService;

    @PostMapping("/add")
    @Operation(summary = "提交举报", description = "同一人对同一内容重复提交且尚未处理时会被拒绝；处理结果通过站内信通知")
    //举报要留痕：除了内容本身，"谁在反复举报同一人"也是一种需要被看见的行为
    @Log("提交举报")
    public MyResult<Void> add(@Valid @RequestBody ReportAddDTO reportAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        reportService.addReport(reportAddDTO, loginUserId);
        return MyResult.success();
    }
}
