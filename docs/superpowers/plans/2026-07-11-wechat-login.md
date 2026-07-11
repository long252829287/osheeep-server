# Osheeep 微信小程序登录 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `POST /api/auth/wechat`，将小程序 `wx.login` code 换取 openid，创建或复用本地用户，并返回现有 JWT 登录响应。

**Architecture:** `WechatCode2SessionClient` 隔离微信官方 API，`WechatAuthService` 负责身份映射事务，现有 `AuthService` 统一签发 JWT。微信身份保存在独立映射表，`session_key` 不落库、不记录、不返回。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring MVC `RestClient`、MyBatis-Plus、MySQL/Flyway、JUnit 5、Mockito、MockMvc。

## Global Constraints

- 官方接口固定为 `GET https://api.weixin.qq.com/sns/jscode2session`。
- 请求参数为 `appid`、`secret`、`js_code`、`grant_type=authorization_code`。
- AppID 从 `OSHEEEP_WECHAT_APP_ID` 注入，AppSecret 从 `OSHEEEP_WECHAT_APP_SECRET` 注入。
- 禁止记录或返回 code、AppSecret、session_key、openid 和完整 JWT。
- 微信用户仍写入 `users`，并通过 `wechat_user_identities.user_id` 关联；不创建第二套业务用户体系。
- 现有邮箱注册和登录行为必须保持不变。

---

### Task 1: 微信身份持久化

**Files:**
- Create: `src/main/resources/db/migration/V2__add_wechat_identity.sql`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatUserIdentityEntity.java`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatUserIdentityMapper.java`
- Modify: `src/main/java/com/osheeep/server/user/UserService.java`
- Modify: `src/test/java/com/osheeep/server/TestUserMapperConfig.java`
- Test: `src/test/java/com/osheeep/server/auth/wechat/WechatAuthServiceTest.java`

**Interfaces:**
- Produces: `WechatUserIdentityMapper`、`UserService.createWechatUser(String username)`。
- Database: openid 唯一、每个 user_id 最多一个微信身份；微信用户 email/password_hash 可空。

- [x] **Step 1: 写失败的身份服务测试**

```java
@Test
void firstLoginCreatesUserAndIdentity() {
    when(sessionClient.exchange("code-1")).thenReturn(new WechatSession("openid-1"));
    when(identityMapper.selectOne(any())).thenReturn(null);
    when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
        invocation.<UserEntity>getArgument(0).setId(101L);
        return 1;
    });

    LoginResponse response = service.login("code-1");

    assertThat(response.user().id()).isEqualTo(101L);
    verify(identityMapper).insert(argThat(identity ->
            identity.getUserId().equals(101L) && identity.getOpenid().equals("openid-1")));
}
```

第二个测试令 `identityMapper.selectOne` 返回 userId 42，断言复用 `userMapper.selectById(42L)` 且不插入新用户。

- [x] **Step 2: 运行测试确认缺少微信身份类型**

Run: `mvn -Dtest=WechatAuthServiceTest test`

Expected: FAIL，目标类型或 `WechatAuthService` 尚不存在。

- [x] **Step 3: 创建迁移和身份映射**

```sql
ALTER TABLE users
    MODIFY email VARCHAR(255) NULL,
    MODIFY password_hash VARCHAR(255) NULL;

CREATE TABLE wechat_user_identities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    openid VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wechat_user_identities_user_id (user_id),
    UNIQUE KEY uk_wechat_user_identities_openid (openid),
    CONSTRAINT fk_wechat_user_identities_user_id FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`WechatUserIdentityEntity` 映射 `id`、`userId`、`openid`、`createdAt`、`updatedAt`；Mapper 继承 `BaseMapper<WechatUserIdentityEntity>`。`createWechatUser` 写入 `username`、`status=ACTIVE`，email/passwordHash 保持 null。

- [x] **Step 4: 运行身份服务测试**

Run: `mvn -Dtest=WechatAuthServiceTest test`

Expected: PASS，首次创建和再次复用两条测试通过。

- [x] **Step 5: 提交身份持久化**

```bash
git add src/main/resources/db/migration/V2__add_wechat_identity.sql src/main/java/com/osheeep/server/auth/wechat src/main/java/com/osheeep/server/user/UserService.java src/test/java/com/osheeep/server/TestUserMapperConfig.java src/test/java/com/osheeep/server/auth/wechat/WechatAuthServiceTest.java docs/superpowers/plans/2026-07-11-wechat-login.md
git commit -m "feat: add wechat user identity mapping"
```

### Task 2: 微信 code2Session 客户端

**Files:**
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatProperties.java`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatSession.java`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatCode2SessionClient.java`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatApiClient.java`
- Create: `src/test/java/com/osheeep/server/auth/wechat/WechatApiClientTest.java`
- Modify: `src/main/resources/application-local.yml`
- Modify: `src/main/resources/application-test.yml`

**Interfaces:**
- Consumes: `WechatProperties(appId, appSecret)` 与 Spring `RestClient.Builder`。
- Produces: `WechatCode2SessionClient.exchange(String code): WechatSession`。

- [ ] **Step 1: 写失败的 API 客户端测试**

```java
@Test
void exchangesCodeWithoutExposingSessionKey() {
    server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                    + "?appid=app-id&secret=app-secret&js_code=code-1&grant_type=authorization_code"))
            .andRespond(withSuccess("{\"openid\":\"openid-1\",\"session_key\":\"secret-session\"}",
                    MediaType.APPLICATION_JSON));

    assertThat(client.exchange("code-1")).isEqualTo(new WechatSession("openid-1"));
    server.verify();
}
```

错误测试返回 `{"errcode":40029,"errmsg":"invalid code"}`，断言抛出 `BusinessException` 且错误码为 `WECHAT_LOGIN_FAILED`。

- [ ] **Step 2: 运行测试确认客户端缺失**

Run: `mvn -Dtest=WechatApiClientTest test`

Expected: FAIL，`WechatApiClient` 尚不存在。

- [ ] **Step 3: 实现 RestClient 调用和配置**

```java
@ConfigurationProperties(prefix = "osheeep.wechat")
public record WechatProperties(String appId, String appSecret) {}

