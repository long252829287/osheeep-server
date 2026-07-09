package com.osheeep.server.thought.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterEntity;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterFragmentEntity;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        TestUserMapperConfig.class
})
class ThoughtClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ThoughtClusterMapper clusterMapper;

    @Autowired
    private ThoughtClusterFragmentMapper clusterFragmentMapper;

    @Autowired
    private ThoughtFragmentMapper fragmentMapper;

    @Autowired
    private ThoughtClusterService clusterService;

    @BeforeEach
    void setUp() {
        reset(clusterMapper, clusterFragmentMapper, fragmentMapper);
    }

    @Test
    void listClustersReturnsCurrentUsersClusters() throws Exception {
        when(clusterMapper.selectList(any())).thenReturn(List.of(cluster(
                10L,
                42L,
                "写作",
                "{\"fragmentIds\":[1,2,3],\"maturityScore\":60,"
                        + "\"missingQuestions\":[],\"status\":\"active\","
                        + "\"thesis\":\"围绕写作的 3 条碎片\"}"
        )));
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(get("/api/thoughts/clusters")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].userId").value(42))
                .andExpect(jsonPath("$.data[0].title").value("写作"))
                .andExpect(jsonPath("$.data[0].fragmentIds[0]").value(1))
                .andExpect(jsonPath("$.data[0].maturityScore").value(60))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    void rebuildClustersGroupsFragmentsByThemeAndStoresClusters() throws Exception {
        when(fragmentMapper.selectList(any())).thenReturn(List.of(
                fragment(1L, 42L, "写作素材一", "{\"theme\":\"写作\"}"),
                fragment(2L, 42L, "写作素材二", "{\"theme\":\"写作\"}"),
                fragment(3L, 42L, "写作素材三", "{\"theme\":\"写作\"}"),
                fragment(4L, 42L, "无主题素材", "{}")
        ));

        List<Long> generatedIds = new ArrayList<>(List.of(100L, 101L));
        when(clusterMapper.insert(any(ThoughtClusterEntity.class))).thenAnswer(invocation -> {
            ThoughtClusterEntity entity = invocation.getArgument(0);
            entity.setId(generatedIds.remove(0));
            return 1;
        });
        when(clusterFragmentMapper.insert(any(ThoughtClusterFragmentEntity.class))).thenReturn(1);

        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(post("/api/thoughts/clusters/rebuild")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value("42"))
                .andExpect(jsonPath("$.data.status").value("completed"));

        ArgumentCaptor<ThoughtClusterEntity> clusterCaptor = ArgumentCaptor.forClass(ThoughtClusterEntity.class);
        verify(clusterMapper, org.mockito.Mockito.times(2)).insert(clusterCaptor.capture());
        verify(clusterFragmentMapper).delete(any());
        List<ThoughtClusterEntity> insertedClusters = clusterCaptor.getAllValues();
        assertThat(insertedClusters).extracting(ThoughtClusterEntity::getTitle)
                .containsExactlyInAnyOrder("写作", "未归组");

        ThoughtClusterEntity writingCluster = insertedClusters.stream()
                .filter(item -> "写作".equals(item.getTitle()))
                .findFirst()
                .orElseThrow();
        JsonNode writingMetadata = objectMapper.readTree(writingCluster.getMetadata());
        assertThat(writingMetadata.path("fragmentIds")).hasSize(3);
        assertThat(writingMetadata.path("maturityScore").asInt()).isEqualTo(60);
        assertThat(writingMetadata.path("status").asText()).isEqualTo("active");

        ThoughtClusterEntity ungroupedCluster = insertedClusters.stream()
                .filter(item -> "未归组".equals(item.getTitle()))
                .findFirst()
                .orElseThrow();
        JsonNode ungroupedMetadata = objectMapper.readTree(ungroupedCluster.getMetadata());
        assertThat(ungroupedMetadata.path("maturityScore").asInt()).isEqualTo(20);
        assertThat(ungroupedMetadata.path("status").asText()).isEqualTo("forming");
        assertThat(ungroupedMetadata.path("missingQuestions").get(0).asText())
                .isEqualTo("继续补充论据和例子");

        ArgumentCaptor<ThoughtClusterFragmentEntity> linkCaptor =
                ArgumentCaptor.forClass(ThoughtClusterFragmentEntity.class);
        verify(clusterFragmentMapper, org.mockito.Mockito.times(4)).insert(linkCaptor.capture());
        assertThat(linkCaptor.getAllValues()).extracting(ThoughtClusterFragmentEntity::getUserId)
                .containsOnly(42L);
    }

    @Test
    void rebuildIsTransactional() throws Exception {
        var method = ThoughtClusterService.class.getMethod("rebuild", Long.class);
        assertThat(AnnotatedElementUtils.hasAnnotation(method, Transactional.class)).isTrue();
        assertThat(clusterService).isNotNull();
    }

    private ThoughtClusterEntity cluster(Long id, Long userId, String title, String metadata) {
        ThoughtClusterEntity entity = new ThoughtClusterEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setSummary("summary");
        entity.setMetadata(metadata);
        entity.setUpdatedAt(LocalDateTime.parse("2026-07-09T10:00:00"));
        return entity;
    }

    private ThoughtFragmentEntity fragment(Long id, Long userId, String content, String metadata) {
        ThoughtFragmentEntity entity = new ThoughtFragmentEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setContent(content);
        entity.setMetadata(metadata);
        return entity;
    }
}
