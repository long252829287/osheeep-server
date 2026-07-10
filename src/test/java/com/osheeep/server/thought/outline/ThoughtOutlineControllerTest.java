package com.osheeep.server.thought.outline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.thought.cluster.ThoughtClusterFragmentMapper;
import com.osheeep.server.thought.cluster.ThoughtClusterMapper;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterEntity;
import com.osheeep.server.thought.cluster.entity.ThoughtClusterFragmentEntity;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import com.osheeep.server.thought.outline.entity.ThoughtOutlineEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class ThoughtOutlineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ThoughtClusterMapper clusterMapper;

    @Autowired
    private ThoughtClusterFragmentMapper clusterFragmentMapper;

    @Autowired
    private ThoughtFragmentMapper fragmentMapper;

    @Autowired
    private ThoughtOutlineMapper outlineMapper;

    @BeforeEach
    void setUp() {
        Mockito.reset(clusterMapper, clusterFragmentMapper, fragmentMapper, outlineMapper);
    }

    @Test
    void generateReturnsConflictWhenNoMatureClusterExists() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(post("/api/thoughts/outlines/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_ERROR"));
    }

    @Test
    void generateBuildsAndReturnsOutlineFromLatestMatureCluster() throws Exception {
        ThoughtClusterEntity matureCluster = new ThoughtClusterEntity();
        matureCluster.setId(10L);
        matureCluster.setUserId(42L);
        matureCluster.setTitle("写作");
        matureCluster.setSummary("持续记录能帮助形成清晰表达");
        matureCluster.setMetadata("{\"status\":\"mature\",\"missingQuestions\":[\"补充一个真实案例\"]}");
        matureCluster.setUpdatedAt(LocalDateTime.parse("2026-07-10T10:00:00"));

        ThoughtClusterEntity newerActiveCluster = new ThoughtClusterEntity();
        newerActiveCluster.setId(11L);
        newerActiveCluster.setUserId(42L);
        newerActiveCluster.setTitle("尚未成熟");
        newerActiveCluster.setMetadata("{\"status\":\"active\"}");
        newerActiveCluster.setUpdatedAt(LocalDateTime.parse("2026-07-10T11:00:00"));

        ThoughtClusterFragmentEntity link = new ThoughtClusterFragmentEntity();
        link.setFragmentId(101L);
        link.setPosition(0);

        ThoughtFragmentEntity fragment = new ThoughtFragmentEntity();
        fragment.setId(101L);
        fragment.setUserId(42L);
        fragment.setContent("记录能暴露思考中的空白");

        Mockito.when(clusterMapper.selectList(Mockito.any())).thenReturn(List.of(newerActiveCluster, matureCluster));
        Mockito.when(clusterFragmentMapper.selectList(Mockito.any())).thenReturn(List.of(link));
        Mockito.when(fragmentMapper.selectList(Mockito.any())).thenReturn(List.of(fragment));
        Mockito.when(outlineMapper.insert(Mockito.any(ThoughtOutlineEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ThoughtOutlineEntity.class).setId(90L);
            return 1;
        });

        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(post("/api/thoughts/outlines/generate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(90))
                .andExpect(jsonPath("$.data.clusterId").value(10))
                .andExpect(jsonPath("$.data.titleCandidates[0]").value("写作"))
                .andExpect(jsonPath("$.data.coreArgument").value("持续记录能帮助形成清晰表达"))
                .andExpect(jsonPath("$.data.outline").isArray())
                .andExpect(jsonPath("$.data.outline.length()").value(3))
                .andExpect(jsonPath("$.data.outline[0].title").value("问题背景"))
                .andExpect(jsonPath("$.data.supportingFragmentIds[0]").value(101))
                .andExpect(jsonPath("$.data.missingMaterials[0]").value("补充一个真实案例"));

        ArgumentCaptor<ThoughtOutlineEntity> outlineCaptor = ArgumentCaptor.forClass(ThoughtOutlineEntity.class);
        Mockito.verify(outlineMapper).insert(outlineCaptor.capture());
        assertThat(outlineCaptor.getValue().getClusterId()).isEqualTo(10L);
        assertThat(outlineCaptor.getValue().getContentJson()).contains("\"titleCandidates\"");
    }

    @Test
    void getReturnsCurrentUsersStoredOutline() throws Exception {
        ThoughtOutlineEntity outline = new ThoughtOutlineEntity();
        outline.setId(90L);
        outline.setUserId(42L);
        outline.setClusterId(10L);
        outline.setTitle("写作");
        outline.setContentJson("{\"titleCandidates\":[\"写作\",\"关于写作的思考\"],"
                + "\"coreArgument\":\"持续记录能帮助形成清晰表达\","
                + "\"outline\":[{\"title\":\"问题背景\",\"content\":\"背景\"}],"
                + "\"supportingFragmentIds\":[101],\"missingMaterials\":[]}");
        outline.setCreatedAt(LocalDateTime.parse("2026-07-10T10:00:00"));
        Mockito.when(outlineMapper.selectOne(Mockito.any())).thenReturn(outline);

        String token = jwtService.generateToken(new CurrentUser(42L, "long"));

        mockMvc.perform(get("/api/thoughts/outlines/90")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(90))
                .andExpect(jsonPath("$.data.outline[0].title").value("问题背景"))
                .andExpect(jsonPath("$.data.supportingFragmentIds[0]").value(101));
    }
}
