package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.middleware.venus.datasource.VenusDataSourceBean;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 数据源装配。
 *
 * <ul>
 *   <li>非 local：Venus 数据源（连接信息经 Apollo {@code tdsql_datasource_config} 命名空间下发）。</li>
 *   <li>local：本地 MySQL 直连，读 {@code datasource.local.*}（见 application-local.yml），HikariCP 连接池。</li>
 * </ul>
 * 两个 Bean 同名 {@code tdsqlDataSource}，按 Profile 二选一。
 */
@Configuration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
public class ServiceConfiguration {

    @Value("${datasource.namespace:tdsql_datasource_config}")
    private String mysqlNamespace;

    /** 非 local：Venus 数据源。 */
    @Bean(name = "tdsqlDataSource")
    @Profile("!local")
    public DataSource tdsqlDataSource() {
        return new VenusDataSourceBean(mysqlNamespace);
    }

    /** local：本地 MySQL 直连（datasource.local.*）。 */
    @Bean(name = "tdsqlDataSource")
    @Profile("local")
    public DataSource localDataSource(
            @Value("${datasource.local.url}") String url,
            @Value("${datasource.local.username}") String username,
            @Value("${datasource.local.password}") String password,
            @Value("${datasource.local.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        ds.setMaximumPoolSize(10);
        ds.setPoolName("local-tdsql");
        return ds;
    }
}
