package com.pab.ficc.ibp.modelgate.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.pab.ficc.ibp.mybatis.plugins.QueryInterceptor;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.plugin.Interceptor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;


/**
 * MyBatis-Plus 装配：SqlSessionFactory + 分页拦截器 + Mapper 扫描。
 *
 * <p>不按 Profile 区分（数据源与 Redis 已统一为单一直连 Bean，见 {@link ServiceConfiguration}），
 * 全部环境走同一套装配。此前曾因分页拦截器漏装导致 {@code selectPage} 退化成不带 LIMIT
 * 的全表查询、{@code total} 恒为 0，故这里显式注册，勿再遗漏。
 */
@Slf4j
@Configuration
@EnableTransactionManagement
@MapperScan(
        basePackages = "com.pab.ficc.ibp.modelgate.server.mapper",
        sqlSessionFactoryRef = "tdsqlSessionFactory"
)
public class TdsqlMybatisPlusConfig {

    /**
     * 全局 JDBC statement 超时（秒）。见下方 setDefaultStatementTimeout 处的说明。
     * 与 {@code RequestPayloadMapper.QUERY_TIMEOUT_SECONDS} 保持一致；
     * 那里的 @Options 是显式重申，即便本项被改动，报文查询也仍有自己的上界。
     */
    private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30;

    /**
     * SQL 日志实现，读 {@code mybatis-plus.configuration.log-impl}。
     *
     * <p><b>必须从配置读、不能写死</b>：本类定义了自定义 SqlSessionFactory，
     * MybatisPlusAutoConfiguration 会因此退避，{@code mybatis-plus.*} 的 yml 配置<b>全部失效</b>。
     * 早先这里硬编码 NoLoggingImpl，而 local 能看到 SQL 是因为当时本类标了 @Profile("!local")、
     * 走的是自动装配；去掉该注解后 local 的 SQL 日志随即消失。
     * 现改为承接配置项 mybatis-plus.configuration.log-impl（application.yml，环境变量 MYBATIS_LOG_IMPL 驱动）。
     */
    @Value("${mybatis-plus.configuration.log-impl:org.apache.ibatis.logging.nologging.NoLoggingImpl}")
    private String logImpl;

    @Bean
    public QueryInterceptor createQueryInterceptor() {
        return new QueryInterceptor();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器，指定 MySQL 方言
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 与 tdsqlSessionFactory 共用同一个数据源的本地事务管理器。
     *
     * <p>{@code ModelGateApplication} 因手工装配 Druid 排除了
     * {@code DataSourceTransactionManagerAutoConfiguration}，不能只写 {@code @Transactional}
     * 就假定事务会生效；没有本 Bean 时模型+凭证的组合写入在运行期没有事务管理器，
     * 甚至可能在第一次调用事务方法时直接报错。显式限定 tdsqlDataSource 也避免内网环境
     * 将来出现多个 DataSource 后误选事务资源。
     */
    @Bean(name = "tdsqlTransactionManager")
    public PlatformTransactionManager tdsqlTransactionManager(
            @Qualifier("tdsqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "tdsqlSessionFactory")
    public MybatisSqlSessionFactoryBean tdsqlSessionFactory(
            @Qualifier("tdsqlDataSource") DataSource dataSource,
            QueryInterceptor queryInterceptor,
            MybatisPlusInterceptor mybatisPlusInterceptor,

            MetaObjectHandler metaObjectHandler) throws Exception {

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        // ⚠️ 自定义 SqlSessionFactory 必须自己挂 MetaObjectHandler：
        // MybatisPlusAutoConfiguration 会把容器里的 MetaObjectHandler 自动塞进 GlobalConfig，
        // 但本类一旦定义了 SqlSessionFactory，该自动装配整体退避，**自动填充随之失效**。
        // 后果极具迷惑性：is_del 被显式写入 NULL（覆盖建表的 DEFAULT '0'），
        // 而 @TableLogic(value="0") 让所有查询都带 WHERE is_del='0'——'0' 匹配不到 NULL，
        // 于是新增的模型/接入系统/凭证「插入成功却查不出来」，看起来像没保存。
        // create_time / update_time / creater / updater 同样全为空。
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        globalConfig.setBanner(false);
        factory.setGlobalConfig(globalConfig);

        MybatisConfiguration configuration = new MybatisConfiguration();
        // 下划线转驼峰：DB 字段 prompt_tokens → Java 字段 promptTokens
        configuration.setMapUnderscoreToCamelCase(true);
        // 生产默认 NoLoggingImpl（高频上报下避免日志噪声）；本地设 MYBATIS_LOG_IMPL 环境变量可切换
        configuration.setLogImpl(resolveLogImpl());
        // 全局 JDBC statement 超时（秒）。此前<b>三层都没有超时兜底</b>：
        //   1. JDBC URL 没有 socketTimeout / maxQueryTimeout；
        //   2. 这里没有 defaultStatementTimeout；
        //   3. Controller 侧没有 .timeout()。
        // 结果是一条跑飞的查询没有任何东西能把它掐掉——Reactor 的取消信号对已进入阻塞
        // JDBC 调用的线程无效，Druid 的 removeAbandoned 又按行内标准关成了 false。
        // 用户等不及连点 5 次「查询」，就是 5 份完全相同的慢查询同时在跑，代价 5 倍且无上界。
        //
        // 设成全局而非只给个别 Mapper 方法：本项目所有正常查询都在百毫秒级
        // （网关热路径的凭证/授权点查更是亚毫秒），30 秒只会掐掉病态查询，不会误伤。
        // 它同时给审计/报文的批量 INSERT 与清理任务的分批 DELETE 加了上界，这也是想要的。
        configuration.setDefaultStatementTimeout(DEFAULT_STATEMENT_TIMEOUT_SECONDS);
        factory.setConfiguration(configuration);

        factory.setPlugins(new Interceptor[]{
                queryInterceptor,
                mybatisPlusInterceptor

        });

        // 扫描 classpath 下所有 mapper XML（自定义 SQL 写在 resources/mapper/**/*.xml）
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/**/*.xml")
        );

        return factory;
    }

    /** 配置值非法时退回 NoLoggingImpl 并告警，不因一个日志配置写错就让应用起不来。 */
    @SuppressWarnings("unchecked")
    private Class<? extends Log> resolveLogImpl() {
        try {
            return (Class<? extends Log>) Class.forName(logImpl);
        } catch (Exception e) {
            log.warn("[MyBatis] log-impl 配置值无法加载，回退为 NoLoggingImpl: {}", logImpl);
            return org.apache.ibatis.logging.nologging.NoLoggingImpl.class;
        }
    }
}
