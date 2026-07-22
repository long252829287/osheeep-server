package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import com.osheeep.server.user.UserMapper;
import com.osheeep.server.user.entity.UserEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeQueryServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerImageAssetService imageAssetService;
    @Mock private UserMapper userMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;

    private DinnerRecipeQueryService queryService;

    @BeforeAll
    static void initializeMybatisTableMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        List.of(
                        DinnerRecipeEntity.class,
                        DinnerRecipeMethodEntity.class,
                        DinnerRecipeMethodStepEntity.class)
                .forEach(entityType -> TableInfoHelper.initTableInfo(assistant, entityType));
    }

    @BeforeEach
    void setUp() {
        DinnerRecipeAuthorizer authorizer =
                new DinnerRecipeAuthorizer(memberMapper, householdMapper, recipeMapper);
        queryService = new DinnerRecipeQueryService(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService,
                userMapper, authorizer);
    }

    @Test
    void draftListContainsOnlyTheCurrentUsersDraftsAndUsesStableOrdering() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity draft = draft(101L, 7L);
        draft.setLastModifiedBy(8L);
        when(recipeMapper.selectList(any())).thenReturn(List.of(draft));
        when(ingredientMapper.selectWithIngredientNames(List.of(101L))).thenReturn(List.of());
        when(methodMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectByIds(any()))
                .thenReturn(List.of(user(7L, "", "owner"), user(8L, "伙伴", "partner")));

        List<FamilyRecipeListItemResponse> result =
                queryService.list(7L, FamilyRecipeTab.DRAFT);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(101L);
            assertThat(item.creatorName()).isEqualTo("owner");
            assertThat(item.lastModifiedByName()).isEqualTo("伙伴");
            assertThat(item.completedStep()).isEqualTo("BASIC");
            assertThat(item.updatedAt()).isEqualTo(Instant.parse("2026-07-16T12:30:00Z"));
        });
        ArgumentCaptor<Wrapper<DinnerRecipeEntity>> query = wrapperCaptor();
        verify(recipeMapper).selectList(query.capture());
        assertEqualsCondition(query.getValue(), "creator_id", 7L);
        assertEqualsCondition(query.getValue(), "status", "DRAFT");
        assertThat(query.getValue().getSqlSegment())
                .doesNotContain("household_id", "scope")
                .contains("ORDER BY updated_at DESC,id DESC");
    }

    @ParameterizedTest
    @EnumSource(value = FamilyRecipeTab.class, names = {"PUBLISHED", "ARCHIVED"})
    void householdListUsesExactScopeStatusAndHouseholdPredicates(FamilyRecipeTab tab) {
        stubActiveMembership(7L, 70L);
        when(recipeMapper.selectList(any())).thenReturn(List.of());

        assertThat(queryService.list(7L, tab)).isEmpty();

        ArgumentCaptor<Wrapper<DinnerRecipeEntity>> query = wrapperCaptor();
        verify(recipeMapper).selectList(query.capture());
        assertEqualsCondition(query.getValue(), "household_id", 70L);
        assertEqualsCondition(query.getValue(), "scope", "HOUSEHOLD");
        assertEqualsCondition(query.getValue(), "status", tab.name());
        assertThat(query.getValue().getSqlSegment())
                .doesNotContain("creator_id")
                .contains("ORDER BY updated_at DESC,id DESC");
        verifyNoInteractions(
                ingredientMapper, methodMapper, stepMapper, imageAssetService, userMapper);
    }

    @Test
    void draftListDerivesAllProgressStepsWithBatchedAggregateQueries() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity basic = draft(101L, 7L);
        DinnerRecipeEntity ingredients = completeBasics(draft(102L, 7L));
        DinnerRecipeEntity method = completeBasics(draft(103L, 7L));
        DinnerRecipeEntity image = completeBasics(draft(104L, 7L));
        DinnerRecipeEntity preview = completeBasics(draft(105L, 7L));
        preview.setImageAssetId(9L);
        List<DinnerRecipeEntity> recipes = List.of(basic, ingredients, method, image, preview);
        recipes.forEach(recipe -> recipe.setLastModifiedBy(7L));
        when(recipeMapper.selectList(any())).thenReturn(recipes);
        when(ingredientMapper.selectWithIngredientNames(List.of(101L, 102L, 103L, 104L, 105L)))
                .thenReturn(List.of(
                        row(103L, 1L, 1),
                        row(104L, 1L, 1),
                        row(105L, 1L, 1)));
        when(methodMapper.selectList(any())).thenReturn(List.of(
                method(204L, 104L),
                method(205L, 105L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(204L, "炒熟", 1),
                step(205L, "炒熟", 1)));
        when(imageAssetService.findApprovedByIds(List.of(9L)))
                .thenReturn(Map.of(9L, imageResponse(9L)));
        when(userMapper.selectByIds(any()))
                .thenReturn(List.of(user(7L, null, null)));

        assertThat(queryService.list(7L, FamilyRecipeTab.DRAFT))
                .extracting(FamilyRecipeListItemResponse::completedStep)
                .containsExactly("BASIC", "INGREDIENTS", "METHOD", "IMAGE", "PREVIEW");

        verify(ingredientMapper).selectWithIngredientNames(List.of(101L, 102L, 103L, 104L, 105L));
        verify(methodMapper).selectList(any());
        verify(stepMapper).selectList(any());
        verify(imageAssetService).findApprovedByIds(List.of(9L));
        verify(userMapper).selectByIds(any());
    }

    @Test
    void listTreatsUnresolvedSelectedImageAsTheImageStepWithoutExtraQueries() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setImageAssetId(8L);
        when(recipeMapper.selectList(any())).thenReturn(List.of(recipe));
        when(ingredientMapper.selectWithIngredientNames(List.of(101L)))
                .thenReturn(List.of(row(101L, 1L, 0)));
        when(methodMapper.selectList(any())).thenReturn(List.of(method(201L, 101L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(step(201L, "炒熟", 0)));
        when(imageAssetService.findApprovedByIds(List.of(8L))).thenReturn(Map.of());
        when(userMapper.selectByIds(any())).thenReturn(List.of(user(7L, "小羊", "owner")));

        List<FamilyRecipeListItemResponse> result =
                queryService.list(7L, FamilyRecipeTab.DRAFT);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.completedStep()).isEqualTo("IMAGE");
            assertThat(item.imageUrl()).isNull();
        });
        verify(imageAssetService).findApprovedByIds(List.of(8L));
    }

    @Test
    void missingUsersUseTheHouseholdMemberFallbackWithoutPerRowQueries() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity first = draft(101L, 7L);
        first.setLastModifiedBy(8L);
        DinnerRecipeEntity second = draft(102L, 9L);
        second.setLastModifiedBy(9L);
        when(recipeMapper.selectList(any())).thenReturn(List.of(first, second));
        when(ingredientMapper.selectWithIngredientNames(any())).thenReturn(List.of());
        when(methodMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectByIds(any()))
                .thenReturn(List.of(user(7L, "", "owner"), user(8L, "伙伴", "partner")));

        List<FamilyRecipeListItemResponse> result =
                queryService.list(7L, FamilyRecipeTab.DRAFT);

        assertThat(result).extracting(FamilyRecipeListItemResponse::creatorName)
                .containsExactly("owner", "家庭成员");
        assertThat(result).extracting(FamilyRecipeListItemResponse::lastModifiedByName)
                .containsExactly("伙伴", "家庭成员");
        verify(userMapper).selectByIds(any());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void partnerCannotReadAnotherUsersDraft() {
        stubActiveMembership(8L, 70L);
        when(recipeMapper.selectById(101L)).thenReturn(draft(101L, 7L));

        assertThatThrownBy(() -> queryService.detail(8L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @ParameterizedTest
    @EnumSource(value = FamilyRecipeTab.class, names = {"PUBLISHED", "ARCHIVED"})
    void partnerCanReadPublishedAndArchivedRecipesFromTheSameHousehold(FamilyRecipeTab tab) {
        stubActiveMembership(8L, 70L);
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setStatus(tab.name());
        when(recipeMapper.selectById(101L)).thenReturn(recipe);
        when(ingredientMapper.selectWithIngredientNames(List.of(101L))).thenReturn(List.of());
        when(methodMapper.selectList(any())).thenReturn(List.of());

        assertThat(queryService.detail(8L, 101L).status()).isEqualTo(tab.name());
    }

    @Test
    void householdMemberCannotReadPublishedRecipeFromAnotherHousehold() {
        stubActiveMembership(8L, 70L);
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setStatus("PUBLISHED");
        recipe.setHouseholdId(71L);
        when(recipeMapper.selectById(101L)).thenReturn(recipe);

        assertThatThrownBy(() -> queryService.detail(8L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @ParameterizedTest(name = "{0} member cannot list family recipes from the old household")
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void formerMemberCannotListOldHouseholdRecipes(String membershipStatus) {
        when(memberMapper.selectActiveByUserId(7L))
                .thenReturn(member(7L, 70L, membershipStatus));
        lenient().when(householdMapper.selectById(70L))
                .thenReturn(household(70L, "ACTIVE"));

        assertThatThrownBy(() -> queryService.list(7L, FamilyRecipeTab.PUBLISHED))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(
                ingredientMapper, methodMapper, stepMapper, imageAssetService, userMapper);
    }

    @ParameterizedTest(name = "{0} member cannot read family recipe detail from the old household")
    @ValueSource(strings = {"LEFT", "REMOVED"})
    void formerMemberCannotReadOldHouseholdRecipeDetail(String membershipStatus) {
        when(memberMapper.selectActiveByUserId(7L))
                .thenReturn(member(7L, 70L, membershipStatus));
        lenient().when(householdMapper.selectById(70L))
                .thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setStatus("PUBLISHED");
        lenient().when(recipeMapper.selectById(101L)).thenReturn(recipe);

        assertThatThrownBy(() -> queryService.detail(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @ParameterizedTest(name = "ACTIVE member cannot list family recipes when household is {0}")
    @ValueSource(strings = {"MISSING", "DISSOLVED"})
    void activeMemberCannotListRecipesWithoutActiveHousehold(String householdStatus) {
        stubMembershipWithHouseholdStatus(7L, 70L, householdStatus);

        assertThatThrownBy(() -> queryService.list(7L, FamilyRecipeTab.PUBLISHED))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(
                recipeMapper, ingredientMapper, methodMapper, stepMapper,
                imageAssetService, userMapper);
    }

    @ParameterizedTest(name = "ACTIVE member cannot read recipe detail when household is {0}")
    @ValueSource(strings = {"MISSING", "DISSOLVED"})
    void activeMemberCannotReadRecipeDetailWithoutActiveHousehold(String householdStatus) {
        stubMembershipWithHouseholdStatus(7L, 70L, householdStatus);

        assertThatThrownBy(() -> queryService.detail(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode().name())
                                .isEqualTo("DINNER_HOUSEHOLD_REQUIRED"));

        verifyNoInteractions(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void detailBatchesTheAggregateAndConvertsShanghaiDateTimeToInstant() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setImageAssetId(9L);
        recipe.setVersion(4L);
        when(recipeMapper.selectById(101L)).thenReturn(recipe);
        when(ingredientMapper.selectWithIngredientNames(List.of(101L)))
                .thenReturn(List.of(
                        row(101L, 2L, 2),
                        row(101L, 1L, 1)));
        when(methodMapper.selectList(any())).thenReturn(List.of(method(201L, 101L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(201L, "装盘", 2),
                step(201L, "炒熟", 1)));
        when(imageAssetService.findApprovedByIds(List.of(9L)))
                .thenReturn(Map.of(9L, imageResponse(9L)));

        RecipeDraftResponse result = queryService.detail(7L, 101L);

        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.ingredients()).extracting(item -> item.ingredientId())
                .containsExactly(1L, 2L);
        assertThat(result.defaultMethod().id()).isEqualTo(201L);
        assertThat(result.defaultMethod().name()).isEqualTo("家常做法");
        assertThat(result.defaultMethod().cookingStyle()).isEqualTo("炒");
        assertThat(result.defaultMethod().steps()).extracting(item -> item.instruction())
                .containsExactly("炒熟", "装盘");
        assertThat(result.defaultMethod().steps()).extracting(item -> item.sortOrder())
                .containsExactly(1, 2);
        assertThat(result.image().listUrl())
                .isEqualTo("https://assets.test/media/recipes/tomato-with-egg-list.webp");
        assertThat(result.image().detailUrl())
                .isEqualTo("https://assets.test/media/recipes/tomato-with-egg-detail.webp");
        assertThat(result.incompleteSteps()).isEmpty();
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-07-16T12:30:00Z"));
        verify(ingredientMapper).selectWithIngredientNames(List.of(101L));
        verify(methodMapper).selectList(any());
        verify(stepMapper).selectList(any());
        verify(imageAssetService).findApprovedByIds(List.of(9L));
    }

    @Test
    void detailTreatsUnresolvedSelectedImageAsIncompleteInStableOrder() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity recipe = completeBasics(draft(101L, 7L));
        recipe.setImageAssetId(8L);
        when(recipeMapper.selectById(101L)).thenReturn(recipe);
        when(ingredientMapper.selectWithIngredientNames(List.of(101L)))
                .thenReturn(List.of(row(101L, 1L, 0)));
        when(methodMapper.selectList(any())).thenReturn(List.of(method(201L, 101L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(step(201L, "炒熟", 0)));
        when(imageAssetService.findApprovedByIds(List.of(8L))).thenReturn(Map.of());

        RecipeDraftResponse result = queryService.detail(7L, 101L);

        assertThat(result.image()).isNull();
        assertThat(result.incompleteSteps()).containsExactly("IMAGE");
        verify(imageAssetService).findApprovedByIds(List.of(8L));
    }

    @Test
    void detailMarksBlankMethodInstructionsIncompleteInFixedStepOrder() {
        stubActiveMembership(7L, 70L);
        DinnerRecipeEntity recipe = draft(101L, 7L);
        recipe.setImageAssetId(9L);
        when(recipeMapper.selectById(101L)).thenReturn(recipe);
        when(ingredientMapper.selectWithIngredientNames(List.of(101L))).thenReturn(List.of());
        when(methodMapper.selectList(any())).thenReturn(List.of(method(201L, 101L)));
        when(stepMapper.selectList(any())).thenReturn(List.of(step(201L, " ", 1)));
        when(imageAssetService.findApprovedByIds(List.of(9L)))
                .thenReturn(Map.of(9L, imageResponse(9L)));

        RecipeDraftResponse result = queryService.detail(7L, 101L);

        assertThat(result.incompleteSteps())
                .containsExactly("BASIC", "INGREDIENTS", "METHOD");
    }

    private void stubActiveMembership(Long userId, Long householdId) {
        when(memberMapper.selectActiveByUserId(userId)).thenReturn(member(userId, householdId));
        when(householdMapper.selectById(householdId)).thenReturn(household(householdId, "ACTIVE"));
    }

    private void stubMembershipWithHouseholdStatus(
            Long userId,
            Long householdId,
            String householdStatus
    ) {
        when(memberMapper.selectActiveByUserId(userId))
                .thenReturn(member(userId, householdId, "ACTIVE"));
        if (!"MISSING".equals(householdStatus)) {
            when(householdMapper.selectById(householdId))
                    .thenReturn(household(householdId, householdStatus));
        }
    }

    private DinnerRecipeEntity draft(Long id, Long creatorId) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(creatorId);
        recipe.setLastModifiedBy(creatorId);
        recipe.setStatus("DRAFT");
        recipe.setVersion(1L);
        recipe.setUpdatedAt(LocalDateTime.of(2026, 7, 16, 20, 30));
        return recipe;
    }

    private DinnerRecipeEntity completeBasics(DinnerRecipeEntity recipe) {
        recipe.setName("番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("咸鲜");
        recipe.setServings(2);
        recipe.setEstimatedMinutes(15);
        return recipe;
    }

    private DinnerRecipeIngredientRow row(Long recipeId, Long ingredientId, int sortOrder) {
        return new DinnerRecipeIngredientRow(
                recipeId, ingredientId, "食材" + ingredientId, BigDecimal.ONE,
                "份", true, sortOrder);
    }

    private DinnerRecipeMethodEntity method(Long id, Long recipeId) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(recipeId);
        method.setName("家常做法");
        method.setCookingStyle("炒");
        method.setIsDefault(true);
        method.setStatus("ACTIVE");
        method.setSortOrder(0);
        return method;
    }

    private DinnerRecipeMethodStepEntity step(Long methodId, String instruction, int sortOrder) {
        DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
        step.setMethodId(methodId);
        step.setInstruction(instruction);
        step.setSortOrder(sortOrder);
        return step;
    }

    private ImageAssetResponse imageResponse(Long id) {
        return new ImageAssetResponse(
                id, "番茄炒鸡蛋",
                "https://assets.test/media/recipes/tomato-with-egg-list.webp",
                "https://assets.test/media/recipes/tomato-with-egg-detail.webp",
                "https://commons.wikimedia.org/wiki/File:Tomato_with_egg.jpg",
                "Kaap bij Sneeuw", "CC0 1.0",
                "https://creativecommons.org/publicdomain/zero/1.0/",
                LocalDate.of(2026, 7, 16), 1198, 1091);
    }

    private UserEntity user(Long id, String displayName, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setUsername(username);
        return user;
    }

    private DinnerHouseholdMemberEntity member(Long userId, Long householdId) {
        return member(userId, householdId, "ACTIVE");
    }

    private DinnerHouseholdMemberEntity member(
            Long userId,
            Long householdId,
            String status
    ) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(11L);
        member.setUserId(userId);
        member.setHouseholdId(householdId);
        member.setStatus(status);
        member.setRole("MEMBER");
        member.setVersion(1L);
        member.setHistoryVisibleFrom(LocalDateTime.of(2026, 7, 1, 0, 0));
        return member;
    }

    private DinnerHouseholdEntity household(Long id, String status) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setStatus(status);
        household.setVersion(1L);
        household.setTimezone("Asia/Shanghai");
        return household;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> ArgumentCaptor<Wrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static void assertEqualsCondition(
            Wrapper<?> wrapper, String column, Object expectedValue) {
        Matcher matcher = Pattern.compile(
                        Pattern.quote(column)
                                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
                .matcher(wrapper.getSqlSegment());
        assertThat(matcher.find())
                .as("%s equality condition in %s", column, wrapper.getSqlSegment())
                .isTrue();
        assertThat(parameterValues(wrapper))
                .containsEntry(matcher.group(1), expectedValue);
    }

    private static Map<String, Object> parameterValues(Wrapper<?> wrapper) {
        assertThat(wrapper).isInstanceOf(AbstractWrapper.class);
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
