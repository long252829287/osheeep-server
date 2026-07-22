package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
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
import com.osheeep.server.dinner.menu.dto.MenuActionRequest;
import com.osheeep.server.dinner.menu.dto.UpdateSelectionsRequest;
import com.osheeep.server.dinner.recipe.dto.PublishRecipeRequest;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.SelectRecipeImageRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyGateway;
import com.osheeep.server.dinner.moderation.DinnerTextSafetyResult;
import com.osheeep.server.dinner.record.entity.DinnerRecordDishSnapshotEntity;
import com.osheeep.server.dinner.record.mapper.DinnerRecordDishSnapshotMapper;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ActiveProfiles("local")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
        initializers = DinnerCustomRecipeTestDatabaseSafetyInitializer.class)
@Import(DinnerCustomRecipeMySqlIT.FlywaySafetyConfiguration.class)
public class DinnerCustomRecipeMySqlIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(DinnerCustomRecipeMySqlIT.class);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtService jwtService;
    @MockitoBean private DinnerTextSafetyGateway textSafetyGateway;
    @MockitoSpyBean private DinnerRecordDishSnapshotMapper snapshotMapper;

    private Long firstUserId;
    private Long secondUserId;
    private Long householdId;
    private Long householdIngredientId;
    private Long foreignHouseholdId;
    private Long firstImageAssetId;
    private Long secondImageAssetId;
    private boolean systemBaselinesRecorded;
    private int systemRecipeCountBefore;
    private int systemIngredientCountBefore;
    private int imageAssetCountBefore;
    private String firstUsername;
    private String secondUsername;
    private String firstOpenid;
    private String householdName;
    private String householdIngredientName;
    private String recipeName;
    private String firstImageSha256;
    private String secondImageSha256;
    private String firstListObjectKey;
    private String firstDetailObjectKey;
    private String secondListObjectKey;
    private String secondDetailObjectKey;
    private final Set<Long> trackedRecipeIds = new LinkedHashSet<>();
    private final Set<Long> trackedMethodIds = new LinkedHashSet<>();
    private final Set<Long> trackedImageAssetIds = new LinkedHashSet<>();

    @BeforeEach
    void requireDedicatedTestDatabaseBeforeAnyWriteAndSeedHousehold() {
        String expected = System.getenv("OSHEEEP_DB_TEST_NAME");
        assertThat(expected).as("OSHEEEP_DB_TEST_NAME safety gate").isNotBlank();
        assertThat(System.getenv("OSHEEEP_DB_NAME"))
                .as("raw selected database must equal the dedicated test database")
                .isEqualTo(expected);
        String activeCatalog = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(activeCatalog)
                .as("write tests may only use the dedicated test catalog")
                .isEqualTo(expected);
        LOGGER.info("DinnerCustomRecipeMySqlIT dedicated catalog: {}", activeCatalog);

        assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class))
                .as("the vertical slice must run on MySQL 8")
                .startsWith("8.");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                        String.class))
                .as("the latest successful Flyway migration must be V7")
                .isEqualTo("7");

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
        firstImageSha256 = suffix + "1".repeat(32);
        secondImageSha256 = suffix + "2".repeat(32);
        firstListObjectKey = "integration/" + suffix + "/first-list.webp";
        firstDetailObjectKey = "integration/" + suffix + "/first-detail.webp";
        secondListObjectKey = "integration/" + suffix + "/second-list.webp";
        secondDetailObjectKey = "integration/" + suffix + "/second-detail.webp";

        firstImageAssetId = insertApprovedImageAsset(
                "集成测试图片一", firstImageSha256,
                firstListObjectKey, firstDetailObjectKey);
        secondImageAssetId = insertApprovedImageAsset(
                "集成测试图片二", secondImageSha256,
                secondListObjectKey, secondDetailObjectKey);

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
        householdIngredientName = "集成测试鸡蛋-" + suffix.substring(0, 8);
        householdIngredientId = insertHouseholdIngredient(householdIngredientName);

        when(textSafetyGateway.check(anyString(), anyString(), anyString()))
                .thenReturn(DinnerTextSafetyResult.PASS);
    }

    @AfterEach
    void cleanUpOnlyTheSeededHouseholdAndUsers() {
        reset(snapshotMapper);
        if (householdId == null
                && firstUserId == null
                && secondUserId == null
                && firstImageAssetId == null
                && secondImageAssetId == null
                && householdName == null
                && firstUsername == null
                && secondUsername == null) {
            return;
        }
        requireDedicatedCatalogAtRuntime();
        recoverGeneratedIdsForCleanup();
        if (householdId != null) {
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

            deleteTrackedRows(
                    "dinner_recipe_method_steps", "method_id", trackedMethodIds);
            deleteTrackedRows("dinner_recipe_methods", "id", trackedMethodIds);
            if (!trackedRecipeIds.isEmpty()) {
                jdbcTemplate.update(
                        "DELETE FROM dinner_recipe_method_steps WHERE method_id IN "
                                + "(SELECT id FROM dinner_recipe_methods WHERE recipe_id IN ("
                                + placeholders(trackedRecipeIds.size()) + "))",
                        trackedRecipeIds.toArray());
                jdbcTemplate.update(
                        "DELETE FROM dinner_recipe_methods WHERE recipe_id IN ("
                                + placeholders(trackedRecipeIds.size()) + ")",
                        trackedRecipeIds.toArray());
                jdbcTemplate.update(
                        "DELETE FROM dinner_recipe_ingredients WHERE recipe_id IN ("
                                + placeholders(trackedRecipeIds.size()) + ")",
                        trackedRecipeIds.toArray());
            }
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
            jdbcTemplate.update("DELETE FROM dinner_menus WHERE household_id = ?", householdId);
            deleteTrackedRows("dinner_recipes", "id", trackedRecipeIds);
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
        if (foreignHouseholdId != null) {
            jdbcTemplate.update(
                    "DELETE FROM dinner_household_members WHERE household_id = ?",
                    foreignHouseholdId);
            jdbcTemplate.update(
                    "DELETE FROM dinner_households WHERE id = ?",
                    foreignHouseholdId);
        }
        deleteTrackedRows("dinner_image_assets", "id", trackedImageAssetIds);
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
    void twoUsersCompleteOneImmutableHouseholdRecipeSnapshot() throws Exception {
        String firstToken = token(firstUserId, firstUsername);
        String secondToken = token(secondUserId, secondUsername);
        PublishedRecipe recipe = publishFamilyRecipe(firstToken);
        addHouseholdInventory(recipe.ingredientId());

        JsonNode firstDiscovered = discoveredRecipe(firstToken, recipe.recipeId());
        JsonNode secondDiscovered = discoveredRecipe(secondToken, recipe.recipeId());
        assertDiscoveredHouseholdRecipe(firstDiscovered, recipe);
        assertDiscoveredHouseholdRecipe(secondDiscovered, recipe);
        assertThat(secondDiscovered).isEqualTo(firstDiscovered);

        JsonNode firstToday = perform(firstToken, get("/api/dinner/menus/today"), null);
        perform(
                firstToken,
                put("/api/dinner/menus/today/selections"),
                new UpdateSelectionsRequest(
                        List.of(recipe.recipeId()),
                        firstToday.at("/data/version").asLong()));
        JsonNode secondToday = perform(secondToken, get("/api/dinner/menus/today"), null);
        JsonNode bothSelected = perform(
                secondToken,
                put("/api/dinner/menus/today/selections"),
                new UpdateSelectionsRequest(
                        List.of(recipe.recipeId()),
                        secondToday.at("/data/version").asLong()));
        long menuId = bothSelected.at("/data/id").asLong();
        JsonNode mergedDish = bothSelected.at("/data/dishes/0");
        assertThat(mergedDish.path("source").asText()).isEqualTo("BOTH");
        assertThat(mergedDish.path("recipeVersion").asLong()).isEqualTo(6L);
        assertThat(mergedDish.at("/method/id").asLong()).isEqualTo(recipe.methodId());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_menu_selections "
                                + "WHERE menu_id = ? AND recipe_id = ? "
                                + "AND recipe_version = 6 AND method_id = ?",
                        Integer.class,
                        menuId,
                        recipe.recipeId(),
                        recipe.methodId()))
                .isEqualTo(2);

        String confirmationKey = UUID.randomUUID().toString();
        JsonNode confirmed = perform(
                firstToken,
                post("/api/dinner/menus/today/confirm"),
                new MenuActionRequest(
                        bothSelected.at("/data/version").asLong(),
                        confirmationKey));
        String completionKey = UUID.randomUUID().toString();
        JsonNode completed = perform(
                secondToken,
                post("/api/dinner/menus/today/complete"),
                new MenuActionRequest(
                        confirmed.at("/data/version").asLong(),
                        completionKey));
        long recordId = completed.at("/data/recordId").asLong();
        JsonNode detailBefore = perform(
                firstToken, get("/api/dinner/records/{id}", recordId), null);
        JsonNode dishBefore = detailBefore.at("/data/dishes/0");
        byte[] dishBytesBefore = objectMapper.writeValueAsBytes(dishBefore);

        assertThat(dishBefore.path("source").asText()).isEqualTo("BOTH");
        assertThat(dishBefore.path("scope").asText()).isEqualTo("HOUSEHOLD");
        assertThat(dishBefore.path("recipeVersion").asLong()).isEqualTo(6L);
        assertThat(dishBefore.path("imagePath").asText()).isEqualTo(recipe.listUrl());
        assertThat(dishBefore.at("/method/steps/0/instruction").asText())
                .isEqualTo("切好食材");
        assertThat(dishBefore.at("/method/steps/0/sortOrder").asInt()).isZero();
        assertThat(dishBefore.at("/method/steps/1/instruction").asText())
                .isEqualTo("热锅翻炒至熟");
        assertThat(dishBefore.at("/method/steps/1/sortOrder").asInt()).isEqualTo(1);
        assertThat(dishBefore.at("/ingredients/0/ingredientId").asLong())
                .isEqualTo(recipe.ingredientId());
        assertThat(dishBefore.at("/ingredients/0/name").asText())
                .isEqualTo(householdIngredientName);
        assertThat(dishBefore.at("/ingredients/0/quantity").isNull()).isTrue();
        assertThat(dishBefore.at("/ingredients/0/unit").asText()).isEqualTo("枚");
        assertThat(dishBefore.at("/ingredients/0/required").asBoolean()).isTrue();
        assertThat(dishBefore.at("/ingredients/0/sortOrder").asInt()).isZero();

        mutatePublishedAggregate(recipe);

        JsonNode detailAfter = perform(
                secondToken, get("/api/dinner/records/{id}", recordId), null);
        assertThat(objectMapper.writeValueAsBytes(detailAfter.at("/data/dishes/0")))
                .isEqualTo(dishBytesBefore);
        assertThat(detailAfter.at("/data/dishes/0/imagePath").asText())
                .isEqualTo(recipe.listUrl());

        JsonNode repeated = perform(
                firstToken,
                post("/api/dinner/menus/today/complete"),
                new MenuActionRequest(
                        completed.at("/data/menu/version").asLong(),
                        completionKey));
        assertThat(repeated.at("/data/recordId").asLong()).isEqualTo(recordId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_cooking_records WHERE menu_id = ?",
                        Integer.class,
                        menuId))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_record_dish_snapshots WHERE record_id = ?",
                        Integer.class,
                        recordId))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'CONFIRM' "
                                + "AND idempotency_key = ?",
                        Integer.class,
                        menuId,
                        confirmationKey))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'COMPLETE' "
                                + "AND idempotency_key = ?",
                        Integer.class,
                        menuId,
                        completionKey))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'COMPLETE'",
                        Integer.class,
                        menuId))
                .isOne();
        verify(textSafetyGateway).check(
                eq(firstOpenid), eq(recipeName), contains("1. 切好食材"));
    }

    @Test
    void tamperedSelectionVersionRejectsCompletionBeforeRecordCreation() throws Exception {
        String token = token(firstUserId, firstUsername);
        PublishedRecipe recipe = publishFamilyRecipe(token);
        ConfirmedMenu menu = confirmFamilyMenu(token, recipe.recipeId());

        try {
            jdbcTemplate.update(
                    "UPDATE dinner_menu_selections SET recipe_version = 7 WHERE menu_id = ?",
                    menu.menuId());
            assertCompletionInvalid(token, menu);
            assertNoCompletionWrites(menu.menuId());
        } finally {
            jdbcTemplate.update(
                    "UPDATE dinner_menu_selections SET recipe_version = 6 WHERE menu_id = ?",
                    menu.menuId());
        }
    }

    @Test
    void tamperedMethodRecipeRejectsCompletionBeforeRecordCreation() throws Exception {
        String token = token(firstUserId, firstUsername);
        PublishedRecipe recipe = publishFamilyRecipe(token);
        ConfirmedMenu menu = confirmFamilyMenu(token, recipe.recipeId());
        Long systemRecipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);

        try {
            jdbcTemplate.update(
                    "UPDATE dinner_recipe_methods SET recipe_id = ? WHERE id = ?",
                    systemRecipeId,
                    recipe.methodId());
            assertCompletionInvalid(token, menu);
            assertNoCompletionWrites(menu.menuId());
        } finally {
            jdbcTemplate.update(
                    "UPDATE dinner_recipe_methods SET recipe_id = ? WHERE id = ?",
                    recipe.recipeId(),
                    recipe.methodId());
        }
    }

    @Test
    void tamperedRecipeHouseholdRejectsCompletionBeforeRecordCreation() throws Exception {
        String token = token(firstUserId, firstUsername);
        PublishedRecipe recipe = publishFamilyRecipe(token);
        ConfirmedMenu menu = confirmFamilyMenu(token, recipe.recipeId());
        foreignHouseholdId = insertHousehold(
                "recipe-it-foreign-" + UUID.randomUUID(), firstUserId);

        try {
            jdbcTemplate.update(
                    "UPDATE dinner_recipes SET household_id = ? WHERE id = ?",
                    foreignHouseholdId,
                    recipe.recipeId());
            assertCompletionInvalid(token, menu);
            assertNoCompletionWrites(menu.menuId());
        } finally {
            jdbcTemplate.update(
                    "UPDATE dinner_recipes SET household_id = ? WHERE id = ?",
                    householdId,
                    recipe.recipeId());
        }
    }

    @Test
    void approvedImageWithBlankListKeyIsOmittedAndBlocksCompletion() throws Exception {
        String token = token(firstUserId, firstUsername);
        PublishedRecipe recipe = publishFamilyRecipe(token);
        ConfirmedMenu menu = confirmFamilyMenu(token, recipe.recipeId());

        try {
            jdbcTemplate.update(
                    "UPDATE dinner_image_assets SET list_object_key = '  ' WHERE id = ?",
                    firstImageAssetId);
            JsonNode discovery = perform(token, get("/api/dinner/recipes"), null);
            assertThat(findRecipe(discovery, recipe.recipeId())).isNull();
            assertCompletionInvalid(token, menu);
            assertNoCompletionWrites(menu.menuId());
        } finally {
            jdbcTemplate.update(
                    "UPDATE dinner_image_assets SET list_object_key = ? WHERE id = ?",
                    firstListObjectKey,
                    firstImageAssetId);
        }
    }

    @Test
    void secondSnapshotInsertFailureRollsBackTheRealCompletionTransaction() throws Exception {
        String token = token(firstUserId, firstUsername);
        PublishedRecipe recipe = publishFamilyRecipe(token);
        Long systemRecipeId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM dinner_recipes WHERE scope = 'SYSTEM'",
                Long.class);
        JsonNode today = perform(token, get("/api/dinner/menus/today"), null);
        JsonNode selected = perform(
                token,
                put("/api/dinner/menus/today/selections"),
                new UpdateSelectionsRequest(
                        List.of(systemRecipeId, recipe.recipeId()),
                        today.at("/data/version").asLong()));
        JsonNode confirmed = perform(
                token,
                post("/api/dinner/menus/today/confirm"),
                new MenuActionRequest(
                        selected.at("/data/version").asLong(),
                        UUID.randomUUID().toString()));
        long menuId = confirmed.at("/data/id").asLong();
        long confirmedVersion = confirmed.at("/data/version").asLong();

        AtomicInteger inserts = new AtomicInteger();
        Answer<?> realMapperDelegate = Mockito.mockingDetails(snapshotMapper)
                .getMockCreationSettings()
                .getDefaultAnswer();
        doAnswer(invocation -> {
            if (inserts.incrementAndGet() == 2) {
                throw new DataIntegrityViolationException("forced snapshot failure");
            }
            return realMapperDelegate.answer(invocation);
        }).when(snapshotMapper).insert(any(DinnerRecordDishSnapshotEntity.class));

        try {
            MockHttpServletRequestBuilder request = post("/api/dinner/menus/today/complete")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(new MenuActionRequest(
                            confirmedVersion,
                            UUID.randomUUID().toString())));
            mockMvc.perform(request)
                    .andExpect(status().isInternalServerError());
        } finally {
            reset(snapshotMapper);
        }

        assertThat(inserts.get()).isEqualTo(2);
        assertNoCompletionWrites(menuId);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM dinner_menus WHERE id = ?",
                        String.class,
                        menuId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT version FROM dinner_menus WHERE id = ?",
                        Long.class,
                        menuId))
                .isEqualTo(confirmedVersion);
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

    private PublishedRecipe publishFamilyRecipe(String token) throws Exception {
        Long ingredientId = householdIngredientId;
        assertThat(ingredientId).isNotNull();

        JsonNode created = perform(token, post("/api/dinner/recipes/drafts"), null);
        long recipeId = created.at("/data/id").asLong();
        assertThat(recipeId).isPositive();
        trackedRecipeIds.add(recipeId);
        assertVersionAndStatus(created, 1, "DRAFT");

        JsonNode basic = perform(
                token,
                put("/api/dinner/recipes/{id}/basic-info", recipeId),
                new UpdateRecipeBasicInfoRequest(
                        1L, recipeName, "家常菜", "酸甜", 2, 15));
        assertVersionAndStatus(basic, 2, "DRAFT");

        JsonNode ingredients = perform(
                token,
                put("/api/dinner/recipes/{id}/ingredients", recipeId),
                new ReplaceRecipeIngredientsRequest(
                        2L, List.of(new RecipeIngredientInput(
                                ingredientId, null, "枚", true))));
        assertVersionAndStatus(ingredients, 3, "DRAFT");
        assertThat(ingredients.at("/data/ingredients/0/quantity").isNull()).isTrue();

        JsonNode method = perform(
                token,
                put("/api/dinner/recipes/{id}/default-method", recipeId),
                new UpdateDefaultMethodRequest(
                        3L,
                        "家常炒",
                        "炒",
                        List.of(
                                new RecipeMethodStepInput("切好食材"),
                                new RecipeMethodStepInput("热锅翻炒至熟"))));
        assertVersionAndStatus(method, 4, "DRAFT");
        long methodId = method.at("/data/defaultMethod/id").asLong();
        assertThat(methodId).isPositive();
        trackedMethodIds.add(methodId);

        JsonNode image = perform(
                token,
                put("/api/dinner/recipes/{id}/image", recipeId),
                new SelectRecipeImageRequest(4L, firstImageAssetId));
        assertVersionAndStatus(image, 5, "DRAFT");
        String listUrl = image.at("/data/image/listUrl").asText();
        assertThat(listUrl).endsWith("/" + firstListObjectKey);

        JsonNode published = perform(
                token,
                post("/api/dinner/recipes/{id}/publish", recipeId),
                new PublishRecipeRequest(5L));
        assertVersionAndStatus(published, 6, "PUBLISHED");
        var publishedRow = jdbcTemplate.queryForMap(
                "SELECT status, version, published_at FROM dinner_recipes WHERE id = ?",
                recipeId);
        assertThat(publishedRow.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) publishedRow.get("version")).longValue()).isEqualTo(6L);
        assertThat(publishedRow.get("published_at")).isNotNull();
        return new PublishedRecipe(recipeId, methodId, ingredientId, listUrl);
    }

    private void addHouseholdInventory(long ingredientId) {
        jdbcTemplate.update(
                "INSERT INTO dinner_household_inventory "
                        + "(household_id, ingredient_id, quantity, unit, version, updated_by) "
                        + "VALUES (?, ?, NULL, '枚', 1, ?)",
                householdId,
                ingredientId,
                firstUserId);
    }

    private JsonNode discoveredRecipe(String token, long recipeId) throws Exception {
        JsonNode response = perform(token, get("/api/dinner/recipes"), null);
        JsonNode recipe = findRecipe(response, recipeId);
        assertThat(recipe).as("published recipe must be discoverable").isNotNull();
        return recipe;
    }

    private JsonNode findRecipe(JsonNode response, long recipeId) {
        return StreamSupport.stream(response.path("data").spliterator(), false)
                .filter(item -> item.path("id").asLong() == recipeId)
                .findFirst()
                .orElse(null);
    }

    private void assertDiscoveredHouseholdRecipe(
            JsonNode discovered,
            PublishedRecipe recipe
    ) {
        assertThat(discovered.path("scope").asText()).isEqualTo("HOUSEHOLD");
        assertThat(discovered.path("version").asLong()).isEqualTo(6L);
        assertThat(discovered.path("imagePath").asText()).isEqualTo(recipe.listUrl());
        assertThat(discovered.at("/defaultMethod/id").asLong()).isEqualTo(recipe.methodId());
        assertThat(discovered.at("/defaultMethod/name").asText()).isEqualTo("家常炒");
        assertThat(discovered.at("/defaultMethod/cookingStyle").asText()).isEqualTo("炒");
        assertThat(discovered.at("/ingredients/0/quantity").isNull()).isTrue();
        assertThat(discovered.at("/ingredients/0/unit").asText()).isEqualTo("枚");
        assertThat(discovered.at("/match/status").asText())
                .isEqualTo("UNKNOWN_QUANTITY");
    }

    private ConfirmedMenu confirmFamilyMenu(String token, long recipeId) throws Exception {
        JsonNode today = perform(token, get("/api/dinner/menus/today"), null);
        JsonNode selected = perform(
                token,
                put("/api/dinner/menus/today/selections"),
                new UpdateSelectionsRequest(
                        List.of(recipeId), today.at("/data/version").asLong()));
        JsonNode confirmed = perform(
                token,
                post("/api/dinner/menus/today/confirm"),
                new MenuActionRequest(
                        selected.at("/data/version").asLong(),
                        UUID.randomUUID().toString()));
        return new ConfirmedMenu(
                confirmed.at("/data/id").asLong(),
                confirmed.at("/data/version").asLong());
    }

    private void assertCompletionInvalid(String token, ConfirmedMenu menu) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/dinner/menus/today/complete")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(new MenuActionRequest(
                        menu.version(), UUID.randomUUID().toString())));
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DINNER_RECIPE_INVALID"));
    }

    private void assertNoCompletionWrites(long menuId) {
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_cooking_records WHERE menu_id = ?",
                        Integer.class,
                        menuId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_record_dish_snapshots snapshot "
                                + "JOIN dinner_cooking_records r "
                                + "ON r.id = snapshot.record_id WHERE r.menu_id = ?",
                        Integer.class,
                        menuId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM dinner_menu_actions "
                                + "WHERE menu_id = ? AND action_type = 'COMPLETE'",
                        Integer.class,
                        menuId))
                .isZero();
    }

    private void mutatePublishedAggregate(PublishedRecipe recipe) {
        assertThat(jdbcTemplate.update(
                        "UPDATE dinner_recipes SET name = ?, version = version + 1, "
                                + "image_asset_id = ? WHERE id = ?",
                        "已修改的源菜谱",
                        secondImageAssetId,
                        recipe.recipeId()))
                .as("source recipe mutation")
                .isOne();
        assertThat(jdbcTemplate.update(
                        "UPDATE dinner_recipe_methods SET name = ?, cooking_style = ? "
                                + "WHERE id = ?",
                        "已修改做法",
                        "炖",
                        recipe.methodId()))
                .as("source method mutation")
                .isOne();
        assertThat(jdbcTemplate.update(
                        "UPDATE dinner_recipe_method_steps "
                                + "SET instruction = CONCAT('已修改步骤-', sort_order) "
                                + "WHERE method_id = ?",
                        recipe.methodId()))
                .as("source steps mutation")
                .isEqualTo(2);
        assertThat(jdbcTemplate.update(
                        "UPDATE dinner_recipe_ingredients "
                                + "SET quantity = ?, unit = '克', is_required = 0 "
                                + "WHERE recipe_id = ?",
                        new BigDecimal("99.000"),
                        recipe.recipeId()))
                .as("source recipe ingredient mutation")
                .isOne();
        assertThat(jdbcTemplate.update(
                        "UPDATE dinner_ingredients SET name = ?, default_unit = ? WHERE id = ?",
                        "已修改食材",
                        "克",
                        recipe.ingredientId()))
                .as("source ingredient mutation")
                .isOne();
    }

    private Long insertApprovedImageAsset(
            String displayName,
            String sha256,
            String listObjectKey,
            String detailObjectKey
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dinner_image_assets "
                            + "(provider, display_name, search_keywords, source_page_url, "
                            + "original_file_url, author, license_name, license_url, acquired_on, "
                            + "sha256, original_width, original_height, original_object_key, "
                            + "list_object_key, detail_object_key, status, reviewed_at) "
                            + "VALUES ('INTEGRATION_TEST', ?, 'integration test', ?, ?, "
                            + "'osheeep-test', 'CC BY 4.0', "
                            + "'https://creativecommons.org/licenses/by/4.0/', '2026-07-21', "
                            + "?, 1200, 900, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP(3))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, displayName);
            statement.setString(2, "https://example.test/source/" + sha256);
            statement.setString(3, "https://example.test/original/" + sha256 + ".jpg");
            statement.setString(4, sha256);
            statement.setString(5, "integration/" + sha256 + "/original.jpg");
            statement.setString(6, listObjectKey);
            statement.setString(7, detailObjectKey);
            return statement;
        }, keyHolder);
        assertThat(keyHolder.getKey()).as("inserted image asset id").isNotNull();
        Long id = keyHolder.getKey().longValue();
        trackedImageAssetIds.add(id);
        return id;
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

    private Long insertHouseholdIngredient(String name) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO dinner_ingredients "
                            + "(scope, household_id, name, category, default_unit, status) "
                            + "VALUES ('HOUSEHOLD', ?, ?, '蛋奶', '枚', 'ACTIVE')",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, householdId);
            statement.setString(2, name);
            return statement;
        }, keyHolder);
        assertThat(keyHolder.getKey()).as("inserted household ingredient id").isNotNull();
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
        if (firstImageAssetId == null && firstImageSha256 != null) {
            firstImageAssetId = findImageAssetId(firstImageSha256);
        }
        if (secondImageAssetId == null && secondImageSha256 != null) {
            secondImageAssetId = findImageAssetId(secondImageSha256);
        }
        if (firstImageAssetId != null) {
            trackedImageAssetIds.add(firstImageAssetId);
        }
        if (secondImageAssetId != null) {
            trackedImageAssetIds.add(secondImageAssetId);
        }
        if (firstUserId != null) {
            trackedRecipeIds.addAll(jdbcTemplate.query(
                    "SELECT id FROM dinner_recipes "
                            + "WHERE scope = 'HOUSEHOLD' AND creator_id = ?",
                    (resultSet, rowNumber) -> resultSet.getLong(1),
                    firstUserId));
        }
        if (!trackedRecipeIds.isEmpty()) {
            trackedMethodIds.addAll(jdbcTemplate.query(
                    "SELECT id FROM dinner_recipe_methods WHERE recipe_id IN ("
                            + placeholders(trackedRecipeIds.size()) + ")",
                    (resultSet, rowNumber) -> resultSet.getLong(1),
                    trackedRecipeIds.toArray()));
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

    private Long findImageAssetId(String sha256) {
        return jdbcTemplate.query(
                        "SELECT id FROM dinner_image_assets WHERE sha256 = ?",
                        (resultSet, rowNumber) -> resultSet.getLong(1),
                        sha256)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void deleteTrackedRows(String table, String idColumn, Set<Long> ids) {
        Set<String> allowedTargets = Set.of(
                "dinner_recipe_method_steps.method_id",
                "dinner_recipe_methods.id",
                "dinner_recipes.id",
                "dinner_image_assets.id");
        if (ids.isEmpty()) {
            return;
        }
        if (!allowedTargets.contains(table + "." + idColumn)) {
            throw new IllegalArgumentException("Unsupported integration cleanup target");
        }
        jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + idColumn + " IN ("
                        + placeholders(ids.size()) + ")",
                ids.toArray());
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record PublishedRecipe(
            long recipeId,
            long methodId,
            long ingredientId,
            String listUrl
    ) {
    }

    private record ConfirmedMenu(long menuId, long version) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlywaySafetyConfiguration {

        @Bean
        FlywayMigrationStrategy dinnerCustomRecipeFlywayMigrationStrategy() {
            return new DinnerCustomRecipeFlywayMigrationStrategy(
                    System.getenv("OSHEEEP_DB_TEST_NAME"));
        }
    }

}
