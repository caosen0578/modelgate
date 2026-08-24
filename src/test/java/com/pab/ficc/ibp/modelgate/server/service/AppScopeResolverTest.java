package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AppScopeResolver} 的决策逻辑回归。
 *
 * <p><b>为什么单独测这个类</b>：它里面有一条极其危险的分支——
 * "应用编码查不到应用"必须返回 {@code matchNothing}，让调用方直接给空页。
 * 一旦这条分支退化成"不加过滤"，审计/报文查询就会把<b>全部系统</b>的数据
 * 当成该应用编码的数据返回。报文里是用户原始 prompt，这属于越界展示，
 * 比查不到严重得多，而且表现上完全"正常"——不会报错、不会为空，只是数据是错的。
 *
 * <p><b>不引入 Mock 框架</b>（与项目其它测试一致）：用 JDK 动态代理造一个只回答
 * {@code selectList} 的假 {@code AppMapper}，其余方法返回 null。
 * 假 mapper <b>忽略 wrapper 条件</b>，直接返回预置行——本测试要验的是
 * "拿到 N 条结果之后怎么决策"，SQL 条件本身已由打真实库的接口验证覆盖。
 *
 * <p>运行方式（项目根目录）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.service.AppScopeResolverTest
 * </pre>
 */
public class AppScopeResolverTest {

    private static int pass = 0;
    private static int fail = 0;

    static {
        // LambdaQueryWrapper 解析 AppInfoEntity::getAppCode 需要 MyBatis-Plus 的 TableInfo 缓存，
        // 而该缓存平时是 Spring 启动时由 MapperScan 建的。脱离容器跑测试必须手动初始化一次，
        // 否则第一次用到 lambda 列名就抛 "can not find lambda cache for this entity"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AppInfoEntity.class);
    }

    public static void main(String[] args) {
        // ① 两个都不给 → 不加任何应用维度过滤
        AppScopeResolver.AppScope s = resolverReturning().resolve(null, null);
        check("无条件：不过滤", !s.matchNothing() && s.eqAppId() == null && s.inAppIds() == null);

        // ② 只给 appId → 等值过滤，且不查 mg_app（假 mapper 返回空也不影响结论）
        s = resolverReturning().resolve("app-1", null);
        check("只给 appId：等值过滤", !s.matchNothing() && "app-1".equals(s.eqAppId()) && s.inAppIds() == null);

        // ③ appId 两侧空白应被 trim
        s = resolverReturning().resolve("  app-1  ", null);
        check("appId 去空白", "app-1".equals(s.eqAppId()));

        // ④ 编码命中 1 个 → 退化成等值（比 IN 单值更利于走索引）
        s = resolverReturning("app-1").resolve(null, "CODE");
        check("编码命中 1 个：等值过滤", !s.matchNothing() && "app-1".equals(s.eqAppId()) && s.inAppIds() == null);

        // ⑤ 编码命中多个 → IN 过滤（app_code 只做"系统内唯一"，跨 sys_code 可重名）
        s = resolverReturning("app-1", "app-2").resolve(null, "CODE");
        check("编码命中多个：IN 过滤",
                !s.matchNothing() && s.eqAppId() == null
                        && s.inAppIds() != null && s.inAppIds().size() == 2);

        // ⑥ ⚠️ 核心：编码查无应用 → matchNothing，绝不能退化成"不过滤"
        s = resolverReturning().resolve(null, "NO_SUCH");
        check("编码查无应用：matchNothing（不是不过滤）",
                s.matchNothing() && s.eqAppId() == null && s.inAppIds() == null);

        // ⑦ appId 与编码矛盾 → matchNothing
        s = resolverReturning("app-1", "app-2").resolve("app-9", "CODE");
        check("appId 不属于该编码：matchNothing", s.matchNothing());

        // ⑧ appId 与编码一致 → 取交集，收敛到该 appId
        s = resolverReturning("app-1", "app-2").resolve("app-2", "CODE");
        check("appId 属于该编码：收敛为等值", !s.matchNothing() && "app-2".equals(s.eqAppId()));

        // ⑨ 命中数超上限 → 报错要求补 appId，而不是拼一个超长 IN 去全表扫
        String[] many = new String[AppScopeResolver.MAX_MATCHES + 1];
        for (int i = 0; i < many.length; i++) many[i] = "app-" + i;
        boolean threw = false;
        try {
            resolverReturning(many).resolve(null, "CODE");
        } catch (BusinessException e) {
            threw = e.getMessage() != null && e.getMessage().contains("超过上限");
        }
        check("命中数超 " + AppScopeResolver.MAX_MATCHES + " 时报错", threw);

        // ⑩ 空白编码等同于未提供（避免前端提交空串时触发一次无谓的小表扫描 + 误判 matchNothing）
        s = resolverReturning().resolve("app-1", "   ");
        check("空白编码等同未提供", !s.matchNothing() && "app-1".equals(s.eqAppId()));

        // ⑪ 库里 app_id 为空/重复时要被过滤和去重，否则 IN 里会出现 null
        s = resolverReturning("app-1", null, "", "app-1").resolve(null, "CODE");
        check("null/空/重复 appId 被清理", !s.matchNothing() && "app-1".equals(s.eqAppId()));

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 造一个 selectList 恒返回指定 appId 行的假 AppMapper，其余方法返回 null。 */
    private static AppScopeResolver resolverReturning(String... appIds) {
        List<AppInfoEntity> rows = new ArrayList<>();
        for (String id : appIds) {
            AppInfoEntity e = new AppInfoEntity();
            e.setAppId(id);
            rows.add(e);
        }
        AppMapper fake = (AppMapper) Proxy.newProxyInstance(
                AppScopeResolverTest.class.getClassLoader(),
                new Class<?>[]{AppMapper.class},
                (proxy, method, methodArgs) -> "selectList".equals(method.getName()) ? rows : null);
        return new AppScopeResolver(fake);
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
