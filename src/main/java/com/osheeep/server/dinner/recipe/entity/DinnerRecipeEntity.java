package com.osheeep.server.dinner.recipe.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_recipes")
public class DinnerRecipeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String scope;
    @TableField("household_id") private Long householdId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String name;
    @TableField("image_path") private String imagePath;
    @TableField("image_asset_id") private Long imageAssetId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String category;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String flavor;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Integer servings;
    @TableField(value = "estimated_minutes", updateStrategy = FieldStrategy.ALWAYS)
    private Integer estimatedMinutes;
    @TableField("creator_id") private Long creatorId;
    @TableField("last_modified_by") private Long lastModifiedBy;
    @TableField("source_recipe_id") private Long sourceRecipeId;
    @TableField("revision_of_recipe_id") private Long revisionOfRecipeId;
    @TableField("base_published_version") private Long basePublishedVersion;
    private String status;
    private Long version;
    @TableField("published_at") private LocalDateTime publishedAt;
    @TableField("archived_at") private LocalDateTime archivedAt;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public Long getImageAssetId() { return imageAssetId; }
    public void setImageAssetId(Long imageAssetId) { this.imageAssetId = imageAssetId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFlavor() { return flavor; }
    public void setFlavor(String flavor) { this.flavor = flavor; }
    public Integer getServings() { return servings; }
    public void setServings(Integer servings) { this.servings = servings; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer estimatedMinutes) { this.estimatedMinutes = estimatedMinutes; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Long getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(Long lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }
    public Long getSourceRecipeId() { return sourceRecipeId; }
    public void setSourceRecipeId(Long sourceRecipeId) { this.sourceRecipeId = sourceRecipeId; }
    public Long getRevisionOfRecipeId() { return revisionOfRecipeId; }
    public void setRevisionOfRecipeId(Long revisionOfRecipeId) { this.revisionOfRecipeId = revisionOfRecipeId; }
    public Long getBasePublishedVersion() { return basePublishedVersion; }
    public void setBasePublishedVersion(Long basePublishedVersion) { this.basePublishedVersion = basePublishedVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
