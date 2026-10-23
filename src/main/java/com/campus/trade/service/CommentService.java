package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.dto.request.comment.CommentAddDTO;
import com.campus.trade.bean.dto.request.comment.CommentAdminQueryDTO;
import com.campus.trade.bean.vo.CommentAdminVo;
import com.campus.trade.bean.vo.CommentVo;

public interface CommentService {
    /** 发表评论/楼中楼回复，返回新行的 id ——
     *  前端拿它把新行直接插进当前列表（回复谁就插谁后面），
     *  而不是整表重拉把用户展开过的楼层全冲掉 */
    Long addComment(CommentAddDTO commentAddDTO, Long loginUserId);
    //删除评论：本人删自己的、管理员删违规内容（内容治理）。
    //判定只写在这一处 —— 不给 admin 另开一条删除路径，否则两套规则迟早漂移
    void deleteComment(Long commentId, Long loginUserId, String loginUserRole);
    //某商品的评论（分页；一次性返回全部是原来的问题：长商品列表撑爆响应）
    Page<CommentVo> getCommentVoList(Long goodsId, Long loginUserId, Integer pageNum, Integer pageSize);
    //楼中楼展开：某条【顶层评论】下的全部回复（回楼主主评论的置顶一组、接话靠后，组内最新在前）。
    //与列表共用同一个缓存 key（comment:list:vN:{goodsId}）做内存切片 —— 不新增分页维度 key 的纪律不变
    Page<CommentVo> getReplies(Long topCommentId, Long loginUserId, Integer pageNum, Integer pageSize);
    //管理端评论治理列表：跨商品，需要商品标题与评论人 id
    Page<CommentAdminVo> getCommentPageForAdmin(CommentAdminQueryDTO queryDTO);
}
