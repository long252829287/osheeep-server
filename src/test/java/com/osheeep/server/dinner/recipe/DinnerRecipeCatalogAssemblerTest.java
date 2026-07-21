package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osheeep.server.dinner.image.DinnerImageAssetService;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodSummaryResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodEntity;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeMethodStepEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeIngredientRow;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodMapper;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMethodStepMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeCatalogAssemblerTest {

    @Mock private DinnerRecipeIngredientMapper ingredientMapper;
    @Mock private DinnerRecipeMethodMapper methodMapper;
    @Mock private DinnerRecipeMethodStepMapper stepMapper;
    @Mock private DinnerImageAssetService imageAssetService;

    private DinnerRecipeCatalogAssembler assembler;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "test");
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeMethodEntity.class);
        TableInfoHelper.initTableInfo(assistant, DinnerRecipeMethodStepEntity.class);
        assembler = new DinnerRecipeCatalogAssembler(
                ingredientMapper, methodMapper, stepMapper, imageAssetService,
                new RecipeDraftValidator());
    }

    @Test
    void assemblesSystemAndHouseholdRowsInBatches() {
        DinnerRecipeEntity system = systemRecipe(1L);
        DinnerRecipeEntity household = householdRecipe(14L, 70L, 8L, 91L);
        when(ingredientMapper.selectWithIngredientNames(List.of(1L, 14L))).thenReturn(List.of(
                ingredient(14L, 102L, "鸡蛋", false, 1),
                ingredient(1L, 101L, "鸡蛋", true, 0),
                ingredient(14L, 101L, "番茄", true, 0,
                        "HOUSEHOLD", 70L, "ACTIVE")));
        when(methodMapper.selectList(any())).thenReturn(List.of(
                method(21L, 14L, "家常做法", "炒")));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(22L, 21L, "盛盘", 1),
                step(21L, 21L, "翻炒", 0)));
        when(imageAssetService.findApprovedByIds(List.of(91L))).thenReturn(Map.of(
                91L, approvedImage(91L)));

        var entries = assembler.assemble(List.of(system, household));

        assertThat(entries.get(1L).imagePath()).isEqualTo("/assets/recipes/1.jpg");
        assertThat(entries.get(1L).defaultMethod()).isNull();
        assertThat(entries.get(14L).imagePath())
                .isEqualTo("https://www.osheeep.com/media/recipes/tomato-with-egg-list.webp");
        assertThat(entries.get(14L).defaultMethod())
                .isEqualTo(new RecipeMethodSummaryResponse(21L, "家常做法", "炒"));
        assertThat(entries.get(14L).ingredients())
                .extracting(RecipeIngredientResponse::sortOrder)
                .containsExactly(0, 1);
        verify(ingredientMapper).selectWithIngredientNames(List.of(1L, 14L));
        verify(methodMapper).selectList(any());
        verify(stepMapper).selectList(any());
        verify(imageAssetService).findApprovedByIds(List.of(91L));
        verifyNoMoreInteractions(
                ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("damagedPublishedHouseholdAggregates")
    void omitsDamagedHouseholdAggregateWithoutLeakingDraftFields(
            DamagedAggregateFixture fixture
    ) {
        DinnerRecipeEntity household = householdRecipe(14L, 70L, 8L, 91L);
        when(ingredientMapper.selectWithIngredientNames(List.of(14L)))
                .thenReturn(fixture.ingredients());
        when(methodMapper.selectList(any())).thenReturn(fixture.methods());
        if (!fixture.methods().isEmpty()) {
            when(stepMapper.selectList(any())).thenReturn(fixture.steps());
        }
        when(imageAssetService.findApprovedByIds(List.of(91L))).thenReturn(fixture.images());

        assertThat(assembler.assemble(List.of(household))).isEmpty();
    }

    @Test
    void returnsEmptyWithoutQueryingDependencies() {
        assertThat(assembler.assemble(List.of())).isEmpty();
        verifyNoMoreInteractions(
                ingredientMapper, methodMapper, stepMapper, imageAssetService);
    }

    @Test
    void rejectsDuplicateActiveDefaultMethodsInsteadOfChoosingOne() {
        DinnerRecipeEntity household = householdRecipe(14L, 70L, 8L, 91L);
        when(ingredientMapper.selectWithIngredientNames(List.of(14L))).thenReturn(List.of(
                ingredient(14L, 101L, "番茄", true, 0)));
        when(methodMapper.selectList(any())).thenReturn(List.of(
                method(21L, 14L, "家常做法", "炒"),
                method(22L, 14L, "另一个默认做法", "煮")));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(31L, 21L, "翻炒", 0), step(32L, 22L, "煮熟", 0)));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        assertThat(assembler.assemble(List.of(household))).isEmpty();
    }

    @Test
    void omitsWholeHouseholdAggregateForAnotherHouseholdsIngredientWithoutLoggingItsName() {
        String privateIngredientName = "另一个家庭的私有食材";
        DinnerRecipeEntity system = systemRecipe(1L);
        DinnerRecipeEntity household = householdRecipe(14L, 70L, 8L, 91L);
        when(ingredientMapper.selectWithIngredientNames(List.of(1L, 14L))).thenReturn(List.of(
                ingredient(1L, 101L, "番茄", true, 0),
                ingredient(14L, 101L, "番茄", true, 0),
                ingredient(14L, 901L, privateIngredientName, true, 1,
                        "HOUSEHOLD", 71L, "ACTIVE")));
        when(methodMapper.selectList(any())).thenReturn(List.of(
                method(21L, 14L, "家常做法", "炒")));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(31L, 21L, "翻炒", 0)));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        DinnerRecipeCatalogAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        Map<Long, DinnerRecipeCatalogAssembler.CatalogEntry> entries;
        try {
            entries = assembler.assemble(List.of(system, household));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(entries).containsOnlyKeys(1L);
        assertThat(entries.values().stream()
                .flatMap(entry -> entry.ingredients().stream())
                .map(RecipeIngredientResponse::name)
                .toList()).doesNotContain(privateIngredientName);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("recipeId=14", "ingredientId=901", "ingredientHouseholdId=71"))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain(privateIngredientName));
    }

    @Test
    void omitsWholeHouseholdAggregateForInactiveIngredient() {
        DinnerRecipeEntity household = householdRecipe(14L, 70L, 8L, 91L);
        when(ingredientMapper.selectWithIngredientNames(List.of(14L))).thenReturn(List.of(
                ingredient(14L, 101L, "番茄", true, 0),
                ingredient(14L, 102L, "停用食材", false, 1,
                        "SYSTEM", null, "INACTIVE")));
        when(methodMapper.selectList(any())).thenReturn(List.of(
                method(21L, 14L, "家常做法", "炒")));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(31L, 21L, "翻炒", 0)));
        when(imageAssetService.findApprovedByIds(List.of(91L)))
                .thenReturn(Map.of(91L, approvedImage(91L)));

        assertThat(assembler.assemble(List.of(household))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidSystemIngredientRows")
    void omitsSystemAggregateForNonSystemOrInactiveIngredient(
            DinnerRecipeIngredientRow invalidIngredient
    ) {
        DinnerRecipeEntity system = systemRecipe(1L);
        when(ingredientMapper.selectWithIngredientNames(List.of(1L)))
                .thenReturn(List.of(invalidIngredient));

        assertThat(assembler.assemble(List.of(system))).isEmpty();
    }

    private static Stream<DinnerRecipeIngredientRow> invalidSystemIngredientRows() {
        return Stream.of(
                ingredient(1L, 901L, "家庭食材", true, 0,
                        "HOUSEHOLD", 70L, "ACTIVE"),
                ingredient(1L, 902L, "停用系统食材", true, 0,
                        "SYSTEM", null, "INACTIVE"));
    }

    private static Stream<DamagedAggregateFixture> damagedPublishedHouseholdAggregates() {
        List<DinnerRecipeIngredientRow> validIngredients = List.of(
                ingredient(14L, 101L, "番茄", true, 0));
        List<DinnerRecipeMethodEntity> validMethod = List.of(
                method(21L, 14L, "家常做法", "炒"));
        List<DinnerRecipeMethodStepEntity> validSteps = List.of(
                step(31L, 21L, "翻炒", 0));
        Map<Long, ImageAssetResponse> validImage = Map.of(91L, approvedImage(91L));
        return Stream.of(
                new DamagedAggregateFixture(
                        "only optional ingredients",
                        List.of(ingredient(14L, 101L, "番茄", false, 0)),
                        validMethod, validSteps, validImage),
                new DamagedAggregateFixture(
                        "blank method name", validIngredients,
                        List.of(method(21L, 14L, " ", "炒")), validSteps, validImage),
                new DamagedAggregateFixture(
                        "blank cooking style", validIngredients,
                        List.of(method(21L, 14L, "家常做法", " ")), validSteps, validImage),
                new DamagedAggregateFixture(
                        "zero steps", validIngredients, validMethod, List.of(), validImage),
                new DamagedAggregateFixture(
                        "blank step instruction", validIngredients, validMethod,
                        List.of(step(31L, 21L, " ", 0)), validImage),
                new DamagedAggregateFixture(
                        "more than twelve steps", validIngredients, validMethod,
                        IntStream.range(0, 13)
                                .mapToObj(index -> step(
                                        31L + index, 21L, "步骤" + index, index))
                                .toList(),
                        validImage),
                new DamagedAggregateFixture(
                        "missing or unapproved image", validIngredients, validMethod,
                        validSteps, Map.of()),
                new DamagedAggregateFixture(
                        "approved image without list url", validIngredients, validMethod,
                        validSteps, Map.of(91L, imageWithListUrl(91L, " "))));
    }

    private static DinnerRecipeEntity systemRecipe(Long id) {
        DinnerRecipeEntity recipe = baseRecipe(id);
        recipe.setScope("SYSTEM");
        recipe.setImagePath("/assets/recipes/" + id + ".jpg");
        recipe.setVersion(1L);
        return recipe;
    }

    private static DinnerRecipeEntity householdRecipe(
            Long id,
            Long householdId,
            Long version,
            Long imageAssetId
    ) {
        DinnerRecipeEntity recipe = baseRecipe(id);
        recipe.setScope("HOUSEHOLD");
        recipe.setHouseholdId(householdId);
        recipe.setCreatorId(7L);
        recipe.setVersion(version);
        recipe.setServings(2);
        recipe.setImageAssetId(imageAssetId);
        return recipe;
    }

    private static DinnerRecipeEntity baseRecipe(Long id) {
        DinnerRecipeEntity recipe = new DinnerRecipeEntity();
        recipe.setId(id);
        recipe.setName("番茄炒蛋");
        recipe.setCategory("家常菜");
        recipe.setFlavor("酸甜");
        recipe.setEstimatedMinutes(15);
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
        method.setIsDefault(true);
        method.setStatus("ACTIVE");
        method.setSortOrder(0);
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

    private static ImageAssetResponse approvedImage(Long id) {
        return imageWithListUrl(
                id,
                "https://www.osheeep.com/media/recipes/tomato-with-egg-list.webp");
    }

    private static ImageAssetResponse imageWithListUrl(Long id, String listUrl) {
        return new ImageAssetResponse(
                id, "番茄炒蛋",
                listUrl,
                "https://www.osheeep.com/media/recipes/tomato-with-egg-detail.webp",
                "https://example.com/source", "author", "CC BY 4.0",
                "https://creativecommons.org/licenses/by/4.0/",
                LocalDate.of(2026, 7, 1), 1200, 900);
    }

    private record DamagedAggregateFixture(
            String name,
            List<DinnerRecipeIngredientRow> ingredients,
            List<DinnerRecipeMethodEntity> methods,
            List<DinnerRecipeMethodStepEntity> steps,
            Map<Long, ImageAssetResponse> images
    ) {
        @Override
        public String toString() {
            return name;
        }
    }
}
