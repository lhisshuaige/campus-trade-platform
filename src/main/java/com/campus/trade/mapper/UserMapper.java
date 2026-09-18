package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.User;
import org.apache.ibatis.annotations.Mapper;

// 用户Mapper 用mybitsplus实现，简化代码实现增删改查
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
