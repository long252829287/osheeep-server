package com.osheeep.server.thought.outline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.ai.AiOutlineGenerator;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.thought.cluster.ThoughtClusterFragmentMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterMapper;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterEntity;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterFragmentEntity;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import com.osheeep.server.thought.outline.dto.OutlineSectionResponse;
import com.osheeep.server.thought.outline.dto.ThoughtOutlineResponse;
import com.osheeep.server.thought.outline.entity.ThoughtOutlineEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThoughtOutlineService {

    private final ThoughtClusterMapper clusterMapper;
    private final ThoughtClusterFragmentMapper clusterFragmentMapper;
    private final ThoughtFragmentMapper fragmentMapper;
    private final ThoughtOutlineMapper outlineMapper;
    private final AiOutlineGenerator outlineGenerator;
    private final ObjectMapper objectMapper;

    public ThoughtOutlineService(
            ThoughtClusterMapper clusterMapper,
            ThoughtClusterFragmentMapper clusterFragmentMapper,
            ThoughtFragmentMapper fragmentMapper,
            ThoughtOutlineMapper outlineMapper,
            AiOutlineGenerator outlineGenerator,
            ObjectMapper objectMapper
    ) {
        this.clusterMapper = clusterMapper;
        this.clusterFragmentMapper = clusterFragmentMapper;
        this.fragmentMapper = fragmentMapper;
        this.outlineMapper = outlineMapper;
        this.outlineGenerator = outlineGenerator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ThoughtOutlineResponse generate(Long userId) {
        ThoughtClusterEntity cluster = latestMatureCluster(userId);
        ClusterMetadata metadata = clusterMetadata(cluster);
        List<Long> fragmentIds = fragmentIds(userId, cluster.getId());
        List<String> fragmentContents = fragments(userId, fragmentIds).stream()
                .map(ThoughtFragmentEntity::getContent)
                .toList();
        AiOutlineGenerator.OutlineDraft draft = outlineGenerator.generate(new AiOutlineGenerator.OutlineSource(
                cluster.getTitle(),
                cluster.getSummary(),
                fragmentIds,
                fragmentContents,
                metadata.missingQuestions()
        ));

        LocalDateTime now = LocalDateTime.now();
        ThoughtOutlineEntity outline = new ThoughtOutlineEntity();
        outline.setUserId(userId);
        outline.setClusterId(cluster.getId());
        outline.setTitle(draft.titleCandidates().getFirst());
        outline.setContentJson(serialize(draft));
        outline.setStatus("DRAFT");
        outline.setCreatedAt(now);
        outline.setUpdatedAt(now);
        outlineMapper.insert(outline);

        return toResponse(outline, toStoredOutline(draft));
    }

    public ThoughtOutlineResponse get(Long userId, Long outlineId) {
        ThoughtOutlineEntity outline = outlineMapper.selectOne(Wrappers.lambdaQuery(ThoughtOutlineEntity.class)
                .eq(ThoughtOutlineEntity::getId, outlineId)
                .eq(ThoughtOutlineEntity::getUserId, userId)
                .isNull(ThoughtOutlineEntity::getDeletedAt));
        if (outline == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "文章大纲不存在或无权访问");
        }
        return toResponse(outline, parseStoredOutline(outline.getContentJson()));
    }

    private ThoughtClusterEntity latestMatureCluster(Long userId) {
        return clusterMapper.selectList(Wrappers.lambdaQuery(ThoughtClusterEntity.class)
                        .eq(ThoughtClusterEntity::getUserId, userId)
                        .isNull(ThoughtClusterEntity::getDeletedAt)
                        .orderByDesc(ThoughtClusterEntity::getUpdatedAt)
                        .orderByDesc(ThoughtClusterEntity::getId))
                .stream()
                .filter(cluster -> "mature".equals(clusterMetadata(cluster).status()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.BUSINESS_ERROR,
                        "请先积累并重建至少一个成熟主题簇"
                ));
    }

    private List<Long> fragmentIds(Long userId, Long clusterId) {
        return clusterFragmentMapper.selectList(Wrappers.lambdaQuery(ThoughtClusterFragmentEntity.class)
                        .eq(ThoughtClusterFragmentEntity::getUserId, userId)
                        .eq(ThoughtClusterFragmentEntity::getClusterId, clusterId)
                        .orderByAsc(ThoughtClusterFragmentEntity::getPosition)
                        .orderByAsc(ThoughtClusterFragmentEntity::getFragmentId))
                .stream()
                .map(ThoughtClusterFragmentEntity::getFragmentId)
                .toList();
    }

    private List<ThoughtFragmentEntity> fragments(Long userId, List<Long> fragmentIds) {
        if (fragmentIds.isEmpty()) {
            return List.of();
        }
        Map<Long, ThoughtFragmentEntity> fragmentsById = fragmentMapper.selectList(
                        new QueryWrapper<ThoughtFragmentEntity>()
                                .eq("user_id", userId)
                                .in("id", fragmentIds)
                                .isNull("deleted_at"))
                .stream()
                .collect(Collectors.toMap(ThoughtFragmentEntity::getId, Function.identity()));
        return fragmentIds.stream()
                .map(fragmentsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ClusterMetadata clusterMetadata(ThoughtClusterEntity cluster) {
        if (cluster.getMetadata() == null || cluster.getMetadata().isBlank()) {
            return ClusterMetadata.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(cluster.getMetadata());
            return new ClusterMetadata(
                    node.path("status").asText("active"),
                    stringList(node.path("missingQuestions"))
            );
        } catch (JsonProcessingException exception) {
            return ClusterMetadata.empty();
        }
    }

    private String serialize(AiOutlineGenerator.OutlineDraft draft) {
        try {
            return objectMapper.writeValueAsString(toStoredOutline(draft));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize thought outline", exception);
        }
    }

    private StoredOutline toStoredOutline(AiOutlineGenerator.OutlineDraft draft) {
        return new StoredOutline(
                draft.titleCandidates(),
                draft.coreArgument(),
                draft.outline().stream()
                        .map(section -> new OutlineSectionResponse(section.title(), section.content()))
                        .toList(),
                draft.supportingFragmentIds(),
                draft.missingMaterials()
        );
    }

    private StoredOutline parseStoredOutline(String contentJson) {
        try {
            return objectMapper.readValue(contentJson, StoredOutline.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse thought outline", exception);
        }
    }

    private ThoughtOutlineResponse toResponse(ThoughtOutlineEntity entity, StoredOutline outline) {
        return new ThoughtOutlineResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getClusterId(),
                outline.titleCandidates(),
                outline.coreArgument(),
                outline.outline(),
                outline.supportingFragmentIds(),
                outline.missingMaterials(),
                entity.getCreatedAt()
        );
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private record ClusterMetadata(String status, List<String> missingQuestions) {
        static ClusterMetadata empty() {
            return new ClusterMetadata("active", List.of());
        }
    }

    private record StoredOutline(
            List<String> titleCandidates,
            String coreArgument,
            List<OutlineSectionResponse> outline,
            List<Long> supportingFragmentIds,
            List<String> missingMaterials
    ) {
    }
}
