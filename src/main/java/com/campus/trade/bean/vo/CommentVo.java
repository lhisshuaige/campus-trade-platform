package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "返回给前端的数据，进行多表关联查询用户昵称")
public class CommentVo {
    @Schema(description = "评论id")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "评论人id")
    private Long userId;

    @Schema(description = "评论人昵称")
    private String nickname;

    @Schema(description = "评论内容")
    private String content;


    @Schema(description = "评论时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
