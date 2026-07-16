package com.osheeep.server.dinner.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdEntity;
import com.osheeep.server.dinner.household.entity.DinnerHouseholdMemberEntity;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMapper;
import com.osheeep.server.dinner.household.mapper.DinnerHouseholdMemberMapper;
import com.osheeep.server.dinner.recipe.dto.RecipeDraftResponse;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DinnerRecipeDraftServiceTest {

    @Mock private DinnerRecipeMapper recipeMapper;
    @Mock private DinnerHouseholdMemberMapper memberMapper;
    @Mock private DinnerHouseholdMapper householdMapper;

    private DinnerRecipeAuthorizer authorizer;
    private DinnerRecipeDraftService service;

    @BeforeEach
    void setUp() {
        authorizer = new DinnerRecipeAuthorizer(memberMapper, householdMapper, recipeMapper);
        service = new DinnerRecipeDraftService(recipeMapper, authorizer);
    }

    @Test
    void createsVersionOneDraftForTheCurrentHouseholdAndOwner() {
        when(memberMapper.selectOne(any())).thenReturn(member(7L, 70L));
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
        when(memberMapper.selectOne(any())).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ARCHIVED"));

        assertThatThrownBy(() -> service.create(7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(recipeMapper, never()).insert(any(DinnerRecipeEntity.class));
    }

    @Test
    void ownedDraftAuthorizationRejectsARecipeThatIsNotTheCurrentUsersDraft() {
        when(memberMapper.selectOne(any())).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity published = recipe(101L, 7L, "PUBLISHED");
        when(recipeMapper.selectById(101L)).thenReturn(published);

        assertThatThrownBy(() -> authorizer.requireOwnedDraft(7L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void ownedDraftAuthorizationReturnsTheCurrentUsersDraft() {
        when(memberMapper.selectOne(any())).thenReturn(member(7L, 70L));
        when(householdMapper.selectById(70L)).thenReturn(household(70L, "ACTIVE"));
        DinnerRecipeEntity draft = recipe(101L, 7L, "DRAFT");
        when(recipeMapper.selectById(101L)).thenReturn(draft);

        assertThat(authorizer.requireOwnedDraft(7L, 101L)).isSameAs(draft);
    }

    private DinnerHouseholdMemberEntity member(Long userId, Long householdId) {
        DinnerHouseholdMemberEntity member = new DinnerHouseholdMemberEntity();
        member.setId(11L);
        member.setUserId(userId);
        member.setHouseholdId(householdId);
        return member;
    }

    private DinnerHouseholdEntity household(Long id, String status) {
        DinnerHouseholdEntity household = new DinnerHouseholdEntity();
        household.setId(id);
        household.setStatus(status);
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
}
