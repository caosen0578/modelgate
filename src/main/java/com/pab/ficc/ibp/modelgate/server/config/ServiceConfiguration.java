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
 * <p>池大小 maxActive=100 是压测优化后按生产评估值配置的（原 HikariCP maxPoolSize=10 硬编码，
 * 压测证实在并发≥10 时连接池打满导致 QPS 断崖式下跌，见 11-变更记录.md 本轮章节）。
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
            @Value("${datasource.local.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver) {
        log.info("[ServiceConfiguration] 数据源: url={}", url);
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        ds.setName("tdsql");

        // 池大小：生产评估值，maxActive=100（2026-08-18 压测优化，原 HikariCP 硬编码 10 已证实偏小）
        ds.setInitialSize(5);
        ds.setMinIdle(10);
        ds.setMaxActive(100);
        // 拿不到连接时最多等 3s 就抛异常，不无限阻塞请求线程——比默认行为更早暴露池耗尽问题
        ds.setMaxWait(3000);

        // 空闲连接健康检查：避免 MySQL 侧空闲超时/网络中断后拿到已失效连接
        ds.setValidationQuery("SELECT 1");
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        ds.setTimeBetweenEvictionRunsMillis(60_000);
        ds.setMinEvictableIdleTimeMillis(300_000);

        // 连接泄漏兜底：业务代码没关连接时 180s 后强制回收并打 ERROR 日志，防止池被占满后无法自愈
        ds.setRemoveAbandoned(true);
        ds.setRemoveAbandonedTimeout(180);
        ds.setLogAbandoned(true);

        try {
            ds.init();
        } catch (SQLException e) {
            throw new IllegalStateException("[ServiceConfiguration] Druid 数据源初始化失败", e);
        }
        return ds;
    }
}
