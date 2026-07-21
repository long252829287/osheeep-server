package com.osheeep.server.dinner.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("dinner_record_dish_snapshots")
public class DinnerRecordDishSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("record_id") private Long recordId;
    @TableField("recipe_id") private Long recipeId;
    @TableField("recipe_scope") private String recipeScope;
    @TableField("recipe_version") private Long recipeVersion;
    private String name;
    @TableField("image_path") private String imagePath;
    private String category;
    private String flavor;
    @TableField("estimated_minutes") private Integer estimatedMinutes;
    private Integer servings;
    @TableField("method_id") private Long methodId;
    @TableField("method_name") private String methodName;
    @TableField("cooking_style") private String cookingStyle;
    @TableField("method_steps") private String methodStepsJson;
    @TableField("ingredients") private String ingredientsJson;
    @TableField("selected_by_user_ids") private String selectedByUserIds;
    @TableField("sort_order") private Integer sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public String getRecipeScope() { return recipeScope; }
    public void setRecipeScope(String recipeScope) { this.recipeScope = recipeScope; }
    public Long getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(Long recipeVersion) { this.recipeVersion = recipeVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFlavor() { return flavor; }
    public void setFlavor(String flavor) { this.flavor = flavor; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
    public Integer getServings() { return servings; }
    public void setServings(Integer servings) { this.servings = servings; }
    public Long getMethodId() { return methodId; }
    public void setMethodId(Long methodId) { this.methodId = methodId; }
    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public String getCookingStyle() { return cookingStyle; }
    public void setCookingStyle(String cookingStyle) { this.cookingStyle = cookingStyle; }
    public String getMethodStepsJson() { return methodStepsJson; }
    public void setMethodStepsJson(String methodStepsJson) { this.methodStepsJson = methodStepsJson; }
    public String getIngredientsJson() { return ingredientsJson; }
    public void setIngredientsJson(String ingredientsJson) { this.ingredientsJson = ingredientsJson; }
    public String getSelectedByUserIds() { return selectedByUserIds; }
    public void setSelectedByUserIds(String selectedByUserIds) { this.selectedByUserIds = selectedByUserIds; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
