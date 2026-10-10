package com.pab.ficc.ibp.modelgate.server.domain;


import java.io.Serializable;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.MDC;

/**
 * 统一返回数据（管理资源接口的响应结构）。
 *
 * <p><b>本地副本说明</b>：与行内 SDK 的 {@code com.pab.ficc.ibp.web.result.Result} 同构
 * （方法签名已逐一比对一致）。本项目 pom 中 {@code ibp-web} 依赖处于注释状态，
 * 故以本地副本形式存在。合入内网时启用 {@code ibp-web} 依赖、删除本包下的
 * {@code Result / IResultCode / BaseResultCode} 三个副本，并把 9 处 import
 * 改为 {@code com.pab.ficc.ibp.web.result.Result}。
 *
 * <p><b>与 {@code common.Result} 的区别（两者有意并存，勿合并）</b>：
 * 本类是管理面结构（{@code resultCode/resultMessage}）；
 * {@code common.Result} 是网关 Filter 对外业务流量 {@code /v1/**} 的旧结构
 * （{@code code/message}），属对外稳定契约。
 *
 * <p><b>已知限制</b>：{@code requestId}/{@code logTraceId} 取自 MDC 的
 * {@code traceId}/{@code rid}。管理面请求不匹配网关路由、不经过 TraceFilter，
 * MDC 中无这两个键，故管理接口响应里这两个字段恒为 {@code null}。
 */
public class Result<T> implements Serializable {
    /** 状态码，00000000 表示成功，其他 code 码表示失败 */
    private String resultCode;
    /** 业务数据 */
    private T data;
    /** 处理失败后的失败消息 */
    private String resultMessage;
    /** 请求 id */
    private String requestId;
    /** 请求时间 */
    private String requestTime;
    /** CatLog 跟踪 id */
    private String logTraceId;

    private Result(String resultCode, T data, String resultMessage) {
        this.resultCode = resultCode;
        this.data = data;
        this.resultMessage = resultMessage;
        this.setOtherInfo();
    }

    private Result(String resultCode, String resultMessage) {
        this.resultCode = resultCode;
        this.resultMessage = resultMessage;
        this.setOtherInfo();
    }

    private Result(T data, IResultCode resultCode) {
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = resultCode.getResultMessage();
        this.data = data;
        this.setOtherInfo();
    }

    private Result(IResultCode resultCode) {
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = resultCode.getResultMessage();
        this.setOtherInfo();
    }

    private Result(IResultCode resultCode, Object... args) {
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = MessageFormat.format(resultCode.getResultMessage(), args);
        this.setOtherInfo();
    }

    private Result(T data, IResultCode resultCode, Object... args) {
        this.data = data;
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = MessageFormat.format(resultCode.getResultMessage(), args);
        this.setOtherInfo();
    }

    private void setOtherInfo() {
        this.requestId = this.getMdcRequestId();
        this.logTraceId = this.getMdcLogTraceId();
        this.requestTime = this.getReqTime();
    }

    private String getMdcRequestId() {
        return MDC.get("traceId");
    }

    private String getReqTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }

    private String getMdcLogTraceId() {
        return MDC.get("rid");
    }

    public static <T> Result<T> success(T data) {
        return new Result(data, BaseResultCode.BIZ_SUCCESS);
    }

    public static <T> Result<T> fail(T data, IResultCode resultCode) {
        return new Result(data, resultCode);
    }

    public static <T> Result<T> fail(T data, String resultCode, String resultMessage) {
        Result<T> result = new Result(resultCode, resultMessage);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(IResultCode resultCode) {
        return new Result(resultCode);
    }

    public static <T> Result<T> fail(IResultCode resultCode, Object... args) {
        return new Result(resultCode, args);
    }

    public static <T> Result<T> fail(T data, IResultCode resultCode, Object... args) {
        return new Result(data, resultCode, args);
    }

    public static <T> Result<T> fail(String resultCode, String resultMessage) {
        return new Result(resultCode, resultMessage);
    }

    public static <T> Result<T> fail(String resultCode, String resultMessage, Object... args) {
        return fail(resultCode, MessageFormat.format(resultMessage, args));
    }

    public String getResultCode() {
        return this.resultCode;
    }

    public T getData() {
        return this.data;
    }

    public String getResultMessage() {
        return this.resultMessage;
    }

    public String getRequestId() {
        return this.requestId;
    }

    public String getRequestTime() {
        return this.requestTime;
    }

    public String getLogTraceId() {
        return this.logTraceId;
    }

    public void setResultCode(String resultCode) {
        this.resultCode = resultCode;
    }

    public void setData(T data) {
        this.data = data;
    }

    public void setResultMessage(String resultMessage) {
        this.resultMessage = resultMessage;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public void setRequestTime(String requestTime) {
        this.requestTime = requestTime;
    }

    public void setLogTraceId(String logTraceId) {
        this.logTraceId = logTraceId;
    }

    public Result() {
    }
}
