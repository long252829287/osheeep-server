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

## Dinner Ingredients, Inventory, And Recipe Discovery

Flyway migration `V5__add_recipe_ingredients_and_household_inventory.sql` adds the standard ingredient catalog, recipe requirements, and per-household inventory. Ingredient and recipe requirement quantities use `DECIMAL(12,3)` and may be `null`; units are required. Inventory rows have their own optimistic `version` and record the last updating user and database-managed timestamp.

All routes in this section require a bearer token. The client never supplies a household ID: the service looks up the authenticated user's household membership and uses its `householdId` for catalog visibility, inventory reads/writes, and recipe matching. A user without a household membership receives HTTP 403 with `FORBIDDEN` and message `Access is denied`.

| Method | Path | Request | Response data |
| --- | --- | --- | --- |
| GET | `/api/dinner/ingredients` | None | Accessible active ingredient array |
| GET | `/api/dinner/inventory` | None | Current household inventory array |
| PUT | `/api/dinner/inventory/{ingredientId}` | JSON body: nullable `quantity`, `unit`, `version` | Created or updated inventory item |
| DELETE | `/api/dinner/inventory/{ingredientId}` | Required query parameter `version` | No data |
| GET | `/api/dinner/recipes` | Optional query parameters described below | Matched active system recipe array |

An ingredient response contains `id`, `name`, `category`, and `defaultUnit`. The catalog contains active system ingredients plus active household-scoped ingredients belonging to the current household, ordered by ID.

An inventory response contains `ingredientId`, `name`, `category`, nullable `quantity`, `unit`, `version`, `updatedBy`, and ISO-8601 `updatedAt`, ordered by inventory row ID. A `null` quantity means the household has the ingredient but has not confirmed its amount; it is not the same as deleting the row. Under the API's non-null JSON serialization, `quantity` is omitted when its value is `null`.

The PUT body follows these rules:

- `quantity` may be `null`; otherwise it must be nonnegative with at most 9 integer digits and 3 fractional digits.
- `unit` is required, nonblank, at most 16 characters, and is trimmed before persistence.
- `version` is required and nonnegative. Creating a missing inventory row requires exactly `version: 0` and returns version `0`. Updating an existing row requires its exact current version and increments it by one.
- Only an active system ingredient or an active ingredient owned by the current household can be stocked.

DELETE also requires the exact current version. A stale PUT or DELETE returns HTTP 409, `DINNER_INVENTORY_VERSION_CONFLICT`, and message `Dinner inventory was updated by another member`. Deleting a row that does not exist returns HTTP 404, `DINNER_INVENTORY_ITEM_NOT_FOUND`, and message `Dinner inventory item was not found`. An inactive, unknown, or foreign-household ingredient on PUT returns HTTP 400, `DINNER_INGREDIENT_INVALID`, and message `Dinner ingredient is invalid`. Request validation failures return HTTP 400 with `VALIDATION_ERROR`.

`GET /api/dinner/recipes` accepts:

- `includeIngredientIds`: a repeated or comma-separated set of ingredient IDs temporarily treated as present when a matching recipe requirement is absent from inventory.
- `excludeIngredientIds`: a repeated or comma-separated set temporarily removed from effective inventory.
- `onlyCookable`: boolean, default `false`. When `true`, recipes with match status `MISSING` are removed, while both `AVAILABLE` and `UNKNOWN_QUANTITY` remain.

Temporary include/exclude values affect only this response and are never persisted. Existing household inventory takes precedence over include: include does not replace a known quantity or fix an insufficient quantity. Exclude is applied last, so it wins over both inventory and include when an ID appears in both sets.

Each recipe retains the original fields `id`, `name`, `imagePath`, `category`, `flavor`, and `estimatedMinutes`, and adds:

- `ingredients`: ordered items with `ingredientId`, `name`, nullable `quantity`, `unit`, `required`, and `sortOrder`; `quantity` is omitted from JSON when `null`.
- `match`: `status`, `matchedRequired`, `totalRequired`, `matchPercent`, `missingIngredients`, and `unknownQuantityIngredients`.

Only required ingredients contribute to matching. Missing stock, insufficient quantity, or a unit mismatch is `MISSING`. Present stock with a `null` quantity against a quantified requirement contributes to `matchedRequired` but produces `UNKNOWN_QUANTITY`. Complete required stock is `AVAILABLE`. Recipes are ordered by status (`AVAILABLE`, `UNKNOWN_QUANTITY`, `MISSING`), then descending `matchPercent`, ascending `estimatedMinutes` with unknown duration last, and finally ascending recipe ID.

This is backward compatible for existing recipe consumers: `GET /api/dinner/recipes` remains the same authenticated route, all query parameters default to the old no-filter call, and no original recipe response field was removed. Existing tonight-menu selection and record endpoints and payloads are unchanged.

## Dinner Menu And Records

All menu, recipe, and record endpoints require a bearer token and an active household membership. The server derives the household and current user from the token.

| Method | Path | Request body | Response data |
| --- | --- | --- | --- |
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
