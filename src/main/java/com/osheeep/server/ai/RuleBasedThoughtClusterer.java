package com.osheeep.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedThoughtClusterer {

    public static final String UNGROUPED_TITLE = "未归组";
    private static final String MISSING_QUESTION = "继续补充论据和例子";

    private final ObjectMapper objectMapper;

    public RuleBasedThoughtClusterer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ClusterDraft> cluster(List<ThoughtFragmentEntity> fragments) {
        Map<String, List<ThoughtFragmentEntity>> groups = new LinkedHashMap<>();
        for (ThoughtFragmentEntity fragment : fragments) {
            groups.computeIfAbsent(themeOf(fragment), key -> new ArrayList<>()).add(fragment);
        }

        return groups.entrySet().stream()
                .map(entry -> draft(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ClusterDraft draft(String title, List<ThoughtFragmentEntity> fragments) {
        int fragmentCount = fragments.size();
        int maturityScore = Math.min(100, fragmentCount * 20);
        List<String> missingQuestions = fragmentCount < 3 ? List.of(MISSING_QUESTION) : List.of();
        String status = status(fragmentCount, maturityScore);
        String thesis = "围绕" + title + "的 " + fragmentCount + " 条碎片";
        return new ClusterDraft(title, fragments, maturityScore, missingQuestions, status, thesis);
    }

    private String themeOf(ThoughtFragmentEntity fragment) {
        if (fragment.getMetadata() == null || fragment.getMetadata().isBlank()) {
            return UNGROUPED_TITLE;
        }

        try {
            JsonNode metadata = objectMapper.readTree(fragment.getMetadata());
            String theme = metadata.path("theme").asText();
            return theme == null || theme.isBlank() ? UNGROUPED_TITLE : theme.trim();
        } catch (Exception exception) {
            return UNGROUPED_TITLE;
        }
    }

    private String status(int fragmentCount, int maturityScore) {
        if (fragmentCount < 3) {
            return "forming";
        }
        if (maturityScore >= 80) {
            return "mature";
        }
        return "active";
    }

    public record ClusterDraft(
            String title,
            List<ThoughtFragmentEntity> fragments,
            int maturityScore,
            List<String> missingQuestions,
            String status,
            String thesis
    ) {
    }
}
