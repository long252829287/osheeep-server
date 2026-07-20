package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osheeep.server.common.security.CurrentUser;
import com.osheeep.server.common.security.JwtService;
import com.osheeep.server.dinner.recipe.dto.PublishRecipeRequest;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.SelectRecipeImageRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyGateway;
import com.osheeep.server.dinner.recipe.moderation.RecipeTextSafetyResult;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.StringUtils;

@ActiveProfiles("local")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
        initializers = DinnerCustomRecipeMySqlIT.DedicatedTestDatabaseInitializer.class)
public class DinnerCustomRecipeMySqlIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(DinnerCustomRecipeMySqlIT.class);
    private static final String SEEDED_ASSET_SHA256 =
            "0c9df553e9cc5ad1ae7e879dc753436ac60a89b8bb62eae70f2d02f18261e544";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @MockitoBean private RecipeTextSafetyGateway textSafetyGateway;

    private Long firstUserId;
    private Long secondUserId;
    private Long householdId;
    private boolean systemBaselinesRecorded;
    private int systemRecipeCountBefore;
    private int systemIngredientCountBefore;
    private int imageAssetCountBefore;
    private String firstUsername;
    private String secondUsername;
    private String firstOpenid;
    private String householdName;
    private String recipeName;

    @BeforeEach
    void requireDedicatedTestDatabaseBeforeAnyWriteAndSeedHousehold() {
        String expected = System.getenv("OSHEEEP_DB_TEST_NAME");
        assertThat(expected).as("OSHEEEP_DB_TEST_NAME safety gate").isNotBlank();
        String activeCatalog = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(activeCatalog)
                .as("write tests may only use the dedicated test catalog")
                .isEqualTo(expected);
        LOGGER.info("DinnerCustomRecipeMySqlIT dedicated catalog: {}", activeCatalog);

        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class))
                .as("the vertical slice must run on MySQL 8")
                .startsWith("8.");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history "
                                + "WHERE version = '6' AND success = 1",
                        Integer.class))
                .as("Flyway V6 must be recorded as successful")
                .isEqualTo(1);

        systemRecipeCountBefore = count(
                "SELECT COUNT(*) FROM dinner_recipes WHERE scope = 'SYSTEM'");
        systemIngredientCountBefore = count(
                "SELECT COUNT(*) FROM dinner_ingredients WHERE scope = 'SYSTEM'");
        imageAssetCountBefore = count("SELECT COUNT(*) FROM dinner_image_assets");
        systemBaselinesRecorded = true;

        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstUsername = "recipe_it_a_" + suffix;
        secondUsername = "recipe_it_b_" + suffix;
        firstOpenid = "recipe-it-a-" + suffix;
        String secondOpenid = "recipe-it-b-" + suffix;
        recipeName = "集成测试番茄炒蛋-" + suffix.substring(0, 8);

        firstUserId = insertUser(firstUsername, "测试成员甲");
        secondUserId = insertUser(secondUsername, "测试成员乙");
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                firstUserId, firstOpenid);
        jdbcTemplate.update(
                "INSERT INTO wechat_user_identities (user_id, openid) VALUES (?, ?)",
                secondUserId, secondOpenid);

        householdName = "recipe-it-household-" + suffix;
        householdId = insertHousehold(householdName, firstUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) VALUES (?, ?)",
                householdId, firstUserId);
        jdbcTemplate.update(
                "INSERT INTO dinner_household_members (household_id, user_id) VALUES (?, ?)",
                householdId, secondUserId);

        when(textSafetyGateway.check(anyString(), anyString(), anyString()))
                .thenReturn(RecipeTextSafetyResult.PASS);
    }

    @AfterEach
    void cleanUpOnlyTheSeededHouseholdAndUsers() {
        if (householdId == null
                && firstUserId == null
                && secondUserId == null
                && householdName == null
                && firstUsername == null
                && secondUsername == null) {
            return;
        }
        requireDedicatedCatalogAtRuntime();
        recoverGeneratedIdsForCleanup();
        if (householdId != null) {
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipe_method_steps WHERE method_id IN "
                            + "(SELECT id FROM dinner_recipe_methods WHERE recipe_id IN "
                            + "(SELECT id FROM dinner_recipes WHERE household_id = ?))",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipe_methods WHERE recipe_id IN "
                            + "(SELECT id FROM dinner_recipes WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_recipe_ingredients WHERE recipe_id IN "
                            + "(SELECT id FROM dinner_recipes WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_record_dish_snapshots WHERE record_id IN "
                            + "(SELECT id FROM dinner_cooking_records WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_cooking_records WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_actions WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_menu_selections WHERE menu_id IN "
                            + "(SELECT id FROM dinner_menus WHERE household_id = ?)",
                    householdId);
            jdbcTemplate.update("DELETE FROM dinner_menus WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_recipes WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_inventory WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_ingredients "
                            + "WHERE household_id = ? AND scope = 'HOUSEHOLD'",
                    householdId);
            jdbcTemplate.update("DELETE FROM dinner_invite_codes WHERE household_id = ?", householdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_members WHERE household_id = ?", householdId);
            jdbcTemplate.update("DELETE FROM dinner_households WHERE id = ?", householdId);
        }
        deleteUser(firstUserId);
        deleteUser(secondUserId);

        if (systemBaselinesRecorded) {
            assertThat(count("SELECT COUNT(*) FROM dinner_recipes WHERE scope = 'SYSTEM'"))
                    .as("integration cleanup must leave system recipes intact")
                    .isEqualTo(systemRecipeCountBefore);
            assertThat(count("SELECT COUNT(*) FROM dinner_ingredients WHERE scope = 'SYSTEM'"))
                    .as("integration cleanup must leave system ingredients intact")
                    .isEqualTo(systemIngredientCountBefore);
            assertThat(count("SELECT COUNT(*) FROM dinner_image_assets"))
                    .as("integration cleanup must leave approved image assets intact")
                    .isEqualTo(imageAssetCountBefore);
        }
    }

    @Test
    void publishesVersionSixRecipeThatBothHouseholdUsersCanListById() throws Exception {
        Long ingredientId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_ingredients "
                        + "WHERE scope = 'SYSTEM' AND status = 'ACTIVE' "
                        + "AND name = '盐' AND default_unit = '克'",
                Long.class);
        Long imageAssetId = jdbcTemplate.queryForObject(
                "SELECT id FROM dinner_image_assets "
                        + "WHERE sha256 = ? AND status = 'APPROVED'",
                Long.class, SEEDED_ASSET_SHA256);
        String firstToken = token(firstUserId, firstUsername);
        String secondToken = token(secondUserId, secondUsername);

        JsonNode created = perform(firstToken, post("/api/dinner/recipes/drafts"), null);
        long recipeId = created.at("/data/id").asLong();
        assertVersionAndStatus(created, 1, "DRAFT");

        JsonNode basic = perform(
                firstToken,
                put("/api/dinner/recipes/{id}/basic-info", recipeId),
                new UpdateRecipeBasicInfoRequest(
                        1L, recipeName, "家常菜", "酸甜", 2, 15));
        assertVersionAndStatus(basic, 2, "DRAFT");

        JsonNode ingredients = perform(
                firstToken,
                put("/api/dinner/recipes/{id}/ingredients", recipeId),
                new ReplaceRecipeIngredientsRequest(
                        2L, List.of(new RecipeIngredientInput(
                                ingredientId, null, "克", true))));
        assertVersionAndStatus(ingredients, 3, "DRAFT");
        assertThat(ingredients.at("/data/ingredients/0/quantity").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT quantity IS NULL FROM dinner_recipe_ingredients "
                                + "WHERE recipe_id = ? AND ingredient_id = ?",
                        Boolean.class, recipeId, ingredientId))
                .isTrue();

        JsonNode method = perform(
                firstToken,
                put("/api/dinner/recipes/{id}/default-method", recipeId),
                new UpdateDefaultMethodRequest(
                        3L,
                        "家常炒",
                        "炒",
                        List.of(
                                new RecipeMethodStepInput("切好食材"),
                                new RecipeMethodStepInput("热锅翻炒至熟"))));
        assertVersionAndStatus(method, 4, "DRAFT");
        assertThat(method.at("/data/defaultMethod/steps/0/instruction").asText())
                .isEqualTo("切好食材");

        JsonNode image = perform(
                firstToken,
                put("/api/dinner/recipes/{id}/image", recipeId),
                new SelectRecipeImageRequest(4L, imageAssetId));
        assertVersionAndStatus(image, 5, "DRAFT");
        assertThat(image.at("/data/image/sourcePageUrl").asText())
                .isEqualTo("https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg");
        assertThat(image.at("/data/image/licenseName").asText()).isEqualTo("CC0 1.0");

        mockMvc.perform(get("/api/dinner/recipes/{id}", recipeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        JsonNode published = perform(
                firstToken,
                post("/api/dinner/recipes/{id}/publish", recipeId),
                new PublishRecipeRequest(5L));
        assertVersionAndStatus(published, 6, "PUBLISHED");
        verify(textSafetyGateway).check(
                eq(firstOpenid), eq(recipeName), contains("1. 切好食材"));
        var publishedRow = jdbcTemplate.queryForMap(
                "SELECT status, version, published_at FROM dinner_recipes WHERE id = ?",
                recipeId);
        assertThat(publishedRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) publishedRow.get("version")).longValue()).isEqualTo(6L);
        assertThat(publishedRow.get("published_at")).isNotNull();

        JsonNode firstList = perform(
                firstToken,
                get("/api/dinner/recipes/family").queryParam("tab", "PUBLISHED"),
                null);
        JsonNode secondList = perform(
                secondToken,
                get("/api/dinner/recipes/family").queryParam("tab", "PUBLISHED"),
                null);
        assertPublishedRecipe(firstList, recipeId);
        assertPublishedRecipe(secondList, recipeId);

        JsonNode partnerDetail = perform(
                secondToken, get("/api/dinner/recipes/{id}", recipeId), null);
        assertVersionAndStatus(partnerDetail, 6, "PUBLISHED");
        assertThat(partnerDetail.at("/data/ingredients/0/quantity").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_image_assets WHERE id = ?",
                        Integer.class, imageAssetId))
                .isOne();
    }

    private JsonNode perform(
            String token,
            MockHttpServletRequestBuilder request,
            Object body
    ) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(body));
        }
        String responseBody = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode response = objectMapper.readTree(responseBody);
        assertThat(response.path("success").asBoolean()).isTrue();
        return response;
    }

    private void assertVersionAndStatus(JsonNode response, long version, String status) {
        assertThat(response.at("/data/version").asLong()).isEqualTo(version);
        assertThat(response.at("/data/status").asText()).isEqualTo(status);
    }

    private void assertPublishedRecipe(JsonNode response, long recipeId) {
        JsonNode recipe = StreamSupport.stream(response.path("data").spliterator(), false)
                .filter(item -> item.path("id").asLong() == recipeId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "published household recipe was not visible by id " + recipeId));
        assertThat(recipe.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(recipe.path("version").asLong()).isEqualTo(6L);
        assertThat(recipe.path("name").asText()).isEqualTo(recipeName);
    }

    private Long insertUser(String username, String displayName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO users (username, display_name, status) "
                            + "VALUES (?, ?, 'ACTIVE')",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, displayName);
            return statement;
        }, keyHolder);
        assertThat(keyHolder.getKey()).as("inserted user id").isNotNull();
        return keyHolder.getKey().longValue();
    }

    private Long insertHousehold(String householdName, Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dinner_households (name, status, created_by) "
                            + "VALUES (?, 'ACTIVE', ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, householdName);
            statement.setLong(2, createdBy);
            return statement;
        }, keyHolder);
        assertThat(keyHolder.getKey()).as("inserted household id").isNotNull();
        return keyHolder.getKey().longValue();
    }

    private String token(Long userId, String username) {
        return jwtService.generateToken(new CurrentUser(userId, username));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private void deleteUser(Long userId) {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM wechat_user_identities WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    private void requireDedicatedCatalogAtRuntime() {
        String expected = System.getenv("OSHEEEP_DB_TEST_NAME");
        assertThat(expected).as("OSHEEEP_DB_TEST_NAME cleanup safety gate").isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .as("cleanup may only use the dedicated test catalog")
                .isEqualTo(expected);
    }

    private void recoverGeneratedIdsForCleanup() {
        if (householdId == null && householdName != null) {
            householdId = jdbcTemplate.query(
                            "SELECT id FROM dinner_households WHERE name = ?",
                            (resultSet, rowNumber) -> resultSet.getLong(1),
                            householdName)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
        if (firstUserId == null && firstUsername != null) {
            firstUserId = findUserId(firstUsername);
        }
        if (secondUserId == null && secondUsername != null) {
            secondUserId = findUserId(secondUsername);
        }
    }

    private Long findUserId(String username) {
        return jdbcTemplate.query(
                        "SELECT id FROM users WHERE username = ?",
                        (resultSet, rowNumber) -> resultSet.getLong(1),
                        username)
                .stream()
                .findFirst()
                .orElse(null);
    }

    public static final class DedicatedTestDatabaseInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static final String FAILURE_MESSAGE =
                "Dinner custom recipe integration test requires an explicit dedicated test database";

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            var environment = applicationContext.getEnvironment();
            String selectedDatabase = environment.getProperty("OSHEEEP_DB_NAME");
            String testDatabase = environment.getProperty("OSHEEEP_DB_TEST_NAME");
            boolean localProfile = Arrays.asList(environment.getActiveProfiles()).contains("local");
            if (!localProfile
                    || !StringUtils.hasText(selectedDatabase)
                    || !StringUtils.hasText(testDatabase)
                    || !selectedDatabase.equals(testDatabase)) {
                throw new IllegalStateException(FAILURE_MESSAGE);
            }
        }
    }
}
