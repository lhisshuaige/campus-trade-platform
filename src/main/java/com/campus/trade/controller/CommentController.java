package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.dto.request.comment.CommentAddDTO;
import com.campus.trade.bean.dto.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RateLimit;
import com.campus.trade.bean.vo.CommentVo;
import com.campus.trade.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/comment")
@Tag(name = "评论接口")
public class CommentController {

    @Resource
    private CommentService commentService;
    //发表评论
    @PostMapping("/add")
    @Operation(summary = "发表评论", description = "返回新评论行的 id，前端据此把新行就地插进列表（不整表刷新）")
    @Log("发表评论")
    //UGC 入口里被刷得最便宜的一个：一次请求就落一行，而且还会进审核、进商品详情页的缓存失效。
    //10 条/分钟不是人能维持的速度（写完一条总得看一眼别人的回复），而脚本会立刻撞上这一行
    @RateLimit(maxCount = 10, message = "评论过于频繁，请稍后再试")
    public MyResult<Long> addComment(@Valid @RequestBody CommentAddDTO commentAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(commentService.addComment(commentAddDTO, loginUserId));
    }

    //删除自己的评论（管理员走 /admin/comment/delete，共用同一条判定）
    @DeleteMapping("/delete")
    @Operation(summary = "删除自己的评论")
    @Log("删除自己的评论")
    public MyResult<Void> deleteComment(@RequestParam("id") Long commentId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        String loginUserRole = (String) request.getAttribute("loginUserRole");
        commentService.deleteComment(commentId, loginUserId, loginUserRole);
        return MyResult.success();
    }

    //分页查询该商品的评论（原来一次性返回全部评论）
    @GetMapping("/list")
    @Operation(summary = "分页查询某商品的评论（楼中楼）",
            description = "顶层按评论时间倒序分页，每楼带楼内排布首条的回复预览与回复总数；isOwner 由服务端按当前登录者计算")
    public MyResult<Page<CommentVo>> getCommentVoList(@RequestParam("id") Long goodsId,
                                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                                      HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(commentService.getCommentVoList(goodsId, loginUserId, pageNum, pageSize));
    }

    //展开一栋楼的全部回复（"展开其他 N 条回复"按钮）。
    //公开可读、isOwner 读侧现算 —— 与 /comment/list 完全同一种权限形态；
    //新增路径必须同步进 WebMvcConfig 的两个列表（optionalAuth 注入 + Jwt 排除），漏一个匿名游客 401
    @GetMapping("/replies")
    @Operation(summary = "分页查询某条顶层评论的回复", description = "楼内排布：回楼主主评论的一组置顶，接楼内话的靠后，组内时间倒序（与列表预览同一前缀序）；isOwner 由服务端按当前登录者计算")
    public MyResult<Page<CommentVo>> getReplies(@RequestParam("id") Long topCommentId,
                                                @RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "10") Integer pageSize,
                                                HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(commentService.getReplies(topCommentId, loginUserId, pageNum, pageSize));
    }
}
