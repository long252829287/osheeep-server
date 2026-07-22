package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.ingredient.entity.DinnerIngredientEntity;
import com.osheeep.server.dinner.ingredient.mapper.DinnerIngredientMapper;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientInput;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepInput;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
import com.osheeep.server.dinner.recipe.dto.ReplaceRecipeIngredientsRequest;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.SelectRecipeImageRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateDefaultMethodRequest;
import com.osheeep.server.dinner.recipe.dto.UpdateRecipeBasicInfoRequest;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeIngredientEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeDraftServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper recipeIngredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerIngredientMapper ingredientMapper;
    @Mock private DinnerImageAssetService imageAssetService;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;
    @Mock private DinnerRecipeQueryService queryService;

    private DinnerRecipeAuthorizer authorizer;
    private DinnerRecipeDraftService service;

    @BeforeEach
    void setUp() {
        authorizer = new DinnerRecipeAuthorizer(memberMapper, householdMapper, recipeMapper);
        service = new DinnerRecipeDraftService(
                recipeMapper, recipeIngredientMapper, methodMapper, stepMapper,
                ingredientMapper, imageAssetService, authorizer, queryService);
    }

    @Test
    void createsVersionOneDraftForTheCurrentHouseholdAndOwner() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        when(recipeMapper.insert(any(DinnerRecipeEntity.class))).thenAnswer(invocation -> {
            DinnerRecipeEntity row = invocation.getArgument(0);
            row.setId(101L);
            return 1;
        });

        RecipeDraftResponse result = service.create(7L);

        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.ingredients()).isEmpty();
        assertThat(result.defaultMethod()).isNull();
        assertThat(result.image()).isNull();
        assertThat(result.incompleteSteps())
                .containsExactly("BASIC", "INGREDIENTS", "METHOD", "IMAGE");
        verify(recipeMapper).insert(argThat((DinnerRecipeEntity row) ->
                "HOUSEHOLD".equals(row.getScope())
                        && "DRAFT".equals(row.getStatus())
                        && row.getHouseholdId().equals(70L)
                        && row.getCreatorId().equals(7L)
                        && row.getLastModifiedBy().equals(7L)
                        && row.getVersion().equals(1L)));
    }

    @Test
    void rejectsDraftCreationWhenTheCurrentHouseholdIsNotActive() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ARCHIVED"));

        assertThatThrownBy(() -> service.create(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_HOUSEHOLD_REQUIRED));

        verify(recipeMapper, never()).insert(any(DinnerRecipeEntity.class));
    }

    @Test
    void ownedDraftAuthorizationRejectsARecipeThatIsNotTheCurrentUsersDraft() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity published = recipe(101L, 7L, "PUBLISHED");
        when(recipeMapper.selectById(101L)).thenReturn(published);

        assertThatThrownBy(() -> authorizer.requireOwnedDraft(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void ownedDraftAuthorizationReturnsTheCurrentUsersDraft() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity draft = recipe(101L, 7L, "DRAFT");
        when(recipeMapper.selectById(101L)).thenReturn(draft);

        assertThat(authorizer.requireOwnedDraft(7L, 101L)).isSameAs(draft);
    }

    @Test
    void ownedDraftAuthorizationRejectsTheOwnersDraftFromAnotherHousehold() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity oldHouseholdDraft = recipe(101L, 7L, "DRAFT");
        oldHouseholdDraft.setHouseholdId(71L);
        when(recipeMapper.selectById(101L)).thenReturn(oldHouseholdDraft);

        assertThatThrownBy(() -> authorizer.requireOwnedDraft(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void draftVisibilityRejectsTheOwnersDraftFromAnotherHousehold() {
        when(memberMapper.selectActiveByUserId(7L)).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity oldHouseholdDraft = recipe(101L, 7L, "DRAFT");
        oldHouseholdDraft.setHouseholdId(71L);
        when(recipeMapper.selectById(101L)).thenReturn(oldHouseholdDraft);

        assertThatThrownBy(() -> authorizer.requireVisible(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void basicInfoSaveLocksExpectedVersionNormalizesTextAndIncrementsOnce() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 3L);
        DinnerRecipeMethodEntity method = defaultMethod(201L, 101L, 8);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(methodMapper.selectOne(any())).thenReturn(method);
        when(queryService.detail(7L, 101L)).thenAnswer(ignored -> response(
                draft, List.of(), null));

        RecipeDraftResponse saved = service.updateBasicInfo(
                7L, 101L,
                new UpdateRecipeBasicInfoRequest(
                        3L, "  番茄炒蛋  ", "  ", " 酸甜 ", 2, 15));

        assertThat(saved.version()).isEqualTo(4L);
        assertThat(draft.getName()).isEqualTo("番茄炒蛋");
        assertThat(draft.getCategory()).isNull();
        assertThat(draft.getFlavor()).isEqualTo("酸甜");
        assertThat(method.getEstimatedMinutes()).isEqualTo(15);
        verify(recipeMapper).selectByIdForUpdate(101L);
        verify(methodMapper).updateById(method);
        verify(recipeMapper, times(1)).updateById(argThat((DinnerRecipeEntity row) ->
                row.getVersion() == 4L
                        && row.getLastModifiedBy().equals(7L)
                        && row.getName().equals("番茄炒蛋")));
    }

    @Test
    void basicInfoSaveDoesNotCreateAMethodWhenNoneExists() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 1L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(methodMapper.selectOne(any())).thenReturn(null);
        when(queryService.detail(7L, 101L)).thenAnswer(ignored -> response(
                draft, List.of(), null));

        service.updateBasicInfo(
                7L, 101L,
                new UpdateRecipeBasicInfoRequest(1L, null, null, null, null, null));

        verify(methodMapper, never()).insert(any(DinnerRecipeMethodEntity.class));
        verify(methodMapper, never()).updateById(any(DinnerRecipeMethodEntity.class));
        assertThat(draft.getVersion()).isEqualTo(2L);
    }

    @Test
    void missingLockedRecipeReturnsNotFoundWithoutMutation() {
        when(recipeMapper.selectByIdForUpdate(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateBasicInfo(
                7L, 404L,
                new UpdateRecipeBasicInfoRequest(1L, null, null, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_NOT_FOUND));

        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
        verifyNoInteractions(methodMapper, queryService);
    }

    @Test
    void staleVersionNeverReplacesIngredients() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 7L, 70L, 4L));

        assertThatThrownBy(() -> service.replaceIngredients(
                7L, 101L,
                new ReplaceRecipeIngredientsRequest(3L, List.of(
                        new RecipeIngredientInput(1L, null, "克", true)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verifyNoInteractions(recipeIngredientMapper, ingredientMapper, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void quantityMayBeNullAndVisibleIngredientsReplaceInRequestOrder() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 1L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(ingredientMapper.selectById(1L))
                .thenReturn(ingredient(1L, "SYSTEM", null, "ACTIVE", "番茄"));
        when(ingredientMapper.selectById(2L))
                .thenReturn(ingredient(2L, "HOUSEHOLD", 70L, "ACTIVE", "鸡蛋"));
        when(queryService.detail(7L, 101L)).thenReturn(new RecipeDraftResponse(
                101L, "DRAFT", 2L, null, null, null, null, null,
                List.of(
                        new RecipeIngredientResponse(1L, "番茄", null, "克", true, 0),
                        new RecipeIngredientResponse(
                                2L, "鸡蛋", new BigDecimal("2.000"), "枚", false, 1)),
                null, null, List.of("BASIC", "METHOD", "IMAGE"), null));

        RecipeDraftResponse saved = service.replaceIngredients(
                7L, 101L,
                new ReplaceRecipeIngredientsRequest(1L, List.of(
                        new RecipeIngredientInput(1L, null, " 克 ", true),
                        new RecipeIngredientInput(
                                2L, new BigDecimal("2.000"), "枚", false))));

        assertThat(saved.version()).isEqualTo(2L);
        assertThat(saved.ingredients()).first()
                .satisfies(item -> assertThat(item.quantity()).isNull());
        ArgumentCaptor<DinnerRecipeIngredientEntity> rows =
                ArgumentCaptor.forClass(DinnerRecipeIngredientEntity.class);
        InOrder replacementOrder = inOrder(recipeIngredientMapper, recipeMapper);
        replacementOrder.verify(recipeIngredientMapper).delete(any());
        replacementOrder.verify(recipeIngredientMapper, times(2)).insert(rows.capture());
        replacementOrder.verify(recipeMapper).updateById(draft);
        assertThat(rows.getAllValues())
                .extracting(
                        DinnerRecipeIngredientEntity::getIngredientId,
                        DinnerRecipeIngredientEntity::getSortOrder,
                        DinnerRecipeIngredientEntity::getUnit)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, 0, "克"),
                        org.assertj.core.groups.Tuple.tuple(2L, 1, "枚"));
    }

    @Test
    void duplicateIngredientsAreRejectedBeforeDeletingOldRows() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 7L, 70L, 1L));

        assertThatThrownBy(() -> service.replaceIngredients(
                7L, 101L,
                new ReplaceRecipeIngredientsRequest(1L, List.of(
                        new RecipeIngredientInput(1L, null, "克", true),
                        new RecipeIngredientInput(1L, BigDecimal.ONE, "个", false)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_INVALID));

        verifyNoInteractions(recipeIngredientMapper, ingredientMapper, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void ingredientOutsideTheDraftHouseholdIsRejectedBeforeDeletingOldRows() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 7L, 70L, 1L));
        when(ingredientMapper.selectById(1L))
                .thenReturn(ingredient(1L, "HOUSEHOLD", 71L, "ACTIVE", "私有食材"));

        assertThatThrownBy(() -> service.replaceIngredients(
                7L, 101L,
                new ReplaceRecipeIngredientsRequest(1L, List.of(
                        new RecipeIngredientInput(1L, null, "克", true)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_INGREDIENT_INVALID));

        verifyNoInteractions(recipeIngredientMapper, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void defaultMethodUpsertsOneActiveDefaultAndReplacesZeroBasedSteps() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 2L);
        draft.setEstimatedMinutes(15);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(methodMapper.selectOne(any())).thenReturn(null);
        when(methodMapper.insert(any(DinnerRecipeMethodEntity.class))).thenAnswer(invocation -> {
            DinnerRecipeMethodEntity method = invocation.getArgument(0);
            method.setId(201L);
            return 1;
        });
        when(queryService.detail(7L, 101L)).thenReturn(new RecipeDraftResponse(
                101L, "DRAFT", 3L, null, null, null, null, 15,
                List.of(),
                new RecipeMethodResponse(
                        201L, "家常做法", "炒",
                        List.of(
                                new RecipeMethodStepResponse("热锅", 0),
                                new RecipeMethodStepResponse("", 1))),
                null, List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null));

        RecipeDraftResponse saved = service.updateDefaultMethod(
                7L, 101L,
                new UpdateDefaultMethodRequest(
                        2L, " 家常做法 ", " 炒 ",
                        List.of(
                                new RecipeMethodStepInput(" 热锅 "),
                                new RecipeMethodStepInput("   "))));

        assertThat(saved.version()).isEqualTo(3L);
        ArgumentCaptor<DinnerRecipeMethodEntity> methodCaptor =
                ArgumentCaptor.forClass(DinnerRecipeMethodEntity.class);
        verify(methodMapper).insert(methodCaptor.capture());
        assertThat(methodCaptor.getValue()).satisfies(method -> {
            assertThat(method.getRecipeId()).isEqualTo(101L);
            assertThat(method.getName()).isEqualTo("家常做法");
            assertThat(method.getCookingStyle()).isEqualTo("炒");
            assertThat(method.getEstimatedMinutes()).isEqualTo(15);
            assertThat(method.getIsDefault()).isTrue();
            assertThat(method.getStatus()).isEqualTo("ACTIVE");
            assertThat(method.getSortOrder()).isZero();
        });
        ArgumentCaptor<DinnerRecipeMethodStepEntity> steps =
                ArgumentCaptor.forClass(DinnerRecipeMethodStepEntity.class);
        verify(stepMapper).delete(any());
        verify(stepMapper, times(2)).insert(steps.capture());
        assertThat(steps.getAllValues())
                .extracting(
                        DinnerRecipeMethodStepEntity::getInstruction,
                        DinnerRecipeMethodStepEntity::getSortOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("热锅", 0),
                        org.assertj.core.groups.Tuple.tuple("", 1));
        verify(recipeMapper, times(1)).updateById(argThat((DinnerRecipeEntity row) ->
                row.getVersion() == 3L && row.getLastModifiedBy().equals(7L)));
    }

    @Test
    void staleVersionNeverReplacesDefaultMethod() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 7L, 70L, 4L));

        assertThatThrownBy(() -> service.updateDefaultMethod(
                7L, 101L,
                new UpdateDefaultMethodRequest(
                        3L, null, null, List.of(new RecipeMethodStepInput("炒熟")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verifyNoInteractions(methodMapper, stepMapper, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void approvedImageSelectionUsesTheSharedLockAndAdvancesVersionExactlyOnce() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 3L);
        ImageAssetResponse image = imageResponse(9L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(imageAssetService.requireApproved(9L)).thenReturn(image);
        when(queryService.detail(7L, 101L)).thenReturn(new RecipeDraftResponse(
                101L, "DRAFT", 4L, null, null, null, null, null,
                List.of(), null, image, List.of("BASIC", "INGREDIENTS", "METHOD"), null));

        RecipeDraftResponse saved = service.selectImage(
                7L, 101L, new SelectRecipeImageRequest(3L, 9L));

        assertThat(saved.version()).isEqualTo(4L);
        assertThat(saved.image().id()).isEqualTo(9L);
        assertThat(draft.getImageAssetId()).isEqualTo(9L);
        verify(recipeMapper).selectByIdForUpdate(101L);
        verify(imageAssetService).requireApproved(9L);
        verify(recipeMapper, times(1)).updateById(argThat((DinnerRecipeEntity row) ->
                row.getVersion() == 4L
                        && row.getLastModifiedBy().equals(7L)
                        && row.getImageAssetId().equals(9L)));
        verify(queryService).detail(7L, 101L);
    }

    @Test
    void nullImageSelectionClearsTheAssociationAndAdvancesOnce() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 3L);
        draft.setImageAssetId(9L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(queryService.detail(7L, 101L)).thenReturn(new RecipeDraftResponse(
                101L, "DRAFT", 4L, null, null, null, null, null,
                List.of(), null, null,
                List.of("BASIC", "INGREDIENTS", "METHOD", "IMAGE"), null));

        RecipeDraftResponse saved = service.selectImage(
                7L, 101L, new SelectRecipeImageRequest(3L, null));

        assertThat(saved.version()).isEqualTo(4L);
        assertThat(draft.getImageAssetId()).isNull();
        verifyNoInteractions(imageAssetService);
        verify(recipeMapper, times(1)).updateById(draft);
    }

    @Test
    void disabledImageIsRejectedBeforeDraftMutationWithExactError() {
        DinnerRecipeEntity draft = draft(101L, 7L, 70L, 3L);
        when(recipeMapper.selectByIdForUpdate(101L)).thenReturn(draft);
        when(imageAssetService.requireApproved(8L))
                .thenThrow(new BusinessException(ErrorCode.DINNER_RECIPE_IMAGE_INVALID));

        assertThatThrownBy(() -> service.selectImage(
                7L, 101L, new SelectRecipeImageRequest(3L, 8L)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode())
                            .isEqualTo(ErrorCode.DINNER_RECIPE_IMAGE_INVALID);
                    assertThat(error.getMessage())
                            .isEqualTo("Dinner recipe image is unavailable");
                });

        assertThat(draft.getVersion()).isEqualTo(3L);
        assertThat(draft.getImageAssetId()).isNull();
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
        verifyNoInteractions(queryService);
    }

    @Test
    void staleImageSelectionNeverResolvesOrMutatesTheAsset() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 7L, 70L, 4L));

        assertThatThrownBy(() -> service.selectImage(
                7L, 101L, new SelectRecipeImageRequest(3L, 9L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT));

        verifyNoInteractions(imageAssetService, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    @Test
    void imageAssetAssociationAllowsExplicitNullPersistence() throws Exception {
        TableField mapping = DinnerRecipeEntity.class.getDeclaredField("imageAssetId")
                .getAnnotation(TableField.class);

        assertThat(mapping.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
    }

    @Test
    void anotherUsersDraftIsForbiddenBeforeAnyChildMutation() {
        when(recipeMapper.selectByIdForUpdate(101L))
                .thenReturn(draft(101L, 8L, 70L, 1L));

        assertThatThrownBy(() -> service.replaceIngredients(
                7L, 101L, new ReplaceRecipeIngredientsRequest(1L, List.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(recipeIngredientMapper, ingredientMapper, queryService);
        verify(recipeMapper, never()).updateById(any(DinnerRecipeEntity.class));
    }

    private DinnerHouseholdMemberEntity member(Long userId, Long householdId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(11L);
        member.setUserId(userId);
        member.setHouseholdId(householdId);
        member.setRole("OWNER");
        member.setStatus("ACTIVE");
        member.setHistoryVisibleFrom(LocalDateTime.of(1970, 1, 1, 0, 0));
        member.setVersion(1L);
        return member;
    }

    private DinnerHouseholdEntity household(Long id, String status) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setStatus(status);
        household.setTimezone("Asia/Shanghai");
        household.setVersion(1L);
        return household;
    }

    private DinnerRecipeEntity recipe(Long id, Long creatorId, String status) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setHouseholdId(70L);
        recipe.setCreatorId(creatorId);
        recipe.setStatus(status);
        recipe.setVersion(1L);
        return recipe;
    }

    private DinnerRecipeEntity draft(Long id, Long creatorId, Long householdId, Long version) {
        DinnerRecipeEntity draft = recipe(id, creatorId, "DRAFT");
        draft.setHouseholdId(householdId);
        draft.setVersion(version);
        return draft;
    }

    private DinnerRecipeMethodEntity defaultMethod(Long id, Long recipeId, Integer minutes) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(recipeId);
        method.setEstimatedMinutes(minutes);
        method.setIsDefault(true);
        method.setStatus("ACTIVE");
        method.setSortOrder(0);
        return method;
    }

    private DinnerIngredientEntity ingredient(
            Long id,
            String scope,
            Long householdId,
            String status,
            String name
    ) {
        DinnerIngredientEntity ingredient = new DinnerIngredientEntity();
        ingredient.setId(id);
        ingredient.setScope(scope);
        ingredient.setHouseholdId(householdId);
        ingredient.setStatus(status);
        ingredient.setName(name);
        return ingredient;
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

    private RecipeDraftResponse response(
            DinnerRecipeEntity draft,
            List<RecipeIngredientResponse> ingredients,
            RecipeMethodResponse method
    ) {
        List<String> incomplete = new ArrayList<>();
        incomplete.add("BASIC");
        if (ingredients.isEmpty()) {
            incomplete.add("INGREDIENTS");
        }
        if (method == null) {
            incomplete.add("METHOD");
        }
        incomplete.add("IMAGE");
        return new RecipeDraftResponse(
                draft.getId(), draft.getStatus(), draft.getVersion(), draft.getName(),
                draft.getCategory(), draft.getFlavor(), draft.getServings(),
                draft.getEstimatedMinutes(), ingredients, method, null, incomplete, null);
    }
}
