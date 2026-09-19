package com.campus.trade.controller;

import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.DTO.result.MyResult;
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
    public MyResult<Void> addComment(@Valid @RequestBody CommentAddDTO commentAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        commentService.addComment(commentAddDTO, loginUserId);
        return MyResult.success();
    }

    //删除自己的评论
    @DeleteMapping("/delete")
    @Operation(summary = "删除自己的评论")
    public MyResult<Void> deleteComment(@RequestParam("id") Long commentId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        commentService.deleteComment(commentId, loginUserId);
        return MyResult.success();
    }

    //根据商品id查询该商品下的所有评论（一对多）
    @GetMapping("/list")
    @Operation(summary = "根据商品id查询该商品下的所有评论")
    public MyResult<List<CommentVo>> getCommentVoList(@RequestParam("id") Long goodsId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        List<CommentVo> commentVoList = commentService.getCommentVoList(goodsId, loginUserId);
        return MyResult.success(commentVoList);
    }
}
