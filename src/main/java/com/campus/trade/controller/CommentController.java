package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.DTO.result.MyResult;
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
    @Operation(summary = "发表评论")
    @Log("发表评论")
    //UGC 入口里被刷得最便宜的一个：一次请求就落一行，而且还会进审核、进商品详情页的缓存失效。
    //10 条/分钟不是人能维持的速度（写完一条总得看一眼别人的回复），而脚本会立刻撞上这一行
    @RateLimit(maxCount = 10, message = "评论过于频繁，请稍后再试")
    public MyResult<Void> addComment(@Valid @RequestBody CommentAddDTO commentAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        commentService.addComment(commentAddDTO, loginUserId);
        return MyResult.success();
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
    @Operation(summary = "分页查询某商品的评论", description = "按评论时间倒序；isOwner 由服务端按当前登录者计算")
    public MyResult<Page<CommentVo>> getCommentVoList(@RequestParam("id") Long goodsId,
                                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                                      HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(commentService.getCommentVoList(goodsId, loginUserId, pageNum, pageSize));
    }
}
