package com.pab.ficc.ibp.modelgate.server.adapter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;

/** 有界 SSE framing：字节跨块、CR/LF/CRLF、多行 data 和 heartbeat；chunk 不是帧。 */
public final class SseFrameDecoder {
    public record Frame(String event, String data) { }
    private final int maxFrameBytes;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String event = "message";
    private int frameBytes;
    private boolean previousCr;
    public SseFrameDecoder(int maxFrameBytes) { this.maxFrameBytes = maxFrameBytes; }
    public List<Frame> accept(byte[] bytes) {
        var output = new ArrayList<Frame>();
        for (byte value : bytes) {
            if (previousCr && value == '\n') { previousCr = false; continue; }
            previousCr = false;
            if (++frameBytes > maxFrameBytes) throw new BusinessException(ErrorCode.PROVIDER_RESPONSE_INVALID, "SSE 帧超过上限");
            if (value == '\r' || value == '\n') {
                finishLine(output); previousCr = value == '\r';
            } else line.write(value);
        }
        return output;
    }
    private void finishLine(List<Frame> output) {
        String text = line.toString(StandardCharsets.UTF_8); line.reset();
        if (text.isEmpty()) {
            if (!data.isEmpty()) {
                data.setLength(data.length() - 1);
                output.add(new Frame(event, data.toString()));
            }
            data.setLength(0); event = "message"; frameBytes = 0;
            return;
        }
        if (text.charAt(0) == ':') return;
        int split = text.indexOf(':');
        String name = split < 0 ? text : text.substring(0, split);
        String value = split < 0 ? "" : text.substring(split + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("event".equals(name)) event = value;
        if ("data".equals(name)) data.append(value).append('\n');
    }
    public boolean hasPartialFrame() { return line.size() != 0 || !data.isEmpty(); }
    public void clear() { line.reset(); data.setLength(0); frameBytes = 0; event = "message"; previousCr = false; }
}
