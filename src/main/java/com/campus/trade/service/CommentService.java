package com.campus.trade.service;

import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.vo.CommentVo;

import java.util.List;

public interface CommentService {
    void addComment(CommentAddDTO commentAddDTO, Long loginUserId);
    void deleteComment(Long commentId, Long loginUserId);
    List<CommentVo> getCommentVoList(Long goodsId);
}
