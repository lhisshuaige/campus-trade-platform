package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.DTO.request.comment.CommentAdminQueryDTO;
import com.campus.trade.bean.vo.CommentAdminVo;
import com.campus.trade.bean.vo.CommentVo;

public interface CommentService {
    void addComment(CommentAddDTO commentAddDTO, Long loginUserId);
    //删除评论：本人删自己的、管理员删违规内容（内容治理）。
    //判定只写在这一处 —— 不给 admin 另开一条删除路径，否则两套规则迟早漂移
    void deleteComment(Long commentId, Long loginUserId, String loginUserRole);
    //某商品的评论（分页；一次性返回全部是原来的问题：长商品列表撑爆响应）
    Page<CommentVo> getCommentVoList(Long goodsId, Long loginUserId, Integer pageNum, Integer pageSize);
    //管理端评论治理列表：跨商品，需要商品标题与评论人 id
    Page<CommentAdminVo> getCommentPageForAdmin(CommentAdminQueryDTO queryDTO);
}
