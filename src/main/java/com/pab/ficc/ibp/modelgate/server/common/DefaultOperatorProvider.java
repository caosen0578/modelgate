package com.pab.ficc.ibp.modelgate.server.common;

import org.springframework.stereotype.Component;

/**
 * {@link OperatorProvider} 的默认占位实现：暂时返回 null（creater/updater/um 留空）。
 *
 * <p>接入行内用户上下文时，提供一个实现了 {@link OperatorProvider} 且标注 {@code @Primary}
 * 的 Bean 覆盖本默认实现即可（或直接替换本类实现）。
 */
@Component
public class DefaultOperatorProvider implements OperatorProvider {

    @Override
    public String currentOperator() {
        // TODO 接入行内用户上下文后返回真实操作人
        return null;
    }

    @Override
    public String currentOperatorUm() {
        // TODO 接入行内用户上下文后返回真实操作人 UM
        return null;
    }
}
