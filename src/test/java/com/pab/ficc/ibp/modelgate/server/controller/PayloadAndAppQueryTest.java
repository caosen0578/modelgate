package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateAppRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.PayloadQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.service.RequestHeaderSnapshotter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 系统/应用双名称兼容规则与请求正文检索生产护栏回归。
 *
 * <p>沿用项目现有 main 测试风格，不额外引入测试框架。这里刻意直接调用 Controller 的校验：
 * SQL 是否能拼出来不是核心风险，真正必须钉死的是 LONGTEXT 模糊查询不能绕过时间/TraceID 上界。
 */
public class PayloadAndAppQueryTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        distinctAppNameIsPreserved();
        oldClientFallsBackToAppName();
        requestKeywordNeedsNarrowing();
        requestKeywordAcceptsBoundedWindow();
        headerKeywordNeedsNarrowing();
        shortHeaderKeywordIsRejected();
        headerKeywordAcceptsBoundedWindow();
        windowOver24HoursIsRejectedWithoutRoundingLoophole();
        twoKeywordModesCannotBeCombined();

        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) System.exit(1);
    }

    private static void distinctAppNameIsPreserved() {
        CreateAppRequestBO req = baseAppRequest();
        req.setSystemName("客户关系管理系统");
        req.setAppName("CRM 智能助手");
        AppInfoEntity entity = req.toEntity();
        check("系统名称与应用名称独立保存",
                "客户关系管理系统".equals(entity.getSystemName())
                        && "CRM 智能助手".equals(entity.getAppName()));
    }

    private static void oldClientFallsBackToAppName() {
        CreateAppRequestBO req = baseAppRequest();
        req.setAppName("旧页面名称");
        check("旧页面未传 systemName 时安全回退",
                "旧页面名称".equals(req.toEntity().getSystemName()));
    }

    private static void requestKeywordNeedsNarrowing() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setRequestKeyword("user-10086");
        check("请求正文模糊检索不能单独执行", validationFails(q));
    }

    private static void requestKeywordAcceptsBoundedWindow() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setRequestKeyword("user-10086");
        q.setStartTime(LocalDateTime.of(2026, 8, 20, 8, 0));
        q.setEndTime(LocalDateTime.of(2026, 8, 20, 20, 0));
        check("请求正文模糊检索接受 24 小时内窗口", !validationFails(q));
    }

    private static void twoKeywordModesCannotBeCombined() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setKeyword("legacy");
        q.setRequestKeyword("user-10086");
        q.setTraceId("12345678");
        check("新旧关键字参数禁止同时传入", validationFails(q));
    }

    private static void headerKeywordNeedsNarrowing() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setHeaderKeyword("user-10086");
        check("请求头全模糊检索不能单独执行", validationFails(q));
    }

    private static void shortHeaderKeywordIsRejected() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setHeaderKeyword("ab");
        q.setTraceId("12345678");
        check("请求头关键字不足 3 字符仍被拒绝", validationFails(q));
    }

    private static void headerKeywordAcceptsBoundedWindow() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setHeaderKeyword("user-10086");
        q.setStartTime(LocalDateTime.of(2026, 8, 20, 8, 0));
        q.setEndTime(LocalDateTime.of(2026, 8, 20, 20, 0));
        check("请求头全模糊检索接受 24 小时内窗口", !validationFails(q));
    }

    private static void windowOver24HoursIsRejectedWithoutRoundingLoophole() throws Exception {
        PayloadQueryBO q = new PayloadQueryBO();
        q.setHeaderKeyword("user-10086");
        q.setStartTime(LocalDateTime.of(2026, 8, 20, 8, 0));
        q.setEndTime(LocalDateTime.of(2026, 8, 21, 8, 1));
        check("24 小时零 1 分不能被 Duration 向下取整后放行", validationFails(q));
    }

    private static CreateAppRequestBO baseAppRequest() {
        CreateAppRequestBO req = new CreateAppRequestBO();
        req.setAppId("crm-service");
        req.setSysCode("CRM");
        req.setAppCode("CRM001");
        req.setOwner("tester");
        return req;
    }

    private static boolean validationFails(PayloadQueryBO q) throws Exception {
        // validate() 只看入参本身，不碰 mapper / AppScopeResolver，故后两个依赖传 null 即可。
        // AppScopeResolver 是 2026-08-24「按应用编码筛选」新增的第三个构造参数。
        PayloadController controller = new PayloadController(null,
                new RequestHeaderSnapshotter(8192, 16384), null);
        Method validate = PayloadController.class.getDeclaredMethod("validate", PayloadQueryBO.class);
        validate.setAccessible(true);
        try {
            validate.invoke(controller, q);
            return false;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BusinessException) return true;
            throw e;
        }
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
