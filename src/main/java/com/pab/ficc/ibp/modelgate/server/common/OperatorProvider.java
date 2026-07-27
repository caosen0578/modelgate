package com.pab.ficc.ibp.modelgate.server.common;

/**
 * 操作人上下文提供者（预留接口）。
 *
 * <p>用于自动填充基础字段 {@code creater}/{@code updater}/{@code creater_um}/{@code updater_um}。
 * 当前为占位实现（返回 null）；后续接入行内用户上下文（如从网关透传的登录态 / 安全上下文）后，
 * 提供一个新的实现 Bean 即可，无需改动各 Service。
 */
public interface OperatorProvider {

    /** 当前操作人（工号/账号），写入 creater / updater */
    String currentOperator();

    /** 当前操作人 UM 账号，写入 creater_um / updater_um */
    String currentOperatorUm();
}
