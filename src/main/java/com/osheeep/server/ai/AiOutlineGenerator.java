package com.osheeep.server.ai;

import java.util.List;

public interface AiOutlineGenerator {

    OutlineDraft generate(OutlineSource source);

    record OutlineSource(
            String clusterTitle,
            String thesis,
            List<Long> supportingFragmentIds,
            List<String> fragmentContents,
            List<String> missingMaterials
    ) {
    }

    record OutlineDraft(
            List<String> titleCandidates,
            String coreArgument,
            List<OutlineSection> outline,
            List<Long> supportingFragmentIds,
            List<String> missingMaterials
    ) {
    }

    record OutlineSection(String title, String content) {
    }
}
