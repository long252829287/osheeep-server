package com.osheeep.server.dinner.household.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_household_members")
public class DinnerHouseholdMemberEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("household_id")
    private Long householdId;
    @TableField("user_id")
    private Long userId;
    @TableField("role")
    private String role;
    @TableField("status")
    private String status;
    @TableField("seat_no")
    private Integer seatNo;
    @TableField("history_visible_from")
    private LocalDateTime historyVisibleFrom;
    @TableField("version")
    private Long version;
    @TableField("joined_at")
    private LocalDateTime joinedAt;
    @TableField("ended_at")
    private LocalDateTime endedAt;
    @TableField("ended_by")
    private Long endedBy;
    @TableField("end_reason")
    private String endReason;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField(value = "active_user_id", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Long activeUserId;
    @TableField(value = "active_owner_household_id", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Long activeOwnerHouseholdId;
    @TableField(value = "active_seat_no", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Integer activeSeatNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHouseholdId() { return householdId; }
    public void setHouseholdId(Long householdId) { this.householdId = householdId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSeatNo() { return seatNo; }
    public void setSeatNo(Integer seatNo) { this.seatNo = seatNo; }
    public LocalDateTime getHistoryVisibleFrom() { return historyVisibleFrom; }
    public void setHistoryVisibleFrom(LocalDateTime historyVisibleFrom) {
        this.historyVisibleFrom = historyVisibleFrom;
    }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(LocalDateTime joinedAt) { this.joinedAt = joinedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Long getEndedBy() { return endedBy; }
    public void setEndedBy(Long endedBy) { this.endedBy = endedBy; }
    public String getEndReason() { return endReason; }
    public void setEndReason(String endReason) { this.endReason = endReason; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getActiveUserId() { return activeUserId; }
    public void setActiveUserId(Long activeUserId) { this.activeUserId = activeUserId; }
    public Long getActiveOwnerHouseholdId() { return activeOwnerHouseholdId; }
    public void setActiveOwnerHouseholdId(Long activeOwnerHouseholdId) {
        this.activeOwnerHouseholdId = activeOwnerHouseholdId;
    }
    public Integer getActiveSeatNo() { return activeSeatNo; }
    public void setActiveSeatNo(Integer activeSeatNo) { this.activeSeatNo = activeSeatNo; }
}
