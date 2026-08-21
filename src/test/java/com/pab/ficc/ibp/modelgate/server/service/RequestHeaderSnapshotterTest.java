package com.pab.ficc.ibp.modelgate.server.service;

import org.springframework.http.HttpHeaders;

import java.util.Map;

/** 全部请求头留档与长度保险丝回归；沿用项目 main 测试风格。 */
public class RequestHeaderSnapshotterTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        capturesAllHeadersIncludingCredentials();
        capturesInteriorUserIdAndEmptyValue();
        doesNotApplyHeaderCountOrNameFiltering();
        snapshotIsBoundedAndMarked();

        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) System.exit(1);
    }

    private static void capturesAllHeadersIncludingCredentials() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(8192, 16384);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "U10086");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer must-be-recorded");
        headers.add(HttpHeaders.COOKIE, "SESSION=must-be-recorded");
        headers.add("X-Api-Key", "must-be-recorded");
        headers.add("X-User-Token", "must-be-recorded");
        headers.add("X-Me2AI-Signature", "must-be-recorded");
        Map<String, String> result = snapshotter.capture(headers);
        check("Authorization/Cookie/API Key/Token/签名与普通 Header 全部留档",
                result.size() == 6
                        && "Bearer must-be-recorded".equals(result.get(HttpHeaders.AUTHORIZATION))
                        && "SESSION=must-be-recorded".equals(result.get(HttpHeaders.COOKIE))
                        && "must-be-recorded".equals(result.get("X-Api-Key"))
                        && "must-be-recorded".equals(result.get("X-User-Token"))
                        && "must-be-recorded".equals(result.get("X-Me2AI-Signature"))
                        && "U10086".equals(result.get("X-User-Id")));
    }

    private static void capturesInteriorUserIdAndEmptyValue() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(8192, 16384);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Info", "tenant=A;userId=U10086;role=maker");
        headers.add("X-Empty", "");
        Map<String, String> result = snapshotter.capture(headers);
        check("用户 ID 位于值中间可保留，空值 Header 也不会丢失",
                result.get("X-User-Info").contains("U10086")
                        && result.containsKey("X-Empty")
                        && result.get("X-Empty").isEmpty());
    }

    private static void snapshotIsBoundedAndMarked() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(20, 256);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Info", "012345678901234567890123456789");
        Map<String, String> result = snapshotter.capture(headers);
        check("超长 Header 值被截断", result.get("X-User-Info").length() <= 20);
        check("截断状态显式写入快照", "true".equals(result.get("_modelgate_capture_truncated")));
    }

    private static void doesNotApplyHeaderCountOrNameFiltering() {
        RequestHeaderSnapshotter snapshotter =
                new RequestHeaderSnapshotter(8192, 32768);
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < 70; i++) {
            headers.add("X-Field-" + i, "value-" + i);
        }
        Map<String, String> result = snapshotter.capture(headers);
        check("不再按字段名称或 64 项数量过滤", result.size() == 70);
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
