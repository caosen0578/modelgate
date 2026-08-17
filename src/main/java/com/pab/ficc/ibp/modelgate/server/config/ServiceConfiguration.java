package com.pab.ficc.ibp.modelgate.server.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 数据源装配：HikariCP 直连 MySQL，读 {@code datasource.local.*}（application.yml）。
 *
 * <p>不再按 Profile 区分（决策：数据源与 Redis 统一直连，不再依赖 Venus/内网中间件）。
 * 所有环境走同一个 Bean，差异只在 {@code datasource.local.*} 这三个配置值——
 * 部署到不同环境时直接改 application.yml 里的这三行即可，不依赖 Spring Profile 机制。
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
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        ds.setMaximumPoolSize(10);
        ds.setPoolName("tdsql");
        return ds;
    }
}
