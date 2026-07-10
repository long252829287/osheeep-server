package com.osheeep.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.thought.fragment.ThoughtFragmentMapper;
import com.osheeep.server.thought.fragment.entity.ThoughtFragmentEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("local")
@SpringBootTest
@AutoConfigureMockMvc
class FrontendContractSmokeIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ThoughtFragmentMapper fragmentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM jobs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM thought_outlines WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM thought_cluster_fragments WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM thought_clusters WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM thought_fragments WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void userCanBuildClustersAndGenerateOutlineAcrossFrontendContract() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String email = "smoke-" + suffix + "@example.com";
        String username = "smoke" + suffix;
        String password = "smoke-password-123";

        JsonNode registered = data(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"username\":\"" + username
                                + "\",\"password\":\"" + password + "\",\"displayName\":\"Smoke Test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
        userId = registered.path("user").path("id").asLong();
        assertThat(userId).isPositive();

        JsonNode loggedIn = data(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
        String accessToken = loggedIn.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(userId));

        seedFragments(userId);

        mockMvc.perform(post("/api/thoughts/clusters/rebuild")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.jobId").isNotEmpty());

        JsonNode clusters = data(mockMvc.perform(get("/api/thoughts/clusters")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());
        assertThat(clusters.size()).isEqualTo(1);
        JsonNode cluster = clusters.get(0);
        assertThat(cluster.path("status").asText()).isEqualTo("mature");
        assertThat(cluster.path("maturityScore").asInt()).isEqualTo(80);

        JsonNode outline = data(mockMvc.perform(post("/api/thoughts/outlines/generate")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clusterId").value(cluster.path("id").asLong()))
                .andExpect(jsonPath("$.data.outline.length()").value(3))
                .andReturn());
        long outlineId = outline.path("id").asLong();
        assertThat(outlineId).isPositive();

        mockMvc.perform(get("/api/thoughts/outlines/" + outlineId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(outlineId));
    }

    private void seedFragments(Long targetUserId) {
        List<String> contents = List.of(
                "持续记录可以让写作主题逐渐形成结构。",
                "相同主题的片段需要足够多，才适合沉淀为文章。",
                "写作时应把具体经历转成能验证的论据。",
                "完成一轮聚类后，可以继续补充反例和行动。"
        );
        for (String content : contents) {
            ThoughtFragmentEntity fragment = new ThoughtFragmentEntity();
            fragment.setUserId(targetUserId);
            fragment.setContent(content);
            fragment.setSource("e2e-smoke");
            fragment.setMetadata("{\"theme\":\"写作\"}");
            fragmentMapper.insert(fragment);
        }
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
