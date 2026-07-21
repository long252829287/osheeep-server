package com.osheeep.server.dinner.menu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_menu_selections")
public class DinnerMenuSelectionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("menu_id") private Long menuId;
    @TableField("user_id") private Long userId;
    @TableField("recipe_id") private Long recipeId;
    @TableField("recipe_version") private Long recipeVersion;
    @TableField("method_id") private Long methodId;
    @TableField("selected_at") private LocalDateTime selectedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMenuId() { return menuId; }
    public void setMenuId(Long menuId) { this.menuId = menuId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRecipeId() { return recipeId; }
    public void setRecipeId(Long recipeId) { this.recipeId = recipeId; }
    public Long getRecipeVersion() { return recipeVersion; }
    public void setRecipeVersion(Long recipeVersion) { this.recipeVersion = recipeVersion; }
    public Long getMethodId() { return methodId; }
    public void setMethodId(Long methodId) { this.methodId = methodId; }
    public LocalDateTime getSelectedAt() { return selectedAt; }
    public void setSelectedAt(LocalDateTime selectedAt) { this.selectedAt = selectedAt; }
}
