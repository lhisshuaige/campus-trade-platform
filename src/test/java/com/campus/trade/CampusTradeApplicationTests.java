package com.campus.trade;

import com.campus.trade.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootTest
class CampusTradeApplicationTests {

    //测试mp是否可用
    @Autowired
    private UserMapper userMapper;

    @Test
    void contextLoads() {

        BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();

        String password="123456";
        String encodePwd = bCryptPasswordEncoder.encode(password);
        System.out.println("加密后的密码是："+encodePwd);
        boolean matches = bCryptPasswordEncoder.matches(password, encodePwd);

        System.out.println("密码匹配结果是："+matches);

    }



}
