package com.osheeep.server.ai;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedOutlineGenerator implements AiOutlineGenerator {

    @Override
    public OutlineDraft generate(OutlineSource source) {
        String title = source.clusterTitle();
        String coreArgument = firstNonBlank(
                source.thesis(),
                source.fragmentContents().stream().findFirst().orElse(null),
                "围绕“" + title + "”继续收集可验证的观察。"
        );

        List<OutlineSection> outline = List.of(
                new OutlineSection(
                        "问题背景",
                        "从“" + title + "”切入，说明这个问题出现的场景与值得被讨论的原因。"
                ),
                new OutlineSection(
                        "核心论证",
                        "以“" + coreArgument + "”为主线，依次组织已有观察、论据与可能的反例。"
                ),
                new OutlineSection(
                        "行动与补链",
                        "将尚未闭合的问题转为下一轮素材收集任务，补齐案例、证据和可执行的行动。"
                )
        );

        return new OutlineDraft(
                List.of(title, "关于" + title + "的思考"),
                coreArgument,
                outline,
                source.supportingFragmentIds(),
                source.missingMaterials()
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Outline core argument is required");
    }
}
