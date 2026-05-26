package com.pab.ficc.ibp.modelgate.server.config;

import com.pab.middleware.venus.datasource.VenusDataSourceBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
public class ServiceConfiguration {

    @Value("${datasource.namespace:tdsql_datasource_config}")
    private String mysqlNamespace;

    @Bean(name = "tdsqlDataSource")
    public DataSource tdsqlDataSource() {
        return new VenusDataSourceBean(mysqlNamespace);
    }
}
