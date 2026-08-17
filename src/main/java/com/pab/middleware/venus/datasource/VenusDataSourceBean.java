package com.pab.middleware.venus.datasource;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 内网 Venus 数据源（由 com.pab.middleware venus 提供，配置经 Apollo 命名空间下发）。
 *
 * <p><b>本地兼容说明：</b>此文件为本地编译用的桩。仅在非 local Profile 的
 * {@code ServiceConfiguration} 中被 {@code new}；local Profile 用 HikariDataSource 从
 * application.yml 的 datasource.local.* 读取连接信息，不会实例化本类。合入内网项目时删除本桩。
 */
public class VenusDataSourceBean implements DataSource {

    public VenusDataSourceBean(String namespace) {
        throw new UnsupportedOperationException(
                "VenusDataSourceBean 为本地编译桩，非 local Profile 需由内网 venus 提供真实实现");
    }

    @Override public Connection getConnection() throws SQLException { throw new SQLException("stub"); }
    @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLException("stub"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("stub"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
}
