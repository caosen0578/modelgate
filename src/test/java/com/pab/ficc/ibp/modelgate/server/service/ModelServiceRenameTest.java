package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;

/**
 * 模型改名时「上游真实模型 ID」的取值规则回归。
 *
 * <p>与项目其它测试一致，用 {@code main()}，不引入测试框架。
 *
 * <p><b>这条规则写反的后果</b>：改完名字之后，该模型的<b>每一个请求</b>都会被上游拒绝
 * （上游不认识网关对外新起的名字），而链路上没有任何一层会报错——
 * 网关这边一切正常，只能靠上游返回的 model not found 去反推。所以单独钉一组用例。
 *
 * <p>运行方式（项目根目录）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.service.ModelServiceRenameTest
 * </pre>
 */
public class ModelServiceRenameTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        blankUpstreamIsBackfilledWithOldId();
        explicitUpstreamIsRespected();
        endToEndIdentityIsPreserved();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 上游 ID 留空 → 回填改名前的 ID，保证上游收到的东西不变 */
    private static void blankUpstreamIsBackfilledWithOldId() {
        check("上游 ID 为 null 时回填旧 ID",
                "old-id".equals(ModelService.resolveUpstreamIdOnRename("old-id", null)));
        check("上游 ID 为空串时回填旧 ID",
                "old-id".equals(ModelService.resolveUpstreamIdOnRename("old-id", "")));
        check("上游 ID 为纯空白时回填旧 ID",
                "old-id".equals(ModelService.resolveUpstreamIdOnRename("old-id", "   ")));
    }

    /** 运维显式填了就尊重他的选择，不能拿旧 ID 去覆盖 */
    private static void explicitUpstreamIsRespected() {
        check("显式填写时原样保留",
                "real-provider-id".equals(
                        ModelService.resolveUpstreamIdOnRename("old-id", "real-provider-id")));
        check("显式填成新 ID 时也尊重（运维要让上游一起改名）",
                "new-id".equals(ModelService.resolveUpstreamIdOnRename("old-id", "new-id")));
    }

    /**
     * 端到端语义：改名 + 回填之后，{@code resolveUpstreamModelId()} 必须仍然吐出旧 ID。
     *
     * <p>这一步才是真正要守住的东西——前面两个用例只验证了取值函数，
     * 这里验证它与实体上的回退逻辑组合起来的<b>净效果</b>。
     */
    private static void endToEndIdentityIsPreserved() {
        // 改名前：上游 ID 留空，实体回退到 model_id
        ModelInstanceEntity before = new ModelInstanceEntity();
        before.setModelId("deepseek-ai/DeepSeek-R1");
        String upstreamBefore = before.resolveUpstreamModelId();
        check("改名前上游收到对外 ID", "deepseek-ai/DeepSeek-R1".equals(upstreamBefore));

        // 改名为 dsr1，提交的上游 ID 为空 → 按规则回填旧 ID
        ModelInstanceEntity after = new ModelInstanceEntity();
        after.setModelId("dsr1");
        after.setUpstreamModelId(
                ModelService.resolveUpstreamIdOnRename("deepseek-ai/DeepSeek-R1", null));

        check("改名后上游收到的仍是旧 ID（身份未被连带改掉）",
                upstreamBefore.equals(after.resolveUpstreamModelId()));

        // 反例：不回填的话上游身份就变了——这正是本次要修的漏洞
        ModelInstanceEntity broken = new ModelInstanceEntity();
        broken.setModelId("dsr1");
        check("反例确认：不回填则上游身份被改成新 ID",
                "dsr1".equals(broken.resolveUpstreamModelId()));
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
