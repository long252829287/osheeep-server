package com.osheeep.server.dinner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.menu.entity.DinnerMenuSelectionEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecordSnapshotAssemblerTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerImageAssetService imageAssetService;

    private DinnerRecordSnapshotAssembler assembler;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeMethodStepEntity.class);
        assembler = new DinnerRecordSnapshotAssembler(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void assemblesSystemAndHouseholdSnapshotsInStableOrderWithOneBatchPerTable() {
        DinnerRecipeEntity system = systemRecipe(1L, null);
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        List<DinnerMenuSelectionEntity> selections = List.of(
                householdSelection(14L, 8L, 8L, 21L),
                systemSelection(1L, 7L),
                householdSelection(14L, 7L, 8L, 21L));
        when(recipeMapper.selectByIds(List.of(1L, 14L)))
                .thenReturn(List.of(family, system));
        when(ingredientMapper.selectWithIngredientNames(List.of(1L, 14L))).thenReturn(List.of(
                ingredient(14L, 202L, "葱", false, 1),
                ingredient(1L, 102L, "盐", false, 0),
                ingredient(14L, 201L, "鸡蛋", true, 0,
                        "HOUSEHOLD", 70L, "ACTIVE"),
                ingredient(1L, 101L, "番茄", true, 0)));
        when(methodMapper.selectByIds(List.of(21L))).thenReturn(List.of(
                method(21L, 14L, "家常做法", "炒")));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(302L, 21L, "盛盘", 1),
                step(301L, 21L, "翻炒", 0)));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        var drafts = assembler.assemble(70L, selections);

        assertThat(drafts).extracting(DinnerRecordSnapshotAssembler.SnapshotDraft::recipeId)
                .containsExactly(1L, 14L);
        assertThat(drafts.getFirst().servings()).isNull();
        assertThat(drafts.getFirst().methodId()).isNull();
        assertThat(drafts.getFirst().ingredients())
                .extracting(ingredient -> ingredient.ingredientId())
                .containsExactly(101L, 102L);
        assertThat(drafts.get(1).selectedByUserIds()).containsExactly(7L, 8L);
        assertThat(drafts.get(1).steps())
                .extracting(step -> step.sortOrder())
                .containsExactly(0, 1);
        assertThat(drafts.get(1).ingredients())
                .extracting(ingredient -> ingredient.sortOrder())
                .containsExactly(0, 1);
        assertThat(drafts.get(1).imagePath())
                .isEqualTo("https://www.osheeep.com/media/recipes/family-list.webp");
        verify(recipeMapper).selectByIds(List.of(1L, 14L));
        verify(ingredientMapper).selectWithIngredientNames(List.of(1L, 14L));
        verify(methodMapper).selectByIds(List.of(21L));
        verify(stepMapper).selectList(any());
        verify(imageAssetService).findApprovedByIds(List.of(91L));
    }

    @Test
    void confirmedMenuWithoutSelectionsIsInvalidBeforeAnyRead() {
        assertInvalid(() -> assembler.assemble(70L, List.of()));
        verifyNoInteractions(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void conflictingSavedVersionsAreInvalidBeforeAnyRead() {
        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L),
                householdSelection(14L, 8L, 9L, 21L))));
        verifyNoInteractions(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void conflictingSavedMethodsAreInvalidBeforeAnyRead() {
        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L),
                householdSelection(14L, 8L, 8L, 22L))));
        verifyNoInteractions(
                recipeMapper, ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void currentRecipeVersionMustEqualSavedVersion() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 9L, 2, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void householdRecipeMustBelongToMenuHousehold() {
        DinnerRecipeEntity family = householdRecipe(14L, 71L, 8L, 2, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void methodMustBelongToSelectedRecipe() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 15L, "错误做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void missingApprovedImageIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)), Map.of());

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void systemRecipeWithoutRequiredIngredientIsInvalid() {
        DinnerRecipeEntity system = systemRecipe(1L, null);
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(system));
        when(ingredientMapper.selectWithIngredientNames(List.of(1L))).thenReturn(List.of(
                ingredient(1L, 101L, "盐", false, 0)));

        assertInvalid(() -> assembler.assemble(70L, List.of(systemSelection(1L, 7L))));

        verifyNoInteractions(methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void householdRecipeWithoutRequiredIngredientIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family,
                List.of(ingredient(14L, 201L, "葱", false, 0)),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void householdRecipeWithAnotherHouseholdsIngredientIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family,
                List.of(ingredient(14L, 901L, "另一个家庭的食材", true, 0,
                        "HOUSEHOLD", 71L, "ACTIVE")),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void householdRecipeWithInactiveIngredientIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family,
                List.of(ingredient(14L, 902L, "停用食材", true, 0,
                        "SYSTEM", null, "INACTIVE")),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @ParameterizedTest
    @MethodSource("invalidSystemIngredientRows")
    void systemRecipeIngredientMustBeActiveAndSystem(
            DinnerRecipeIngredientRow invalidIngredient
    ) {
        DinnerRecipeEntity system = systemRecipe(1L, null);
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(system));
        when(ingredientMapper.selectWithIngredientNames(List.of(1L)))
                .thenReturn(List.of(invalidIngredient));

        assertInvalid(() -> assembler.assemble(70L, List.of(systemSelection(1L, 7L))));
    }

    private static Stream<DinnerRecipeIngredientRow> invalidSystemIngredientRows() {
        return Stream.of(
                ingredient(1L, 901L, "家庭食材", true, 0,
                        "HOUSEHOLD", 70L, "ACTIVE"),
                ingredient(1L, 902L, "停用系统食材", true, 0,
                        "SYSTEM", null, "INACTIVE"));
    }

    @Test
    void missingMethodRowIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(ingredientMapper.selectWithIngredientNames(List.of(14L)))
                .thenReturn(validIngredients(14L));
        when(methodMapper.selectByIds(List.of(21L))).thenReturn(List.of());

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void missingMethodStepIsInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 14L, "家常做法", "炒")), List.of(),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void missingAndDuplicateRecipeRowsAreInvalid() {
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of());
        assertInvalid(() -> assembler.assemble(70L, List.of(systemSelection(1L, 7L))));
        verify(ingredientMapper, never()).selectWithIngredientNames(any());

        org.mockito.Mockito.reset(recipeMapper);
        DinnerRecipeEntity system = systemRecipe(1L, null);
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(system, system));
        assertInvalid(() -> assembler.assemble(70L, List.of(systemSelection(1L, 7L))));
    }

    @Test
    void duplicateMethodRowsAreInvalid() {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, 2, 91L);
        DinnerRecipeMethodEntity saved = method(21L, 14L, "家常做法", "炒");
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(family));
        when(ingredientMapper.selectWithIngredientNames(List.of(14L)))
                .thenReturn(validIngredients(14L));
        when(methodMapper.selectByIds(List.of(21L))).thenReturn(List.of(saved, saved));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    @Test
    void ingredientRowForUnselectedRecipeIsInvalid() {
        DinnerRecipeEntity system = systemRecipe(1L, null);
        when(recipeMapper.selectByIds(List.of(1L))).thenReturn(List.of(system));
        when(ingredientMapper.selectWithIngredientNames(List.of(1L))).thenReturn(List.of(
                ingredient(1L, 101L, "番茄", true, 0),
                ingredient(99L, 999L, "越界食材", true, 0)));

        assertInvalid(() -> assembler.assemble(70L, List.of(systemSelection(1L, 7L))));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void householdServingsMustBeWithinPublicationRange(int servings) {
        DinnerRecipeEntity family = householdRecipe(14L, 70L, 8L, servings, 91L);
        stubHouseholdAggregate(family, validIngredients(14L),
                List.of(method(21L, 14L, "家常做法", "炒")),
                List.of(step(301L, 21L, "翻炒", 0)),
                Map.of(91L, approvedImage(91L)));

        assertInvalid(() -> assembler.assemble(70L, List.of(
                householdSelection(14L, 7L, 8L, 21L))));
    }

    private void stubHouseholdAggregate(
            DinnerRecipeEntity recipe,
            List<DinnerRecipeIngredientRow> ingredients,
            List<DinnerRecipeMethodEntity> methods,
            List<DinnerRecipeMethodStepEntity> steps,
            Map<Long, ImageAssetResponse> images
    ) {
        when(recipeMapper.selectByIds(List.of(14L))).thenReturn(List.of(recipe));
        when(ingredientMapper.selectWithIngredientNames(List.of(14L)))
                .thenReturn(ingredients);
        when(methodMapper.selectByIds(List.of(21L))).thenReturn(methods);
        when(stepMapper.selectList(any())).thenReturn(steps);
        when(imageAssetService.findApprovedByIds(List.of(91L))).thenReturn(images);
    }

    private static List<DinnerRecipeIngredientRow> validIngredients(Long recipeId) {
        return List.of(ingredient(recipeId, 201L, "鸡蛋", true, 0));
    }

    private static DinnerRecipeEntity systemRecipe(Long id, Integer servings) {
        DinnerRecipeEntity recipe = baseRecipe(id, "系统菜");
        recipe.setScope("SYSTEM");
        recipe.setVersion(1L);
        recipe.setServings(servings);
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
        return recipe;
    }

    private static DinnerRecipeEntity householdRecipe(
            Long id,
            Long householdId,
            Long version,
            Integer servings,
            Long imageAssetId
    ) {
        DinnerRecipeEntity recipe = baseRecipe(id, "自家番茄炒蛋");
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(householdId);
        recipe.setVersion(version);
        recipe.setServings(servings);
        recipe.setImageAssetId(imageAssetId);
        return recipe;
    }

    private static DinnerRecipeEntity baseRecipe(Long id, String name) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setName(name);
        recipe.setCategory("家常菜");
        recipe.setFlavor("鲜香");
        recipe.setEstimatedMinutes(10);
        recipe.setStatus("PUBLISHED");
        return recipe;
    }

    private static DinnerRecipeIngredientRow ingredient(
            Long recipeId,
            Long ingredientId,
            String name,
            boolean required,
            int sortOrder
    ) {
        return new DinnerRecipeIngredientRow(
                recipeId, ingredientId, name, BigDecimal.ONE, "个", required, sortOrder);
    }

    private static DinnerRecipeIngredientRow ingredient(
            Long recipeId,
            Long ingredientId,
            String name,
            boolean required,
            int sortOrder,
            String ingredientScope,
            Long ingredientHouseholdId,
            String ingredientStatus
    ) {
        return new DinnerRecipeIngredientRow(
                recipeId, ingredientId, name, BigDecimal.ONE, "个", required, sortOrder,
                ingredientScope, ingredientHouseholdId, ingredientStatus);
    }

    private static DinnerRecipeMethodEntity method(
            Long id,
            Long recipeId,
            String name,
            String cookingStyle
    ) {
        DinnerRecipeMethodEntity method = new DinnerRecipeMethodEntity();
        method.setId(id);
        method.setRecipeId(recipeId);
        method.setName(name);
        method.setCookingStyle(cookingStyle);
        method.setStatus("ACTIVE");
        return method;
    }

    private static DinnerRecipeMethodStepEntity step(
            Long id,
            Long methodId,
            String instruction,
            int sortOrder
    ) {
        DinnerRecipeMethodStepEntity step = new DinnerRecipeMethodStepEntity();
        step.setId(id);
        step.setMethodId(methodId);
        step.setInstruction(instruction);
        step.setSortOrder(sortOrder);
        return step;
    }

    private static DinnerMenuSelectionEntity systemSelection(Long recipeId, Long userId) {
        return selection(recipeId, userId, 1L, null);
    }

    private static DinnerMenuSelectionEntity householdSelection(
            Long recipeId,
            Long userId,
            Long version,
            Long methodId
    ) {
        return selection(recipeId, userId, version, methodId);
    }

    private static DinnerMenuSelectionEntity selection(
            Long recipeId,
            Long userId,
            Long version,
            Long methodId
    ) {
        DinnerMenuSelectionEntity selection = new DinnerMenuSelectionEntity();
        selection.setMenuId(31L);
        selection.setRecipeId(recipeId);
        selection.setUserId(userId);
        selection.setRecipeVersion(version);
        selection.setMethodId(methodId);
        return selection;
    }

    private static ImageAssetResponse approvedImage(Long id) {
        return new ImageAssetResponse(
                id, "番茄炒蛋",
                "https://www.osheeep.com/media/recipes/family-list.webp",
                "https://www.osheeep.com/media/recipes/family-detail.webp",
                "https://example.com/source", "author", "CC BY 4.0",
                "https://creativecommons.org/licenses/by/4.0/",
                LocalDate.of(2026, 7, 1), 1200, 900);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.DINNER_RECIPE_INVALID));
    }
}
