package com.pab.ficc.ibp.modelgate.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AppMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把管理面查询里的「appId + 应用编码」两个条件，翻译成审计/报文表上可执行的过滤条件。
 *
 * <p><b>为什么需要这一层</b>：{@code mg_audit_log} 与 {@code mg_request_payload} 里
 * <b>只有 {@code app_id}，没有 {@code app_code}</b>。要支持按应用编码筛选，只有两条路：
 * <ol>
 *   <li>把 {@code app_code} 冗余进这两张表 —— 需要改 DDL，并且采集侧（{@code TraceFilter} /
 *       {@code AuditService} / {@code PayloadService}）每请求都要多拿一次应用编码。
 *       <b>违反「不为管理功能增加每请求解析或数据库操作」，直接否掉。</b></li>
 *   <li><b>本类的做法</b>：管理面查询时先在 {@code mg_app}（管理表，行数是接入系统数量，
 *       内网量级在个位数到百级）把 {@code app_code} 解析成一组 {@code app_id}，
 *       再以 {@code app_id IN (...)} 去查大表。<b>转发热路径零改动</b>，
 *       多出来的一次小表查询只发生在管理员点「查询」的时候。</li>
 * </ol>
 *
 * <p><b>应用编码是精确匹配，不是模糊匹配</b>：它是编码而非名称，用户手里拿到的就是完整值；
 * 做成 {@code LIKE} 只会让 {@code app_code=CRM} 顺带命中 {@code CRM_TEST}，
 * 排查时把两个系统的流量混在一起看，比查不到更糟。
 *
 * <p><b>⚠️ 应用编码在库里不是全局唯一</b>（{@code mg_app} 的唯一键是 {@code app_id}，
 * {@code app_code} 的注释是"系统内唯一"），因此同一个编码可能落在不同 {@code sys_code} 下的
 * 多个应用上。所以解析结果是<b>一组</b> appId 而不是一个，这一点不能想当然。
 */
@Service
@RequiredArgsConstructor
public class AppScopeResolver {

    private final AppMapper appMapper;

    /**
     * 单个应用编码最多允许解析出多少个 appId。
     *
     * <p>超过就报错而不是照单全收：{@code IN (...)} 列表过长会让 MySQL 放弃走
     * {@code idx_app_id} 改走全表扫描，而审计表是全库增长最快的表。
     * 内网接入系统数量远达不到这个量级，真触发了说明查询条件本身有问题（比如编码填错成了通配值），
     * 此时提示用户补 appId 比默默扫全表正确。
     */
    public static final int MAX_MATCHES = 200;

    /**
     * 解析结果。三种互斥形态，调用方必须把三种都处理掉：
     *
     * <ul>
     *   <li>{@code matchNothing=true} —— 条件本身就注定查不到任何行（应用编码没有对应的应用，
     *       或 appId 与应用编码互相矛盾）。调用方<b>必须直接返回空页，不要去查大表</b>。
     *       这一形态最容易被漏掉：忘了处理就会退化成"没有 app 过滤条件"，
     *       结果是<b>把全部系统的数据当成该应用编码的数据返回</b>——比报错严重得多。</li>
     *   <li>{@code eqAppId != null} —— 按单个 appId 等值过滤。</li>
     *   <li>{@code inAppIds != null} —— 按一组 appId IN 过滤（非空）。</li>
     * </ul>
     * 三者都为空/false 表示不加任何应用维度的过滤。
     */
    public record AppScope(boolean matchNothing, String eqAppId, List<String> inAppIds) {

        static AppScope none() {
            return new AppScope(false, null, null);
        }

        static AppScope nothing() {
            return new AppScope(true, null, null);
        }

        static AppScope eq(String appId) {
            return new AppScope(false, appId, null);
        }

        static AppScope in(List<String> appIds) {
            return new AppScope(false, null, appIds);
        }
    }

    /**
     * 把 (appId, appCode) 翻译成 {@link AppScope}。
     *
     * <p>两者<b>同时给出时取交集</b>（AND 语义）：appId 不属于该应用编码时返回
     * {@link AppScope#matchNothing()}，而不是二选一忽略掉其中一个。
     * 忽略其一会让页面显示的筛选条件与实际执行的查询对不上——用户以为自己在看
     * "编码 X 下的 appId Y"，实际看到的是 "全部 appId Y" 或 "编码 X 的全部应用"。
     *
     * @param appId   接入系统 appId，可空
     * @param appCode 应用编码，可空
     */
    public AppScope resolve(String appId, String appCode) {
        boolean hasAppId = notBlank(appId);
        boolean hasAppCode = notBlank(appCode);

        if (!hasAppCode) {
            return hasAppId ? AppScope.eq(appId.trim()) : AppScope.none();
        }

        List<String> appIds = findAppIdsByAppCode(appCode.trim());
        if (appIds.isEmpty()) {
            // 编码本身查不到应用：可能是填错，也可能该应用已被删除。
            // 无论哪种，都不能退化成"不过滤"，否则会把全量数据当成它的数据展示。
            return AppScope.nothing();
        }
        if (appIds.size() > MAX_MATCHES) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, String.format(
                    "应用编码 %s 匹配到 %d 个应用，超过上限 %d；请同时指定 appId 缩小范围",
                    appCode.trim(), appIds.size(), MAX_MATCHES));
        }
        if (hasAppId) {
            return appIds.contains(appId.trim()) ? AppScope.eq(appId.trim()) : AppScope.nothing();
        }
        return appIds.size() == 1 ? AppScope.eq(appIds.get(0)) : AppScope.in(appIds);
    }

    /**
     * 按应用编码精确查 appId 列表。
     *
     * <p>只 {@code select app_id} 一列：{@code mg_app} 有 {@code remark VARCHAR(512)} 等宽列，
     * 这里只要主键映射，没必要把整行拉回来。
     *
     * <p>{@code app_code} 上<b>没有索引</b>，这里是一次小表全扫。可接受：
     * {@code mg_app} 的行数等于接入系统数量（内网个位数到百级），且只在管理员点查询时执行一次。
     * 若将来接入系统涨到千级以上，再考虑给 {@code app_code} 加索引。
     */
    private List<String> findAppIdsByAppCode(String appCode) {
        return appMapper.selectList(new LambdaQueryWrapper<AppInfoEntity>()
                        .select(AppInfoEntity::getAppId)
                        .eq(AppInfoEntity::getAppCode, appCode))
                .stream()
                .map(AppInfoEntity::getAppId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
