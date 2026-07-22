package com.osheeep.server.dinner.household.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_households")
public class DinnerHouseholdEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String timezone;
    private String status;
    @TableField("version")
    private Long version;
    @TableField("invite_revision")
    private Long inviteRevision;
    @TableField("admin_changed_at")
    private LocalDateTime adminChangedAt;
    @TableField("created_by")
    private Long createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Long getInviteRevision() { return inviteRevision; }
    public void setInviteRevision(Long inviteRevision) { this.inviteRevision = inviteRevision; }
    public LocalDateTime getAdminChangedAt() { return adminChangedAt; }
    public void setAdminChangedAt(LocalDateTime adminChangedAt) { this.adminChangedAt = adminChangedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
