package com.pab.middleware.venuscommon.filter;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

/**
 * 内网 MyBatis SQL 拦截器（由 com.pab.middleware venuscommon 提供）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译用的桩，实现为空转发。仅在非 local Profile 的
 * {@code TdsqlMybatisPlusConfig} 中被引用；local Profile 的 MyBatis 配置不装配本拦截器。
 * 合入内网项目时删除本桩，改用真实实现。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class MybatisSQLInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }
}
