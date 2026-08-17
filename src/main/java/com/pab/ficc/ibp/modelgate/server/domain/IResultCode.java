package com.pab.ficc.ibp.modelgate.server.domain;

/**
 * 统一返回码接口，供各模块实现。
 *
 * <p><b>本地副本说明</b>：与行内 SDK 的 {@code com.pab.ficc.ibp.tool.exception.IResultCode} 同构。
 * 本项目 pom 中 {@code ibp-web} 依赖处于注释状态，故 {@link Result} 及其依赖的返回码接口
 * 以本地副本形式存在。合入内网时启用 {@code ibp-web} 依赖并删除本包下的
 * {@code Result / IResultCode / BaseResultCode} 三个副本，import 改回行内 SDK 包路径。
 */
public interface IResultCode {

    String getResultCode();

    String getResultMessage();
}
