package com.osheeep.server.dinner.image;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import org.junit.jupiter.api.Test;
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
class DinnerImageStaticResourceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DinnerImageProperties properties;

    @Test
    void derivativeCanBeReadWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/media/recipes/tomato-with-egg-list.webp"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("image/webp")))
                .andExpect(content().bytes(org.springframework.util.StreamUtils.copyToByteArray(
                        getClass().getResourceAsStream(
                                "/static/media/recipes/tomato-with-egg-list.webp"))));
    }

    @Test
    void mediaPathDoesNotPermitUnauthenticatedWrites() throws Exception {
        mockMvc.perform(post("/media/recipes/tomato-with-egg-list.webp"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testProfileUsesTheConfiguredPublicBaseUrl() {
        assertThat(properties.publicBaseUrl()).isEqualTo("https://assets.test");
    }
}
