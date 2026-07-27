package com.pab.ficc.ibp.modelgate.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.pab.ficc.cus.mybatis.plugins.QueryInterceptor;
import com.pab.middleware.venuscommon.filter.MybatisSQLInterceptor;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.plugin.Interceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 内网 MyBatis-Plus 装配（Venus 数据源 + 内网 SQL 拦截器）。
 * local Profile 由 LocalInfraConfig 提供等价装配（去内网拦截器）。
 */
@Configuration
@Profile("!local")
@MapperScan(
        basePackages = "com.pab.ficc.ibp.modelgate.server.mapper",
        sqlSessionFactoryRef = "tdsqlSessionFactory"
)
public class TdsqlMybatisPlusConfig {

    @Bean
    public QueryInterceptor createQueryInterceptor() {
        return new QueryInterceptor();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MybatisSQLInterceptor createMybatisSQLInterceptor() {
        return new MybatisSQLInterceptor();
    }

    @Bean(name = "tdsqlSessionFactory")
    public MybatisSqlSessionFactoryBean tdsqlSessionFactory(
            @Qualifier("tdsqlDataSource") DataSource dataSource,
            QueryInterceptor queryInterceptor,
            MybatisPlusInterceptor mybatisPlusInterceptor,
            MybatisSQLInterceptor mybatisSQLInterceptor,
            MetaObjectHandler metaObjectHandler) throws Exception {

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(NoLoggingImpl.class);
        factory.setConfiguration(configuration);

        // 注册基础字段自动填充（自定义 SqlSessionFactory 不会自动拾取 @Bean MetaObjectHandler）
        // 逻辑删除由实体字段上的 @TableLogic 注解驱动，此处用 defaults() 保证 GlobalConfig 基线完整
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factory.setGlobalConfig(globalConfig);

        factory.setPlugins(new Interceptor[]{
                queryInterceptor,
                mybatisPlusInterceptor,
                mybatisSQLInterceptor
        });

        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/**/*.xml")
        );

        return factory;
    }
}
