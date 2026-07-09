package com.osheeep.server.thought.cluster;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.ai.RuleBasedThoughtClusterer;
import com.osheeep.server.ai.RuleBasedThoughtClusterer.ClusterDraft;
import com.osheeep.server.thought.cluster.dto.RebuildClustersResponse;
import com.osheeep.server.thought.cluster.dto.ThoughtClusterResponse;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterEntity;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterFragmentEntity;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThoughtClusterService {

    private final ThoughtClusterMapper clusterMapper;
    private final ThoughtClusterFragmentMapper clusterFragmentMapper;
    private final ThoughtFragmentMapper fragmentMapper;
    private final RuleBasedThoughtClusterer clusterer;
    private final ObjectMapper objectMapper;

    public ThoughtClusterService(
            ThoughtClusterMapper clusterMapper,
            ThoughtClusterFragmentMapper clusterFragmentMapper,
            ThoughtFragmentMapper fragmentMapper,
            RuleBasedThoughtClusterer clusterer,
            ObjectMapper objectMapper
    ) {
        this.clusterMapper = clusterMapper;
        this.clusterFragmentMapper = clusterFragmentMapper;
        this.fragmentMapper = fragmentMapper;
        this.clusterer = clusterer;
        this.objectMapper = objectMapper;
    }

    public List<ThoughtClusterResponse> listClusters(Long userId) {
        return clusterMapper.selectList(Wrappers.lambdaQuery(ThoughtClusterEntity.class)
                        .eq(ThoughtClusterEntity::getUserId, userId)
                        .isNull(ThoughtClusterEntity::getDeletedAt)
                        .orderByDesc(ThoughtClusterEntity::getUpdatedAt)
                        .orderByDesc(ThoughtClusterEntity::getId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RebuildClustersResponse rebuild(Long userId) {
        List<ThoughtFragmentEntity> fragments = fragmentMapper.selectList(Wrappers.lambdaQuery(ThoughtFragmentEntity.class)
                .eq(ThoughtFragmentEntity::getUserId, userId)
                .isNull(ThoughtFragmentEntity::getDeletedAt)
                .orderByAsc(ThoughtFragmentEntity::getCreatedAt)
                .orderByAsc(ThoughtFragmentEntity::getId));

        LocalDateTime now = LocalDateTime.now();
        clusterFragmentMapper.delete(Wrappers.lambdaQuery(ThoughtClusterFragmentEntity.class)
                .eq(ThoughtClusterFragmentEntity::getUserId, userId));

        ThoughtClusterEntity deleted = new ThoughtClusterEntity();
        deleted.setDeletedAt(now);
        clusterMapper.update(deleted, Wrappers.lambdaUpdate(ThoughtClusterEntity.class)
                .eq(ThoughtClusterEntity::getUserId, userId)
                .isNull(ThoughtClusterEntity::getDeletedAt));

        for (ClusterDraft draft : clusterer.cluster(fragments)) {
            ThoughtClusterEntity cluster = new ThoughtClusterEntity();
            cluster.setUserId(userId);
            cluster.setTitle(draft.title());
            cluster.setSummary(draft.thesis());
            cluster.setMetadata(metadata(draft));
            clusterMapper.insert(cluster);
            insertLinks(userId, cluster.getId(), draft.fragments());
        }

        return new RebuildClustersResponse(
                userId.toString(),
                "completed",
                "主题聚类已重建",
                null
        );
    }

    private void insertLinks(Long userId, Long clusterId, List<ThoughtFragmentEntity> fragments) {
        for (int index = 0; index < fragments.size(); index++) {
            ThoughtClusterFragmentEntity link = new ThoughtClusterFragmentEntity();
            link.setUserId(userId);
            link.setClusterId(clusterId);
            link.setFragmentId(fragments.get(index).getId());
            link.setPosition(index);
            clusterFragmentMapper.insert(link);
        }
    }

    private ThoughtClusterResponse toResponse(ThoughtClusterEntity entity) {
        ClusterMetadata metadata = parseMetadata(entity.getMetadata());
        return new ThoughtClusterResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getTitle(),
                metadata.thesis(),
                metadata.fragmentIds(),
                metadata.maturityScore(),
                metadata.missingQuestions(),
                metadata.status(),
                entity.getUpdatedAt()
        );
    }

    private String metadata(ClusterDraft draft) {
        List<Long> fragmentIds = draft.fragments().stream()
                .map(ThoughtFragmentEntity::getId)
                .toList();
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "fragmentIds", fragmentIds,
                    "maturityScore", draft.maturityScore(),
                    "missingQuestions", draft.missingQuestions(),
                    "status", draft.status(),
                    "thesis", draft.thesis()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize cluster metadata", exception);
        }
    }

    private ClusterMetadata parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return ClusterMetadata.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            return new ClusterMetadata(
                    longList(node.path("fragmentIds")),
                    node.path("maturityScore").asInt(0),
                    stringList(node.path("missingQuestions")),
                    node.path("status").asText("active"),
                    node.path("thesis").asText(null)
            );
        } catch (Exception exception) {
            return ClusterMetadata.empty();
        }
    }

    private List<Long> longList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asLong)
                .toList();
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private record ClusterMetadata(
            List<Long> fragmentIds,
            int maturityScore,
            List<String> missingQuestions,
            String status,
            String thesis
    ) {
        static ClusterMetadata empty() {
            return new ClusterMetadata(List.of(), 0, List.of(), "active", null);
        }
    }
}
