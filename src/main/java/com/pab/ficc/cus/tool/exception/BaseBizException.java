/*
package com.pab.ficc.cus.tool.exception;

import java.text.MessageFormat;


*/
/**
 * 基础业务异常
 *//*

public class BaseBizException extends HystrixBadRequestException {

    private final String resultCode;
    private final String resultMessage;

    public BaseBizException(IResultCode resultCode) {
        super(resultCode.getResultMessage());
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = resultCode.getResultMessage();
    }

    public BaseBizException(IResultCode resultCode, Object... args) {
        super(MessageFormat.format(resultCode.getResultMessage(), args));
        this.resultCode = resultCode.getResultCode();
        this.resultMessage = MessageFormat.format(resultCode.getResultMessage(), args);
    }

    public BaseBizException(String resultCode, String resultMessage) {
        super(resultMessage);
        this.resultCode = resultCode;
        this.resultMessage = resultMessage;
    }

    public BaseBizException(String resultCode, String resultMessage, Object... args) {
        super(MessageFormat.format(resultMessage, args));
        this.resultCode = resultCode;
        this.resultMessage = MessageFormat.format(resultMessage, args);
    }

    @Generated
    public String getResultCode() {
        return this.resultCode;
    }

    @Generated
    public String getResultMessage() {
        return this.resultMessage;
    }
}

// 依赖的返回码接口（与Result类共用）
interface IResultCode {
    String getResultCode();
    String getResultMessage();
}
*/
