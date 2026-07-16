package com.osheeep.server.dinner.image.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("dinner_image_assets")
public class DinnerImageAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String provider;
    @TableField("display_name") private String displayName;
    @TableField("search_keywords") private String searchKeywords;
    @TableField("source_page_url") private String sourcePageUrl;
    @TableField("original_file_url") private String originalFileUrl;
    private String author;
    @TableField("license_name") private String licenseName;
    @TableField("license_url") private String licenseUrl;
    @TableField("acquired_on") private LocalDate acquiredOn;
    private String sha256;
    @TableField("original_width") private Integer originalWidth;
    @TableField("original_height") private Integer originalHeight;
    @TableField("original_object_key") private String originalObjectKey;
    @TableField("list_object_key") private String listObjectKey;
    @TableField("detail_object_key") private String detailObjectKey;
    private String status;
    @TableField("reviewed_at") private LocalDateTime reviewedAt;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSearchKeywords() { return searchKeywords; }
    public void setSearchKeywords(String searchKeywords) { this.searchKeywords = searchKeywords; }
    public String getSourcePageUrl() { return sourcePageUrl; }
    public void setSourcePageUrl(String sourcePageUrl) { this.sourcePageUrl = sourcePageUrl; }
    public String getOriginalFileUrl() { return originalFileUrl; }
    public void setOriginalFileUrl(String originalFileUrl) { this.originalFileUrl = originalFileUrl; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getLicenseName() { return licenseName; }
    public void setLicenseName(String licenseName) { this.licenseName = licenseName; }
    public String getLicenseUrl() { return licenseUrl; }
    public void setLicenseUrl(String licenseUrl) { this.licenseUrl = licenseUrl; }
    public LocalDate getAcquiredOn() { return acquiredOn; }
    public void setAcquiredOn(LocalDate acquiredOn) { this.acquiredOn = acquiredOn; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public Integer getOriginalWidth() { return originalWidth; }
    public void setOriginalWidth(Integer originalWidth) { this.originalWidth = originalWidth; }
    public Integer getOriginalHeight() { return originalHeight; }
    public void setOriginalHeight(Integer originalHeight) { this.originalHeight = originalHeight; }
    public String getOriginalObjectKey() { return originalObjectKey; }
    public void setOriginalObjectKey(String originalObjectKey) { this.originalObjectKey = originalObjectKey; }
    public String getListObjectKey() { return listObjectKey; }
    public void setListObjectKey(String listObjectKey) { this.listObjectKey = listObjectKey; }
    public String getDetailObjectKey() { return detailObjectKey; }
    public void setDetailObjectKey(String detailObjectKey) { this.detailObjectKey = detailObjectKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
