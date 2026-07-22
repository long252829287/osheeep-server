package com.osheeep.server.dinner.household.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_household_operations")
public class DinnerHouseholdOperationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("household_id")
    private Long householdId;
    @TableField("actor_id")
    private Long actorId;
    @TableField("actor_membership_id")
    private Long actorMembershipId;
    @TableField("target_member_id")
    private Long targetMemberId;
    @TableField("operation_type")
    private String operationType;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("request_fingerprint")
    private String requestFingerprint;
    @TableField("result_schema_version")
    private Integer resultSchemaVersion;
    @TableField("result_household_version")
    private Long resultHouseholdVersion;
    @TableField("result_payload")
    private String resultPayload;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public Long getActorMembershipId() { return actorMembershipId; }
    public void setActorMembershipId(Long actorMembershipId) {
        this.actorMembershipId = actorMembershipId;
    }
    public Long getTargetMemberId() { return targetMemberId; }
    public void setTargetMemberId(Long targetMemberId) { this.targetMemberId = targetMemberId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }
    public Integer getResultSchemaVersion() { return resultSchemaVersion; }
    public void setResultSchemaVersion(Integer resultSchemaVersion) {
        this.resultSchemaVersion = resultSchemaVersion;
    }
    public Long getResultHouseholdVersion() { return resultHouseholdVersion; }
    public void setResultHouseholdVersion(Long resultHouseholdVersion) {
        this.resultHouseholdVersion = resultHouseholdVersion;
    }
    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
