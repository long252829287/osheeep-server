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
    private String name;
    @TableField("image_path") private String imagePath;
    private String category;
    private String flavor;
    @TableField("estimated_minutes") private Integer estimatedMinutes;
    @TableField("selected_by_user_ids") private String selectedByUserIds;
    @TableField("sort_order") private Integer sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
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
    public String getSelectedByUserIds() { return selectedByUserIds; }
    public void setSelectedByUserIds(String selectedByUserIds) { this.selectedByUserIds = selectedByUserIds; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
