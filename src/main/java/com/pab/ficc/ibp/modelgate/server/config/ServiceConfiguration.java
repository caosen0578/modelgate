package com.pab.ficc.ibp.modelgate.server.config;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 数据源装配：Druid 直连 MySQL（内网框架统一用它，非 HikariCP），读
 * {@code datasource.local.*}（application.yml）。
 *
 * <p>不再按 Profile 区分（决策：数据源与 Redis 统一直连，不再依赖 Venus/内网中间件）。
 * 所有环境走同一个 Bean，差异只在 {@code datasource.local.*} 这三个配置值——
 * 部署到不同环境时直接改 application.yml 里的这三行即可，不依赖 Spring Profile 机制。
 *
 * <p>池参数（{@code datasource.local.pool.*}）externalize 到 application.yml（2026-08-18 压测优化后，
 * 便于生产环境直接改配置调整、不用重新编译）。{@code @Value} 里的默认值与 application.yml 当前写的
 * 生产评估值一致——yml 里删掉某一项不会失效，只是退回这里写的同一个默认值，两处应保持同步。
 */
@Slf4j
@Configuration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
public class ServiceConfiguration {

    @Bean(name = "tdsqlDataSource")
    public DataSource tdsqlDataSource(
            @Value("${datasource.local.url}") String url,
            @Value("${datasource.local.username}") String username,
            @Value("${datasource.local.password}") String password,
            @Value("${datasource.local.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver,
            @Value("${datasource.local.pool.init-size:5}") int initSize,
            @Value("${datasource.local.pool.min-idle:10}") int minIdle,
            @Value("${datasource.local.pool.max-active:100}") int maxActive,
            @Value("${datasource.local.pool.max-wait:3000}") int maxWait,
            @Value("${datasource.local.pool.time-between-eviction-runs-millis:30000}") long timeBetweenEvictionRunsMillis,
            @Value("${datasource.local.pool.min-evictable-idle-time-millis:1800000}") long minEvictableIdleTimeMillis,
            @Value("${datasource.local.pool.remove-abandoned:false}") boolean removeAbandoned,
            @Value("${datasource.local.pool.remove-abandoned-timeout-millis:300000}") long removeAbandonedTimeoutMillis) {
        log.info("[ServiceConfiguration] 数据源: url={}, maxActive={}", url, maxActive);
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        ds.setName("tdsql");

        ds.setInitialSize(initSize);
        ds.setMinIdle(minIdle);
        ds.setMaxActive(maxActive);
        // 拿不到连接时最多等 maxWait 就抛异常，不无限阻塞请求线程——比默认行为更早暴露池耗尽问题
        ds.setMaxWait(maxWait);

        // 空闲连接健康检查：避免 MySQL 侧空闲超时/网络中断后拿到已失效连接。
        // validationQuery/testOnBorrow/testOnReturn 不 externalize——固定用最佳实践值
        // （testWhileIdle 校验闲置连接、不占请求路径开销），没有按环境调整的必要。
        ds.setValidationQuery("SELECT 1");
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        ds.setTimeBetweenEvictionRunsMillis(timeBetweenEvictionRunsMillis);
        ds.setMinEvictableIdleTimeMillis(minEvictableIdleTimeMillis);

        // 连接泄漏兜底：业务代码没关连接时超时强制回收并打 ERROR 日志，防止池被占满后无法自愈。
        // 默认关闭（remove-abandoned=false，2026-08-18 对齐行内标准默认值）——Druid 官方本就不建议
        // 生产环境开启：真在跑的慢查询会被误判成"泄漏"强行掐断连接。需要时可在 yml 里打开，
        // 用 setRemoveAbandonedTimeoutMillis（毫秒版本，行内标准用的是这个而非秒版本的
        // setRemoveAbandonedTimeout，两者是 Druid 里两个独立方法，别混用）。
        ds.setRemoveAbandoned(removeAbandoned);
        ds.setRemoveAbandonedTimeoutMillis(removeAbandonedTimeoutMillis);
        ds.setLogAbandoned(true);

        try {
            ds.init();
        } catch (SQLException e) {
            throw new IllegalStateException("[ServiceConfiguration] Druid 数据源初始化失败", e);
        }
        return ds;
    }
}
