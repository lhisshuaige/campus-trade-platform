package com.campus.trade.service;

import com.campus.trade.bean.vo.request.comment.CommentAddVo;
import com.campus.trade.bean.vo.request.comment.CommentVo;

import java.util.List;

public interface CommentService {
    void addComment(CommentAddVo commentAddVo, Long loginUserId);
    void deleteComment(Long commentId, Long loginUserId);
    List<CommentVo> getCommentVoList(Long goodsId);
}
