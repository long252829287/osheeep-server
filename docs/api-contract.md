# API Contract

Base URL: `http://localhost:8080`

## Response Envelope

All `/api/**` endpoints return this shape:

```json
{
  "success": true,
  "data": {},
  "message": "OK",
  "errorCode": null,
  "requestId": "..."
}
```

For failures, `success` is `false`, `data` is omitted, and `errorCode` is one of the documented common or business-specific codes. Successful responses with no data also omit `data`.

## Authentication

Public endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/wechat`
- `GET /actuator/health`
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

All other `/api/**` endpoints require:

```http
Authorization: Bearer <accessToken>
```

## Auth And User

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | `email`, `username`, `password`, optional `displayName` | Login response |
| POST | `/api/auth/login` | `email`, `password` | Login response |
| POST | `/api/auth/wechat` | `code` from `wx.login` | Login response |
| POST | `/api/auth/logout` | None | `null` |
| GET | `/api/users/me` | None | User profile |

Login response data:

```json
{
  "accessToken": "...",
  "user": {
    "id": 1,
    "email": "user@example.com",
    "username": "osheeep",
    "nickname": "Osheeep",
    "avatarUrl": null
  }
}
```

Registration validation: `username` is 3-64 characters and `password` is 8-128 characters.

The WeChat endpoint exchanges the temporary code on the server. It never returns `openid`, `session_key`, or the AppSecret.

## Dinner Household

All household endpoints require a bearer token. Each user can belong to one household and each household can contain at most two users.

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
| GET | `/api/dinner/household` | None | Household summary, or omitted `data` when unbound |
| POST | `/api/dinner/households` | Optional `name` | Household, invite code, expiry |
| POST | `/api/dinner/households/invite-code/refresh` | None | Household, replacement invite code, expiry |
| POST | `/api/dinner/households/join` | `inviteCode` | Household summary |

Household summary fields are `id`, `name`, `timezone`, and `memberCount`. Create and refresh responses add `inviteCode` and ISO-8601 `inviteExpiresAt`. Invite codes expire after 24 hours; only a keyed digest is persisted.

Household business errors are `DINNER_INVITE_INVALID`, `DINNER_INVITE_EXPIRED`, `DINNER_HOUSEHOLD_FULL`, and `DINNER_ALREADY_IN_HOUSEHOLD`.

## Dinner Menu And Records

All menu, recipe, and record endpoints require a bearer token and an active household membership. The server derives the household and current user from the token.

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
| GET | `/api/dinner/recipes` | None | Active system recipe array |
| GET | `/api/dinner/menus/today` | None | Today's merged menu |
| PUT | `/api/dinner/menus/today/selections` | `recipeIds`, `version` | Updated merged menu |
| POST | `/api/dinner/menus/today/confirm` | `version`, UUID v4 `idempotencyKey` | Confirmed menu |
| POST | `/api/dinner/menus/today/complete` | `version`, UUID v4 `idempotencyKey` | `recordId` and completed menu |
| GET | `/api/dinner/records` | None | Completed record summaries |
| GET | `/api/dinner/records/{id}` | None | Record detail and dish snapshots |

The menu business day changes at 04:00 in the household timezone. `TodayMenuResponse` contains `id`, `menuDate`, `status`, `version`, `mySelectionCount`, `partnerSelectionCount`, `consensusCount`, `selectedRecipeIds`, merged `dishes`, confirmation/completion metadata, and optional `recordId`. Dish `source` is relative to the current user: `ME`, `PARTNER`, or `BOTH`.

Selection updates replace only the current member's complete selection set. Every write compares `version`; stale writes return HTTP 409 with `DINNER_MENU_VERSION_CONFLICT`. Confirming an empty menu returns `DINNER_MENU_EMPTY`. Updating a completed menu returns `DINNER_MENU_COMPLETED`, and completing a menu that is not confirmed returns `DINNER_MENU_NOT_CONFIRMED`.

Completion is idempotent by both the request key and the unique menu record. Repeated completion returns the existing record and never creates duplicate snapshots.

## Thought Clusters

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
| GET | `/api/thoughts/clusters` | None | Cluster array |
| POST | `/api/thoughts/clusters/rebuild` | None | Completed job response |

A cluster contains `id`, `userId`, `title`, `thesis`, `fragmentIds`, `maturityScore`, `missingQuestions`, `status`, and `updatedAt`.

The rebuild response contains `userId`, `status`, `note`, and `jobId`. The first version completes synchronously, so a successful response has `status: "completed"` and a persisted job ID.

## Thought Outlines

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
| POST | `/api/thoughts/outlines/generate` | None | Generated outline |
| GET | `/api/thoughts/outlines/{id}` | None | Stored outline |

Generation selects the current user's latest mature cluster, stores the result, and returns it synchronously. The response contains `id`, `userId`, `clusterId`, `titleCandidates`, `coreArgument`, `outline`, `supportingFragmentIds`, `missingMaterials`, and `createdAt`.

`outline` is an array of sections with `title` and `content`. The rule-based first version always creates `问题背景`, `核心论证`, and `行动与补链`.

If no mature cluster exists, generation returns `BUSINESS_ERROR`.

## Fragment API Status

The thought-fragment module is intentionally pending redesign. The frontend currently contains calls to `/api/thoughts/fragments`, but those CRUD endpoints are not available until that redesign is resumed. Do not treat the frontend calls as an active backend contract yet.

## Local Exploration

Use Swagger UI at `http://localhost:8080/swagger-ui.html` for request and response schemas. After logging in, use the **Authorize** action and paste the access token to call protected endpoints.
