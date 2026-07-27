package com.pab.ficc.cus.mybatis.plugins;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * 内网 MyBatis 查询拦截器（由 com.pab.ficc.cus 提供）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译用的桩，实现为空转发。仅在非 local Profile 的
 * {@code TdsqlMybatisPlusConfig} 中被引用；local Profile 的 MyBatis 配置不装配本拦截器。
 * 合入内网项目时删除本桩，改用真实实现。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class,
                        org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class})
})
public class QueryInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }
}
