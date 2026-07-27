package com.pab.ficc.ibp.modelgate.server.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.pab.ficc.ibp.modelgate.server.common.OperatorProvider;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 内部规范基础字段自动填充。
 *
 * <p>仅对存在对应字段的实体生效（{@code strictXxxFill} 内部会校验 setter 是否存在），
 * 因此不含这些字段的实体（如 {@code AuditLogEntity}）不受影响。
 *
 * <ul>
 *   <li>insert：create_time / update_time / is_del('0') / creater / updater / creater_um / updater_um</li>
 *   <li>update：update_time / updater / updater_um</li>
 * </ul>
 *
 * <p>creater/updater/um 取自 {@link OperatorProvider}，当前为占位（null），后续接入行内用户上下文。
 */
@Component
@RequiredArgsConstructor
public class AuditFieldMetaObjectHandler implements MetaObjectHandler {

    private final OperatorProvider operatorProvider;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = operatorProvider.currentOperator();
        String operatorUm = operatorProvider.currentOperatorUm();

        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "isDel", String.class, "0");
        this.strictInsertFill(metaObject, "creater", String.class, operator);
        this.strictInsertFill(metaObject, "updater", String.class, operator);
        this.strictInsertFill(metaObject, "createrUm", String.class, operatorUm);
        this.strictInsertFill(metaObject, "updaterUm", String.class, operatorUm);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updater", String.class, operatorProvider.currentOperator());
        this.strictUpdateFill(metaObject, "updaterUm", String.class, operatorProvider.currentOperatorUm());
    }
}
