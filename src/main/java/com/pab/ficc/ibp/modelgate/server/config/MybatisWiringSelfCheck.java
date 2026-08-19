package com.pab.ficc.ibp.modelgate.server.config;

import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配自检：把「静默失效」变成「启动失败」。
 *
 * <p><b>为什么需要这个类</b>：{@link TdsqlMybatisPlusConfig} 自定义了 SqlSessionFactory，
 * {@code MybatisPlusAutoConfiguration} 因此整体退避，<b>凡是自动装配原本提供的能力都得手动补齐</b>。
 * 漏配任何一项都不会报错，只会在运行期以极隐蔽的方式表现出来——这已经真实发生过两次：
 * <ul>
 *   <li><b>漏挂 MetaObjectHandler</b>：基础字段不填充，{@code is_del} 被写成 NULL，
 *       而 {@code @TableLogic} 的 {@code WHERE is_del='0'} 匹配不到 NULL，
 *       新增的模型/接入系统/凭证<b>插入成功却查不出来</b>，页面上像是没保存。</li>
 *   <li><b>漏挂分页拦截器</b>：{@code selectPage} 退化成不带 LIMIT 的全表查询、
 *       {@code total} 恒为 0，分页条形同虚设，审计表每次翻页把整张表捞进内存。</li>
 * </ul>
 * 两次都是排查了很久才定位到装配层。本类在启动时直接断言这两项，
 * <b>缺失即启动失败</b>——宁可起不来，也不要带着静默写坏数据的配置跑进生产。
 *
 * <p>新增 GlobalConfig / 拦截器相关的装配时，请在此同步补一条断言。
 *
 * <p><b>内网合并版同样需要引入本类，而且更需要</b>：内网若使用行内框架自己的
 * SqlSessionFactory，MybatisPlusAutoConfiguration 一样会退避，同样存在漏配 MetaObjectHandler
 * 的风险，而且没有本类就<b>完全没有任何信号</b>——症状会是「新增数据的时间字段是空的」
 * 或「插入成功却查不出来」，要花很久才能定位到装配层。
 * 本类已改为按类型注入 ObjectProvider，不依赖任何特定 bean 名，任何环境都能安全引入；
 * 装配正确时它只在启动时打一行日志，零运行期成本。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MybatisWiringSelfCheck {

    /**
     * 按<b>类型</b>注入全部 SqlSessionFactory，而不是按名字注入 {@code tdsqlSessionFactory}。
     *
     * <p>早先按名字注入，导致本类<b>只能在本仓库跑</b>：内网合并版若用行内框架自己的
     * SqlSessionFactory（bean 名不叫 tdsqlSessionFactory），本类会因为找不到 bean 而
     * 让应用起不来——于是内网干脆不引入这个类，连带失去了它提供的全部保护，
     * 而它恰恰是用来兜住"基础字段不自动填充"这类静默故障的。
     *
     * <p>改为按类型注入 {@code ObjectProvider}：有几个查几个，一个都没有也不报错。
     * 这样任何环境都能安全引入。
     */
    private final ObjectProvider<SqlSessionFactory> sqlSessionFactories;

    @PostConstruct
    public void verify() {
        List<SqlSessionFactory> factories = sqlSessionFactories.stream().toList();

        if (factories.isEmpty()) {
            // 容器里没有 SqlSessionFactory 说明装配方式与预期完全不同，
            // 此时断言无从谈起。只告警不拦启动——本类的职责是"发现问题"，不是"制造问题"。
            log.warn("[MyBatis自检] 未发现任何 SqlSessionFactory，跳过装配校验。"
                    + "若本应用确实使用 MyBatis，请确认装配方式；基础字段自动填充与分页拦截器将无人校验");
            return;
        }

        for (SqlSessionFactory factory : factories) {
            verifyOne(factory);
        }
    }

    private void verifyOne(SqlSessionFactory factory) {
        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(factory.getConfiguration());

        if (globalConfig == null || globalConfig.getMetaObjectHandler() == null) {
            throw new IllegalStateException("""
                    MyBatis-Plus 装配缺失：GlobalConfig 未挂载 MetaObjectHandler。
                    后果一：基础字段不会自动填充，create_time / update_time / creater / updater
                           全部为空。若全局 insertStrategy 会写出显式 NULL，还会**覆盖建表时的
                           DEFAULT CURRENT_TIMESTAMP**，表现为「新增的数据时间字段是空的」。
                    后果二：is_del 被写入 NULL，而 @TableLogic 的 WHERE is_del='0' 匹配不到 NULL
                           —— 新增数据插入成功但永远查不出来。
                    修复：在定义 SqlSessionFactory 的地方补上
                         factory.setGlobalConfig(new GlobalConfig().setMetaObjectHandler(handler))
                         （本仓库见 TdsqlMybatisPlusConfig）""");
        }

        boolean hasPagination = factory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .flatMap(i -> i.getInterceptors().stream())
                .anyMatch(PaginationInnerInterceptor.class::isInstance);

        if (!hasPagination) {
            throw new IllegalStateException("""
                    MyBatis-Plus 装配缺失：未注册 PaginationInnerInterceptor。
                    后果：selectPage 退化为不带 LIMIT 的全表查询、total 恒为 0，
                         列表页分页失效，审计表每次翻页把整张表捞进内存。
                    修复：在定义 MybatisPlusInterceptor 的地方
                         addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL))""");
        }

        long interceptorCount = factory.getConfiguration().getInterceptors().stream()
                .filter(Interceptor.class::isInstance).count();
        log.info("[MyBatis自检] 装配校验通过: MetaObjectHandler={}, 分页拦截器=已注册, 拦截器总数={}",
                globalConfig.getMetaObjectHandler().getClass().getSimpleName(), interceptorCount);
    }
}
