package com.pab.ficc.ibp.modelgate.server.domain;

/**
 * 基础返回码枚举。
 *
 * <p><b>本地副本说明</b>：与行内 SDK 的 {@code com.pab.ficc.ibp.web.result.BaseResultCode} 同构。
 * 成功码固定为 {@code 00000000}，前端 {@code admin.html} 的 {@code isOk()} 依此判定，勿改。
 * 合入内网时随 {@link Result} / {@link IResultCode} 一并删除，改用行内 SDK 版本。
 */
public enum BaseResultCode implements IResultCode {

    BIZ_SUCCESS("00000000", "操作成功");

    private final String code;
    private final String msg;

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
