package com.osheeep.server.dinner.household;

import com.osheeep.server.common.error.BusinessException;
import com.osheeep.server.common.error.ErrorCode;
import com.osheeep.server.dinner.recipe.entity.DinnerRecipeEntity;
import com.osheeep.server.dinner.recipe.mapper.DinnerRecipeMapper;
import java.util.List;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DinnerHouseholdDraftLifecycleService {

    private final DinnerRecipeMapper recipeMapper;

    public DinnerHouseholdDraftLifecycleService(DinnerRecipeMapper recipeMapper) {
        this.recipeMapper = recipeMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rebindUnboundDrafts(Long actorUserId, Long householdId) {
        try {
            List<DinnerRecipeEntity> drafts =
                    recipeMapper.selectUnboundDraftsByCreatorForUpdate(actorUserId);
            Long previousId = null;
            for (DinnerRecipeEntity draft : drafts) {
                if (!isRebindable(draft, actorUserId)
                        || (previousId != null && draft.getId() <= previousId)) {
                    throw recipeVersionConflict();
                }
                draft.setHouseholdId(householdId);
                draft.setLastModifiedBy(actorUserId);
                draft.setVersion(draft.getVersion() + 1L);
                if (recipeMapper.updateById(draft) != 1) {
                    throw recipeVersionConflict();
                }
                previousId = draft.getId();
            }
        } catch (PessimisticLockingFailureException exception) {
            throw recipeVersionConflict();
        }
    }

    private boolean isRebindable(DinnerRecipeEntity draft, Long actorUserId) {
        return draft != null
                && draft.getId() != null
                && "HOUSEHOLD".equals(draft.getScope())
                && "DRAFT".equals(draft.getStatus())
                && actorUserId.equals(draft.getCreatorId())
                && draft.getHouseholdId() == null
                && draft.getVersion() != null
                && draft.getVersion() >= 1;
    }

    private BusinessException recipeVersionConflict() {
        return new BusinessException(ErrorCode.DINNER_RECIPE_VERSION_CONFLICT);
    }
}
