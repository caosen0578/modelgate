package com.pab.ficc.ibp.modelgate.server.adapter;

/** 逐帧更新，收尾时只读快照；不依赖报文记录开关。 */
public final class ResponseTelemetry {
    public static final String EXCHANGE_ATTRIBUTE = "mg.providerTelemetry";
    private volatile int inputTokens;
    private volatile int outputTokens;
    private volatile boolean hasUsage;
    private volatile String errorCode;
    private volatile String finishReason;
    public void usage(Integer input, Integer output) {
        if (input != null) inputTokens = input;
        if (output != null) outputTokens = output;
        hasUsage = true;
    }
    public int inputTokens() { return inputTokens; }
    public int outputTokens() { return outputTokens; }
    public boolean hasUsage() { return hasUsage; }
    public String errorCode() { return errorCode; }
    public void error(String code) { errorCode = code; }
    public String finishReason() { return finishReason; }
    public void finish(String reason) { finishReason = reason; }
}
