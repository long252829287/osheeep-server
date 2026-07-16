package com.osheeep.server.dinner.recipe;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.osheeep.server.dinner.image.dto.ImageAssetResponse;
import com.osheeep.server.dinner.image.entity.DinnerImageAssetEntity;
import com.osheeep.server.dinner.image.mapper.DinnerImageAssetMapper;
import com.osheeep.server.dinner.recipe.DinnerRecipeAuthorizer.RecipeAccess;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeListItemResponse;
import com.osheeep.server.dinner.recipe.dto.FamilyRecipeTab;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeIngredientResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodResponse;
import com.osheeep.server.dinner.recipe.dto.RecipeMethodStepResponse;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DinnerRecipeQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String MEMBER_FALLBACK = "家庭成员";

    private final DinnerRecipeMapper recipeMapper;
    private final DinnerRecipeIngredientMapper ingredientMapper;
    private final DinnerRecipeMethodMapper methodMapper;
    private final DinnerRecipeMethodStepMapper stepMapper;
    private final DinnerImageAssetMapper imageMapper;
    private final UserMapper userMapper;
    private final DinnerRecipeAuthorizer authorizer;

    public DinnerRecipeQueryService(
            DinnerRecipeMapper recipeMapper,
            DinnerRecipeIngredientMapper ingredientMapper,
            DinnerRecipeMethodMapper methodMapper,
            DinnerRecipeMethodStepMapper stepMapper,
            DinnerImageAssetMapper imageMapper,
            UserMapper userMapper,
            DinnerRecipeAuthorizer authorizer
    ) {
        this.recipeMapper = recipeMapper;
        this.ingredientMapper = ingredientMapper;
        this.methodMapper = methodMapper;
        this.stepMapper = stepMapper;
        this.imageMapper = imageMapper;
        this.userMapper = userMapper;
        this.authorizer = authorizer;
    }

    public List<FamilyRecipeListItemResponse> list(Long userId, FamilyRecipeTab tab) {
        RecipeAccess access = authorizer.requireMembership(userId);
        var query = Wrappers.<DinnerRecipeEntity>lambdaQuery();
        if (tab == FamilyRecipeTab.DRAFT) {
            query.eq(DinnerRecipeEntity::getCreatorId, userId)
                    .eq(DinnerRecipeEntity::getStatus, "DRAFT");
        } else {
            query.eq(DinnerRecipeEntity::getHouseholdId, access.householdId())
                    .eq(DinnerRecipeEntity::getScope, "HOUSEHOLD")
                    .eq(DinnerRecipeEntity::getStatus, tab.name());
        }
        query.orderByDesc(DinnerRecipeEntity::getUpdatedAt)
                .orderByDesc(DinnerRecipeEntity::getId);
        List<DinnerRecipeEntity> recipes = recipeMapper.selectList(query);
        if (recipes.isEmpty()) {
            return List.of();
        }

        AggregateData aggregate = loadAggregate(recipes);
        Map<Long, UserEntity> usersById = loadUsers(recipes);
        return recipes.stream()
                .map(recipe -> listResponse(recipe, aggregate, usersById))
                .toList();
    }

    public RecipeDraftResponse detail(Long userId, Long recipeId) {
        DinnerRecipeEntity recipe = authorizer.requireVisible(userId, recipeId);
        AggregateData aggregate = loadAggregate(List.of(recipe));
        return detailResponse(recipe, aggregate);
    }

    private AggregateData loadAggregate(List<DinnerRecipeEntity> recipes) {
        List<Long> recipeIds = recipes.stream().map(DinnerRecipeEntity::getId).toList();
        Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe =
                loadIngredients(recipeIds);
        Map<Long, RecipeMethodResponse> methodsByRecipe = loadDefaultMethods(recipeIds);
        Map<Long, ImageAssetResponse> imagesById = loadImages(recipes);
        return new AggregateData(ingredientsByRecipe, methodsByRecipe, imagesById);
    }

    private Map<Long, List<RecipeIngredientResponse>> loadIngredients(List<Long> recipeIds) {
        return ingredientMapper.selectWithIngredientNames(recipeIds).stream()
                .sorted(Comparator.comparing(DinnerRecipeIngredientRow::recipeId)
                        .thenComparingInt(DinnerRecipeIngredientRow::sortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeIngredientRow::recipeId,
                        Collectors.mapping(row -> new RecipeIngredientResponse(
                                        row.ingredientId(), row.name(), row.quantity(), row.unit(),
                                        row.required(), row.sortOrder()),
                                Collectors.toList())));
    }

    private Map<Long, RecipeMethodResponse> loadDefaultMethods(List<Long> recipeIds) {
        List<DinnerRecipeMethodEntity> methods = methodMapper.selectList(
                Wrappers.<DinnerRecipeMethodEntity>lambdaQuery()
                        .in(DinnerRecipeMethodEntity::getRecipeId, recipeIds)
                        .eq(DinnerRecipeMethodEntity::getIsDefault, true)
                        .eq(DinnerRecipeMethodEntity::getStatus, "ACTIVE")
                        .orderByAsc(DinnerRecipeMethodEntity::getRecipeId)
                        .orderByAsc(DinnerRecipeMethodEntity::getSortOrder)
                        .orderByAsc(DinnerRecipeMethodEntity::getId));
        if (methods.isEmpty()) {
            return Map.of();
        }
        List<Long> methodIds = methods.stream().map(DinnerRecipeMethodEntity::getId).toList();
        Map<Long, List<RecipeMethodStepResponse>> stepsByMethod = stepMapper.selectList(
                        Wrappers.<DinnerRecipeMethodStepEntity>lambdaQuery()
                                .in(DinnerRecipeMethodStepEntity::getMethodId, methodIds)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getMethodId)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getSortOrder)
                                .orderByAsc(DinnerRecipeMethodStepEntity::getId))
                .stream()
                .sorted(Comparator.comparing(DinnerRecipeMethodStepEntity::getMethodId)
                        .thenComparingInt(DinnerRecipeMethodStepEntity::getSortOrder))
                .collect(Collectors.groupingBy(
                        DinnerRecipeMethodStepEntity::getMethodId,
                        Collectors.mapping(step -> new RecipeMethodStepResponse(
                                        step.getInstruction(), step.getSortOrder()),
                                Collectors.toList())));
        return methods.stream().collect(Collectors.toMap(
                DinnerRecipeMethodEntity::getRecipeId,
                method -> new RecipeMethodResponse(
                        method.getId(), method.getName(), method.getCookingStyle(),
                        stepsByMethod.getOrDefault(method.getId(), List.of())),
                (first, ignored) -> first));
    }

    private Map<Long, ImageAssetResponse> loadImages(List<DinnerRecipeEntity> recipes) {
        List<Long> imageIds = recipes.stream()
                .map(DinnerRecipeEntity::getImageAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        return imageMapper.selectByIds(imageIds).stream()
                .collect(Collectors.toMap(
                        DinnerImageAssetEntity::getId,
                        this::imageResponse));
    }

    private Map<Long, UserEntity> loadUsers(List<DinnerRecipeEntity> recipes) {
        Set<Long> userIds = new LinkedHashSet<>();
        recipes.forEach(recipe -> {
            if (recipe.getCreatorId() != null) {
                userIds.add(recipe.getCreatorId());
            }
            if (recipe.getLastModifiedBy() != null) {
                userIds.add(recipe.getLastModifiedBy());
            }
        });
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private FamilyRecipeListItemResponse listResponse(
            DinnerRecipeEntity recipe,
            AggregateData aggregate,
            Map<Long, UserEntity> usersById
    ) {
        List<String> incomplete = incompleteSteps(recipe, aggregate);
        ImageAssetResponse image = selectedImage(recipe, aggregate);
        return new FamilyRecipeListItemResponse(
                recipe.getId(), recipe.getStatus(), recipe.getName(),
                image == null ? recipe.getImagePath() : image.listUrl(),
                recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                recipe.getEstimatedMinutes(), recipe.getVersion(), recipe.getCreatorId(),
                memberName(usersById.get(recipe.getCreatorId())),
                recipe.getLastModifiedBy(),
                memberName(usersById.get(recipe.getLastModifiedBy())),
                incomplete.isEmpty() ? "PREVIEW" : incomplete.getFirst(),
                toInstant(recipe.getUpdatedAt()));
    }

    private RecipeDraftResponse detailResponse(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        return new RecipeDraftResponse(
                recipe.getId(), recipe.getStatus(), recipe.getVersion(), recipe.getName(),
                recipe.getCategory(), recipe.getFlavor(), recipe.getServings(),
                recipe.getEstimatedMinutes(),
                aggregate.ingredientsByRecipe().getOrDefault(recipe.getId(), List.of()),
                aggregate.methodsByRecipe().get(recipe.getId()),
                selectedImage(recipe, aggregate),
                incompleteSteps(recipe, aggregate), toInstant(recipe.getUpdatedAt()));
    }

    private List<String> incompleteSteps(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        List<String> incomplete = new ArrayList<>(4);
        if (!basicComplete(recipe)) {
            incomplete.add("BASIC");
        }
        if (aggregate.ingredientsByRecipe().getOrDefault(recipe.getId(), List.of()).isEmpty()) {
            incomplete.add("INGREDIENTS");
        }
        if (!methodComplete(aggregate.methodsByRecipe().get(recipe.getId()))) {
            incomplete.add("METHOD");
        }
        if (recipe.getImageAssetId() == null) {
            incomplete.add("IMAGE");
        }
        return List.copyOf(incomplete);
    }

    private boolean basicComplete(DinnerRecipeEntity recipe) {
        return StringUtils.hasText(recipe.getName())
                && StringUtils.hasText(recipe.getCategory())
                && StringUtils.hasText(recipe.getFlavor())
                && recipe.getServings() != null
                && recipe.getEstimatedMinutes() != null;
    }

    private ImageAssetResponse selectedImage(
            DinnerRecipeEntity recipe,
            AggregateData aggregate
    ) {
        return recipe.getImageAssetId() == null
                ? null
                : aggregate.imagesById().get(recipe.getImageAssetId());
    }

    private boolean methodComplete(RecipeMethodResponse method) {
        return method != null
                && !method.steps().isEmpty()
                && method.steps().stream()
                        .allMatch(step -> StringUtils.hasText(step.instruction()));
    }

    private ImageAssetResponse imageResponse(DinnerImageAssetEntity asset) {
        return new ImageAssetResponse(
                asset.getId(), asset.getDisplayName(), objectUrl(asset.getListObjectKey()),
                objectUrl(asset.getDetailObjectKey()), asset.getSourcePageUrl(), asset.getAuthor(),
                asset.getLicenseName(), asset.getLicenseUrl(), asset.getAcquiredOn(),
                asset.getOriginalWidth(), asset.getOriginalHeight());
    }

    private String objectUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey) || objectKey.startsWith("/")) {
            return objectKey;
        }
        return "/" + objectKey;
    }

    private String memberName(UserEntity user) {
        if (user == null) {
            return MEMBER_FALLBACK;
        }
        if (StringUtils.hasText(user.getDisplayName())) {
            return user.getDisplayName();
        }
        if (StringUtils.hasText(user.getUsername())) {
            return user.getUsername();
        }
        return MEMBER_FALLBACK;
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(SHANGHAI).toInstant();
    }

    private record AggregateData(
            Map<Long, List<RecipeIngredientResponse>> ingredientsByRecipe,
            Map<Long, RecipeMethodResponse> methodsByRecipe,
            Map<Long, ImageAssetResponse> imagesById
    ) {
    }
}
