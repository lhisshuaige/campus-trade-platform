package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.Log;
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
