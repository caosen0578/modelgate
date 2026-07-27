/*
package com.pab.ficc.cus.tool.exception;

*/
/**
 * 名称: BizException
 * 描述: 业务异常
 * Version: 1.0
 * author: liuyue
 * date: 2020/03/19/14:00
 * version: 1.0
 *//*

public class BizException extends BaseBizException {

    public BizException(IResultCode resultCode) {
        super(resultCode);
    }

    */
/**
     * 构造方法 错误信息 resultMessage 中的占位符 {} 可用参数args替换
     * Params: resultCode - 消息对象
     *         args - 消息参数替换
     *//*

    public BizException(IResultCode resultCode, Object[] args) {
        super(resultCode, args);
    }

    public BizException(String resultCode) {
        this(resultCode, "");
    }

    */
/**
     * 构造方法
     * Params: resultCode - 消息码
     *         resultMessage - 消息内容
     *//*

    public BizException(String resultCode, String resultMessage) {
        super(resultCode, resultMessage);
    }

    */
/**
     * 构造方法 错误信息 resultMessage 中的占位符 {} 可用参数args替换 账号{0}不存在
     * Params: resultCode - 错误码
     *         args - 消息参数替换
     *//*

    public BizException(String resultCode, String resultMessage, Object[] args) {
        super(resultCode, resultMessage, args);
    }
}*/
