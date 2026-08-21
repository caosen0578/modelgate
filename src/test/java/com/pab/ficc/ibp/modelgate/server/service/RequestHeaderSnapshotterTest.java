package com.pab.ficc.ibp.modelgate.server.service;

import org.springframework.http.HttpHeaders;

import java.util.Map;

/** 请求头白名单、凭证硬拒绝与长度保险丝回归；沿用项目 main 测试风格。 */
public class RequestHeaderSnapshotterTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        capturesConfiguredHeaderAndInteriorUserId();
        credentialHeadersAreNeverCaptured();
        allowlistCountIsHardBounded();
        snapshotIsBoundedAndMarked();

        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) System.exit(1);
    }

    private static void capturesConfiguredHeaderAndInteriorUserId() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter("X-User-Info", 8192, 16384);
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-user-info", "tenant=A;userId=U10086;role=maker");
        Map<String, String> result = snapshotter.capture(headers);
        check("Header 名大小写不敏感且保留值中间的用户 ID",
                result.get("X-User-Info").contains("U10086"));
    }

    private static void credentialHeadersAreNeverCaptured() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(
                        "X-User-Id,Authorization,X-Api-Key,X-User-Token,X-Internal-Credential,X-Session-Id",
                        8192, 16384);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "U10086");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer must-not-leak");
        headers.add("X-Api-Key", "must-not-leak");
        headers.add("X-User-Token", "must-not-leak");
        headers.add("X-Internal-Credential", "must-not-leak");
        headers.add("X-Session-Id", "must-not-leak");
        Map<String, String> result = snapshotter.capture(headers);
        check("白名单中的用户头被记录", "U10086".equals(result.get("X-User-Id")));
        check("Authorization 即使误配也不记录", !result.containsKey("Authorization"));
        check("X-Api-Key 即使误配也不记录", !result.containsKey("X-Api-Key"));
        check("Token 类 Header 即使误配也不记录", !result.containsKey("X-User-Token"));
        check("Credential 类 Header 即使误配也不记录", !result.containsKey("X-Internal-Credential"));
        check("Session ID 类 Header 即使误配也不记录", !result.containsKey("X-Session-Id"));
    }

    private static void snapshotIsBoundedAndMarked() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter("X-User-Info", 20, 64);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Info", "012345678901234567890123456789");
        Map<String, String> result = snapshotter.capture(headers);
        check("超长 Header 值被截断", result.get("X-User-Info").length() <= 20);
        check("截断状态显式写入快照", "true".equals(result.get("_modelgate_capture_truncated")));
    }

    private static void allowlistCountIsHardBounded() {
        StringBuilder allowlist = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (!allowlist.isEmpty()) allowlist.append(',');
            allowlist.append("X-User-Field-").append(i);
        }
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(allowlist.toString(), 8192, 16384);
        check("白名单误配过多时每请求查找次数仍有硬上限",
                snapshotter.allowedHeaders().size() == snapshotter.maxHeaderCount()
                        && snapshotter.maxHeaderCount() == 16);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }
}
