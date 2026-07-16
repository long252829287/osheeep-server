package com.osheeep.server.dinner.recipe.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_recipe_methods")
public class DinnerRecipeMethodEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("recipe_id") private Long recipeId;
    private String name;
    @TableField("cooking_style") private String cookingStyle;
    @TableField("estimated_minutes") private Integer estimatedMinutes;
    @TableField("is_default") private Boolean isDefault;
    private String status;
    @TableField("sort_order") private Integer sortOrder;
    @TableField(value = "default_recipe_id", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Long defaultRecipeId;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCookingStyle() { return cookingStyle; }
    public void setCookingStyle(String cookingStyle) { this.cookingStyle = cookingStyle; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Long getDefaultRecipeId() { return defaultRecipeId; }
    public void setDefaultRecipeId(Long defaultRecipeId) { this.defaultRecipeId = defaultRecipeId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
