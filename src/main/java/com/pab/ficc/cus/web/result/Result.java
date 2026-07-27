package com.pab.ficc.cus.web.result;

import org.slf4j.MDC;

import java.io.Serializable;
import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一返回数据
 */
//("统一返回数据")
public class Result<T> implements Serializable {

   //("状态码,00000000表示成功,其他code码表示失败")
    private String resultCode;

    //("业务数据")
    private T data;

    //("处理失败后,失败消息")
    private String resultMessage;

    //("请求id")
    private String requestId;

    //("请求时间")
    private String requestTime;

    //("CatLog跟踪id")
    private String logTraceId;

    // region 私有构造器
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
    // endregion

    // 填充链路ID、请求时间等公共字段
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

    // region 静态对外方法
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
    // endregion

    // region getter & setter
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

    public void setResultCode(final String resultCode) {
        this.resultCode = resultCode;
    }

    public void setData(final T data) {
        this.data = data;
    }

    public void setResultMessage(final String resultMessage) {
        this.resultMessage = resultMessage;
    }

    public void setRequestId(final String requestId) {
        this.requestId = requestId;
    }

    public void setRequestTime(final String requestTime) {
        this.requestTime = requestTime;
    }

    public void setLogTraceId(final String logTraceId) {
        this.logTraceId = logTraceId;
    }

    public Result() {
    }
    // endregion
}

// 配套接口、枚举示意（代码中依赖）
interface IResultCode {
    String getResultCode();
    String getResultMessage();
}

enum BaseResultCode implements IResultCode {
    BIZ_SUCCESS("00000000", "操作成功");

    private String code;
    private String msg;

    BaseResultCode(String code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getResultCode() {
        return this.code;
    }

    @Override
    public String getResultMessage() {
        return this.msg;
    }
}