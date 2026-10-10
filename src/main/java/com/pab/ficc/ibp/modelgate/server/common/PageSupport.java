package com.pab.ficc.ibp.modelgate.server.common;

import com.pab.ficc.ibp.domain.base.bo.BaseBo;

/**
 * 分页入参护栏。
 *
 * <p><b>为什么必须有</b>：行内 {@code BaseBo.setPageSize} 是一个<b>裸 setter</b>，
 * 框架层不做任何收敛（已反编译确认：方法体只有 {@code putfield}）。
 * 也就是说所有走 {@code BaseBo} 的列表接口，只要没人在 Controller 里拦，
 * 调用方传多大就是多大。实测 {@code GET /audit?pageSize=100000}
 * 返回 HTTP 200、2.8 秒、1.7MB，把当时整张审计表都吐了出来。
 *
 * <p>危害不止于"返回慢"：这类查询会长时间占住一条 Druid 连接（池上限 100）
 * 与一个线程，而 {@code mg_audit_log} 是保留 90 天、全库增长最快的表，
 * 涨到百万行后同一个 URL 就是几分钟、几百 MB。
 *
 * <p><b>两条护栏，缺一不可</b>：
 * <ul>
 *   <li>{@code pageSize} 上限——限制<b>返回</b>多少行；</li>
 *   <li>OFFSET 上限——限制<b>跳过</b>多少行。只封 pageSize 是不够的：
 *       {@code pageSize=20&pageIndex=500000} 一样要让 MySQL 扫过一千万行才能定位到那一页，
 *       而分页条的「末页」按钮恰好会精确命中最大合法 OFFSET。</li>
 * </ul>
 *
 * <p>超限一律<b>抛异常</b>而不是静默截断：静默改小会让调用方以为自己拿到了完整数据，
 * 分页总数与实际返回对不上，比直接报错更难排查。
 */
public final class PageSupport {

    private PageSupport() {}

    /**
     * 单页最大条数。
     *
     * <p>200 这个值不是随手取的：管理面前端的下拉最大是 100，
     * 而「填充筛选下拉」这类一次性拉全量的地方用的正是 {@code pageSize=200}
     * （见 admin.html 的 {@code fetchSelectableModels} / {@code fillAuditFilters}），
     * 所以上限必须 ≥200，取等号即可。
     */
    public static final long MAX_PAGE_SIZE = 200;

    /**
     * 最大可跳过的行数（= {@code (pageIndex-1) × pageSize}）。
     *
     * <p>5 万按每页 20 条算是第 2500 页。真要看更早的数据，正确做法是用时间/关键字条件
     * 直接定位，而不是一页页翻——所以超限提示会往那个方向引导。
     */
    public static final long MAX_OFFSET = 50_000;

    /**
     * 校验分页参数。
     *
     * @param q     任意继承 {@code BaseBo} 的查询入参
     * @param what  出错信息里对该列表的称呼，如 "审计日志"
     * @throws BusinessException 参数非法时抛出，由 GlobalExceptionHandler 转成 400
     */
    public static void validate(BaseBo q, String what) {
        if (q == null) {
            return;
        }
        long pageIndex = q.getPageIndex();
        long pageSize = q.getPageSize();

        if (pageIndex <= 0 || pageSize <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    what + "查询的 pageIndex 与 pageSize 必须为正数（当前 pageIndex="
                            + pageIndex + ", pageSize=" + pageSize + "）");
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    what + "查询的 pageSize 不能超过 " + MAX_PAGE_SIZE + "，当前传入 " + pageSize
                            + "（单页过大会长时间占用数据库连接，影响网关转发）");
        }
        long offset = (pageIndex - 1) * pageSize;
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, String.format(
                    "%s翻页过深（已跳过 %d 条，上限 %d）。请改用筛选条件直接定位到目标数据，"
                            + "不要一页页翻——被跳过的行同样要由数据库扫描，代价与跳过的行数成正比",
                    what, offset, MAX_OFFSET));
        }
    }
}
