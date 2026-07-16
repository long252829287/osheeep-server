package com.osheeep.server.dinner.image;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.osheeep.server.TestUserMapperConfig;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestUserMapperConfig.class)
class DinnerImageAssetControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerImageAssetService imageAssetService;

    private String token;

    @BeforeEach
    void setUp() {
        reset(imageAssetService);
        token = jwtService.generateToken(new CurrentUser(7L, "wx_user"));
    }

    @Test
    void imageSearchRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/dinner/image-assets").queryParam("query", "番茄"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        verifyNoInteractions(imageAssetService);
    }

    @Test
    void authenticatedSearchReturnsApprovedAssetContract() throws Exception {
        when(imageAssetService.search("番茄")).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/dinner/image-assets")
                        .queryParam("query", "番茄")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].displayName").value("番茄炒鸡蛋"))
                .andExpect(jsonPath("$.data[0].listUrl").value(
                        "https://assets.test/media/recipes/tomato-with-egg-list.webp"))
                .andExpect(jsonPath("$.data[0].detailUrl").value(
                        "https://assets.test/media/recipes/tomato-with-egg-detail.webp"))
                .andExpect(jsonPath("$.data[0].sourcePageUrl").value(
                        "https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg"))
                .andExpect(jsonPath("$.data[0].licenseName").value("CC0 1.0"))
                .andExpect(jsonPath("$.data[0].originalObjectKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].originalFileUrl").doesNotExist());

        verify(imageAssetService).search("番茄");
    }

    private ImageAssetResponse response() {
        return new ImageAssetResponse(
                9L, "番茄炒鸡蛋",
                "https://assets.test/media/recipes/tomato-with-egg-list.webp",
                "https://assets.test/media/recipes/tomato-with-egg-detail.webp",
                "https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg",
                "Kaap bij Sneeuw", "CC0 1.0",
                "https://creativecommons.org/publicdomain/zero/1.0/",
                LocalDate.of(2026, 7, 16), 1198, 1091);
    }
}
