package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Comment;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.DTO.request.comment.CommentAddDTO;
import com.campus.trade.bean.vo.CommentVo;
import com.campus.trade.mapper.CommentMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.CommentService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class CommentServiceImp extends ServiceImpl<CommentMapper, Comment> implements CommentService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private GoodsMapper goodsMapper;

    //发表评论
    @Override
    public void addComment(CommentAddDTO commentAddDTO, Long loginUserId) {
        Comment comment = new Comment();
        BeanUtils.copyProperties(commentAddDTO, comment);
        comment.setUserId(loginUserId);
        save( comment);
    }

    //删除自己的评论
    @Override
    public void deleteComment(Long commentId, Long loginUserId) {
        Comment comment = getById(commentId);
        if (comment==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"评论不存在");
        }
        if (!comment.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"不能删除别人的评论");
        }
        removeById(commentId);
    }

    //根据商品id查询该商品的所有评论（一对多）
    @Override
    public List<CommentVo> getCommentVoList(Long goodsId) {
        //先判断该商品是否存在
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法查看评论");
        }

        LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Comment::getGoodsId, goodsId);
        queryWrapper.orderByDesc(Comment::getCreateTime);
        //这里获取到后端的该商品id下的所有评论，然后进行多表关联查询用户昵称
        List<Comment> commentList = list(queryWrapper);

        //没有评论属于正常情况，返回空列表而不是抛异常
        if (commentList.isEmpty()) {
            return new ArrayList<>();
        }

        List<CommentVo> commentVoList=new ArrayList<>();
        //获取所有评论的userId，批量查询避免N+1
        List<Long> userIdList=commentList.stream().map(Comment::getUserId).toList();
        List<User> userList = userMapper.selectBatchIds(userIdList);
        Map<Long,String> nickNameMap = userList.stream().collect(java.util.stream.Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));

        //这里对后端数据库的数据库遍历 转换成前端数据 进行关联查询设置用户昵称
        for (Comment comment : commentList) {
            CommentVo commentVo = new CommentVo();
            BeanUtils.copyProperties(comment, commentVo);
            //这里进行多表关联查询用户昵称
            String nickname = nickNameMap.get(comment.getUserId());
            commentVo.setNickname(nickname);
            commentVoList.add(commentVo);
        }
        return commentVoList;
    }
}