public interface WechatCode2SessionClient {
    WechatSession exchange(String code);
}

public record WechatSession(String openid) {}
```

`WechatApiClient.exchange` 使用 URI builder 添加四个查询参数，响应 record 只在内存中接收 `openid`、`session_key`、`errcode`、`errmsg`。errcode 非零、响应为空或 openid 为空时抛出 `WECHAT_LOGIN_FAILED`，异常消息不得拼接微信原始响应。

`application-local.yml` 增加：

```yaml
osheeep:
  wechat:
    app-id: ${OSHEEEP_WECHAT_APP_ID}
    app-secret: ${OSHEEEP_WECHAT_APP_SECRET}
```

测试配置只使用 `test-app-id`、`test-app-secret`。

- [ ] **Step 4: 验证客户端测试**

Run: `mvn -Dtest=WechatApiClientTest test`

Expected: PASS，成功解包与微信错误映射均通过。

- [ ] **Step 5: 提交微信客户端**

```bash
git add src/main/java/com/osheeep/server/auth/wechat src/test/java/com/osheeep/server/auth/wechat/WechatApiClientTest.java src/main/resources/application-local.yml src/main/resources/application-test.yml src/main/java/com/osheeep/server/common/error/ErrorCode.java docs/superpowers/plans/2026-07-11-wechat-login.md
git commit -m "feat: add wechat code session client"
```

### Task 3: 公开登录接口与端到端验证

**Files:**
- Create: `src/main/java/com/osheeep/server/auth/dto/WechatLoginRequest.java`
- Create: `src/main/java/com/osheeep/server/auth/wechat/WechatAuthService.java`
- Modify: `src/main/java/com/osheeep/server/auth/AuthController.java`
- Modify: `src/main/java/com/osheeep/server/auth/AuthService.java`
- Modify: `src/main/java/com/osheeep/server/common/security/SecurityConfig.java`
- Modify: `src/test/java/com/osheeep/server/auth/AuthControllerTest.java`
- Modify: `docs/api-contract.md`

**Interfaces:**
- Consumes: `{ "code": "wx-login-code" }`。
- Produces: 现有 `ApiResponse<LoginResponse>`，字段仍为 `accessToken` 和 `user`。

- [ ] **Step 1: 写失败的控制器测试**

```java
@Test
void wechatLoginReturnsTokenAndUserWithoutAuthentication() throws Exception {
    when(wechatSessionClient.exchange("code-1")).thenReturn(new WechatSession("openid-1"));
    when(wechatIdentityMapper.selectOne(any())).thenReturn(null);
    when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
        invocation.<UserEntity>getArgument(0).setId(101L);
        return 1;
    });

    mockMvc.perform(post("/api/auth/wechat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"code-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").isString())
            .andExpect(jsonPath("$.data.user.id").value(101));
}
```

空 code 测试断言 HTTP 400 和 `VALIDATION_ERROR`；微信错误测试断言 HTTP 401 和 `WECHAT_LOGIN_FAILED`。

- [ ] **Step 2: 运行测试确认接口未注册**

Run: `mvn -Dtest=AuthControllerTest test`

Expected: FAIL，`POST /api/auth/wechat` 未放行或未映射。

- [ ] **Step 3: 实现控制器与 JWT 复用**

```java
public record WechatLoginRequest(@NotBlank String code) {}
```

`AuthController.wechatLogin` 调用 `WechatAuthService.login(request.code())`；`AuthService.issueToken(UserEntity)` 改为包内可复用方法；SecurityConfig 将 `/api/auth/wechat` 与 register/login 一并 permitAll。

- [ ] **Step 4: 运行完整验证**

Run: `mvn test`

Expected: BUILD SUCCESS，全部单元和控制器测试通过。

- [ ] **Step 5: 提交登录接口**

```bash
git add src/main/java src/test/java src/main/resources docs/api-contract.md docs/superpowers/plans/2026-07-11-wechat-login.md
git commit -m "feat: add wechat mini program login"
```

## 阶段验收

- 不带 JWT 调用 `POST /api/auth/wechat` 可成功。
- 首次 openid 创建一个 users 记录和一个身份映射；再次登录复用同一 user。
- 响应不含 openid、session_key、AppSecret。
- 邮箱注册、邮箱登录和现有受保护接口测试继续通过。
- 未配置真实环境变量时服务端不应以 local profile 成功启动。
