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
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MybatisWiringSelfCheck {

    private final SqlSessionFactory tdsqlSessionFactory;

    @PostConstruct
    public void verify() {
        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(tdsqlSessionFactory.getConfiguration());

        if (globalConfig == null || globalConfig.getMetaObjectHandler() == null) {
            throw new IllegalStateException("""
                    MyBatis-Plus 装配缺失：GlobalConfig 未挂载 MetaObjectHandler。
                    后果：基础字段不会自动填充，is_del 将被写入 NULL，而 @TableLogic 的
                    WHERE is_del='0' 匹配不到 NULL —— 新增数据插入成功但永远查不出来。
                    修复：在 TdsqlMybatisPlusConfig 的 SqlSessionFactory 中
                         factory.setGlobalConfig(globalConfig.setMetaObjectHandler(...))""");
        }

        boolean hasPagination = tdsqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .flatMap(i -> i.getInterceptors().stream())
                .anyMatch(PaginationInnerInterceptor.class::isInstance);

        if (!hasPagination) {
            throw new IllegalStateException("""
                    MyBatis-Plus 装配缺失：未注册 PaginationInnerInterceptor。
                    后果：selectPage 退化为不带 LIMIT 的全表查询、total 恒为 0，
                         列表页分页失效，审计表每次翻页把整张表捞进内存。
                    修复：在 TdsqlMybatisPlusConfig 的 mybatisPlusInterceptor() 中
                         addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL))""");
        }

        long interceptorCount = tdsqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(Interceptor.class::isInstance).count();
        log.info("[MyBatis自检] 装配校验通过: MetaObjectHandler={}, 分页拦截器=已注册, 拦截器总数={}",
                globalConfig.getMetaObjectHandler().getClass().getSimpleName(), interceptorCount);
    }
}
