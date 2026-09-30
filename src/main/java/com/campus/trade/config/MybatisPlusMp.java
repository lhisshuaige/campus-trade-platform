package com.campus.trade.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// mybatis-plus 配置类 配置分页
@Configuration
public class MybatisPlusMp {

    /**
     * 分页单页条数硬上限：500。
     * 它和 DTO 上的 @Max 不是同一层（两处都写、但管的不是同一件事）：
     * DTO 校验给的是「人话提示」，这一条是给分页插件本身的兜底 —— 脚本传的 size 再大，
     * 插件也会把它夹紧到 500，于是一条 SQL 永远不可能捞出一整张表。
     * 500 这个值留得很宽：业务侧真正的上限是各 Service 里的 Math.min(pageSize, 50)，
     * 收紧到那个数字只会让「以后要做导出」时先回来改这里，所以按「防脚本」而不是「按业务口径」定
     */
    private static final long PAGE_MAX_LIMIT = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(){
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁插件必须在【分页插件之前】add：这两个 InnerInterceptor 共用同一条 SQL 的改写时机，
        // MP 官方要求的顺序就是「先自己业务的拦截，分页放最后」——分页排最后是因为它要拿到最终 SQL 再拼 LIMIT
        // 只标了 @Version 的实体参与比对（本项目只有 Goods.version），其余实体的 update 完全不受影响，
        // 所以「加了这个插件」不等于「所有更新都多一个 WHERE 条件」
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页插件
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInnerInterceptor.setMaxLimit(PAGE_MAX_LIMIT);
        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        return interceptor;
    }
}
