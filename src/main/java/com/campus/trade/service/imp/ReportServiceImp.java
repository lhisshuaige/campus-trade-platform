package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.DTO.request.report.ReportAddDTO;
import com.campus.trade.bean.DTO.request.report.ReportAdminQueryDTO;
import com.campus.trade.bean.entry.Comment;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.Notice;
import com.campus.trade.bean.entry.Report;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.ContentAuditUtils;
import com.campus.trade.bean.vo.ReportVo;
import com.campus.trade.mapper.CommentMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.ReportMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.NoticeService;
import com.campus.trade.service.ReportService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class ReportServiceImp extends ServiceImpl<ReportMapper, Report> implements ReportService {

    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private CommentMapper commentMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ContentAuditUtils contentAuditUtils;
    @Resource
    private NoticeService noticeService;

    //举报列表里被举报内容的摘要长度：够认出来是哪条，又不至于把整篇商品描述搬进治理台
    private static final int SUMMARY_MAX_LENGTH = 60;
    private static final Set<String> TARGET_TYPES = Set.of(Report.TARGET_GOODS, Report.TARGET_COMMENT);

    @Override
    public void addReport(ReportAddDTO reportAddDTO, Long loginUserId) {
        String targetType = reportAddDTO.getTargetType() == null
                ? null : reportAddDTO.getTargetType().trim().toLowerCase();
        if (!TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "举报对象类型只能是 goods 或 comment");
        }
        String reason = reportAddDTO.getReason() == null ? null : reportAddDTO.getReason().trim();
        if (reason == null || reason.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "举报理由不能为空");
        }
        //举报理由本身也是 UGC：不校就会变成"用别人商品详情里出现过的词反过来举报别人"的工具
        contentAuditUtils.assertClean(reason, "report.reason");

        //目标必须真实存在：不预检就会留下一条指向空气的举报，管理员点开才知道什么都没删掉
        //（target_id 没有外键，所以这一步就是唯一的存在性保障）
        if (!targetExists(targetType, reportAddDTO.getTargetId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "被举报的内容不存在或已被删除");
        }
        //同一人对同一目标只留一条待处理举报：重复提交不增加任何信息，只会把待办列表刷成噪音
        //（不做"历史上只能举报一次"，否则管理员驳回后用户就再也没法举报了）
        if (exists(new LambdaQueryWrapper<Report>()
                .eq(Report::getUserId, loginUserId)
                .eq(Report::getTargetType, targetType)
                .eq(Report::getTargetId, reportAddDTO.getTargetId())
                .eq(Report::getStatus, Report.STATUS_PENDING))) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该内容你已提交举报，正在处理中");
        }
        Report report = new Report();
        report.setUserId(loginUserId);
        report.setTargetType(targetType);
        report.setTargetId(reportAddDTO.getTargetId());
        report.setReason(reason);
        report.setStatus(Report.STATUS_PENDING);
        save(report);
        //刻意不通知被举报人：举报必须匿名，否则"提交举报"会立刻变成骚扰与报复的工具
    }

    @Override
    public Page<ReportVo> getReportPageForAdmin(ReportAdminQueryDTO queryDTO) {
        Integer status = queryDTO.getStatus();
        if (status != null && status != Report.STATUS_PENDING
                && status != Report.STATUS_HANDLED && status != Report.STATUS_REJECTED) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "处理状态取值只能是 0待处理/1已处理/2已驳回");
        }
        String targetType = queryDTO.getTargetType();
        if (targetType != null && !targetType.isBlank() && !TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "举报对象类型只能是 goods 或 comment");
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null, Report::getStatus, status);
        wrapper.eq(targetType != null && !targetType.isBlank(), Report::getTargetType, targetType);
        wrapper.eq(queryDTO.getUserId() != null, Report::getUserId, queryDTO.getUserId());
        //待办列表默认就是"最该看的在最上面"：已处理的老记录不该占首页
        wrapper.orderByAsc(Report::getStatus).orderByDesc(Report::getCreateTime);
        Page<Report> reportPage = page(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        Page<ReportVo> voPage = new Page<>(reportPage.getCurrent(), reportPage.getSize(), reportPage.getTotal());
        voPage.setRecords(new ArrayList<>());
        List<Report> rows = reportPage.getRecords();
        if (rows.isEmpty()) {
            return voPage;
        }
        //举报人昵称 + 被举报内容摘要都批量补，避免逐条查（和订单/评论管理端同一套做法）
        Map<Long, String> nicknameMap = userMapper.selectBatchIds(
                        rows.stream().map(Report::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
        Map<Long, String> goodsTitleMap = batchLoad(rows, Report.TARGET_GOODS, Report::getTargetType,
                ids -> goodsMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Goods::getId, g -> g.getTitle() == null ? "" : g.getTitle())));
        Map<Long, String> commentTextMap = batchLoad(rows, Report.TARGET_COMMENT, Report::getTargetType,
                ids -> commentMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Comment::getId, c -> c.getContent() == null ? "" : c.getContent())));
        for (Report report : rows) {
            ReportVo vo = new ReportVo();
            BeanUtils.copyProperties(report, vo);
            vo.setReporterNickname(nicknameMap.getOrDefault(report.getUserId(), ""));
            String summary = Report.TARGET_GOODS.equals(report.getTargetType())
                    ? goodsTitleMap.get(report.getTargetId())
                    : commentTextMap.get(report.getTargetId());
            vo.setTargetSummary(summary == null ? "（内容已删除）" : summarize(summary));
            voPage.getRecords().add(vo);
        }
        return voPage;
    }

    @Override
    public void handleReport(Long reportId, boolean accept, Long operatorId) {
        int target = accept ? Report.STATUS_HANDLED : Report.STATUS_REJECTED;
        // CAS：只有仍待处理才迁移。两个管理员同时点"处理"时，后点的那个人必须看到"已处理"，
        // 而不是把状态覆盖一遍、再给举报人发两条相互矛盾的通知
        LambdaUpdateWrapper<Report> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Report::getId, reportId)
                .eq(Report::getStatus, Report.STATUS_PENDING)
                .set(Report::getStatus, target)
                .set(Report::getHandlerId, operatorId)
                .set(Report::getHandleTime, LocalDateTime.now());
        if (baseMapper.update(null, wrapper) == 0) {
            Report exist = getById(reportId);
            if (exist == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "举报记录不存在");
            }
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该举报已被处理，请刷新后查看");
        }
        Report report = getById(reportId);
        //处理结果必须回到举报人那里，否则"举报了然后什么都没发生"是这个功能最快的死法
        noticeService.send(report.getUserId(), operatorId, Notice.TYPE_SYSTEM,
                accept ? "你的举报已处理" : "你的举报未通过",
                "你举报的" + (Report.TARGET_GOODS.equals(report.getTargetType()) ? "商品" : "评论")
                        + "（id=" + report.getTargetId() + "）" + (accept ? "已按违规内容处置" : "经核实未发现违规"),
                reportId);
    }

    //目标是否存在：多态关联的两张表各查一次主键，命中不了就是不存在
    private boolean targetExists(String targetType, Long targetId) {
        if (targetId == null) {
            return false;
        }
        if (Report.TARGET_GOODS.equals(targetType)) {
            return goodsMapper.selectById(targetId) != null;
        }
        return commentMapper.selectById(targetId) != null;
    }

    //按 targetType 挑出对应的 id 批量查，返回 id -> 摘要文案
    private Map<Long, String> batchLoad(List<Report> rows, String targetType,
                                        Function<Report, String> typeGetter,
                                        Function<List<Long>, Map<Long, String>> loader) {
        List<Long> ids = rows.stream()
                .filter(r -> targetType.equals(typeGetter.apply(r)))
                .map(Report::getTargetId).distinct().toList();
        return ids.isEmpty() ? Map.of() : loader.apply(ids);
    }

    private String summarize(String text) {
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH) + "…";
    }
}
