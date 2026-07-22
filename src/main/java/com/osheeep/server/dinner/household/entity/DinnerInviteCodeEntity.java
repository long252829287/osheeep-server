package com.osheeep.server.dinner.household.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_invite_codes")
public class DinnerInviteCodeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("household_id")
    private Long householdId;
    @TableField("code_hash")
    private String codeHash;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("revoked_at")
    private LocalDateTime revokedAt;
    @TableField("consumed_at")
    private LocalDateTime consumedAt;
    @TableField("consumed_by")
    private Long consumedBy;
    @TableField("revocation_reason")
    private String revocationReason;
    @TableField(value = "open_household_id", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Long openHouseholdId;
    @TableField("created_by")
    private Long createdBy;
    @TableField("created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public Long getConsumedBy() { return consumedBy; }
    public void setConsumedBy(Long consumedBy) { this.consumedBy = consumedBy; }
    public String getRevocationReason() { return revocationReason; }
    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }
    public Long getOpenHouseholdId() { return openHouseholdId; }
    public void setOpenHouseholdId(Long openHouseholdId) { this.openHouseholdId = openHouseholdId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
