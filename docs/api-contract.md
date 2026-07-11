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

For failures, `success` is `false`, `data` is `null`, and `errorCode` is one of `VALIDATION_ERROR`, `UNAUTHORIZED`, `WECHAT_LOGIN_FAILED`, `FORBIDDEN`, `BUSINESS_ERROR`, or `INTERNAL_ERROR`.

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
