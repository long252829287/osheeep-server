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

For failures, `success` is `false` and `errorCode` is one of the documented common or business-specific codes. `data` is normally omitted; `DINNER_RECIPE_VALIDATION_FAILED` uses it for structured field issues. Successful responses with no data also omit `data`.

## Authentication

Public endpoints:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/wechat`
- `GET /actuator/health`
- `GET /media/recipes/**` (read-only, self-hosted approved recipe derivatives)
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

All other `/api/**` endpoints require:

```http
Authorization: Bearer <accessToken>
```

## Auth And User

| Method | Path                 | Request body                                            | Response data  |
| ------ | -------------------- | ------------------------------------------------------- | -------------- |
| POST   | `/api/auth/register` | `email`, `username`, `password`, optional `displayName` | Login response |
| POST   | `/api/auth/login`    | `email`, `password`                                     | Login response |
| POST   | `/api/auth/wechat`   | `code` from `wx.login`                                  | Login response |
| POST   | `/api/auth/logout`   | None                                                    | `null`         |
| GET    | `/api/users/me`      | None                                                    | User profile   |
| POST   | `/api/users/me/deletion` | WeChat login `code` for identity re-verification    | `null`         |

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

| Method | Path                                         | Request body    | Response data                                     |
| ------ | -------------------------------------------- | --------------- | ------------------------------------------------- |
| GET    | `/api/dinner/household`                      | None            | Household summary, or omitted `data` when unbound |
| POST   | `/api/dinner/households`                     | Optional `name` | Household, invite code, expiry                    |
| POST   | `/api/dinner/households/invite-code/refresh` | None            | Household, replacement invite code, expiry        |
| POST   | `/api/dinner/households/join`                | `inviteCode`    | Household summary                                 |

Household summary fields are `id`, `name`, `timezone`, and `memberCount`. Create and refresh responses add `inviteCode` and ISO-8601 `inviteExpiresAt`. Invite codes expire after 24 hours; only a keyed digest is persisted.

Household business errors are `DINNER_INVITE_INVALID`, `DINNER_INVITE_EXPIRED`, `DINNER_HOUSEHOLD_FULL`, and `DINNER_ALREADY_IN_HOUSEHOLD`.

## Dinner Ingredients, Inventory, And Recipe Discovery

Flyway migration `V5__add_recipe_ingredients_and_household_inventory.sql` adds the standard ingredient catalog, recipe requirements, and per-household inventory. Ingredient and recipe requirement quantities use `DECIMAL(12,3)` and may be `null`; units are required. Inventory rows have their own optimistic `version` and record the last updating user and database-managed timestamp. Persisted inventory versions start at `1`; request version `0` is reserved exclusively for create-only intent. Inventory `DATETIME(3)` values are database-local `Asia/Shanghai` timestamps and are converted from that explicit zone to ISO-8601 UTC instants in API responses.

All routes in this section require a bearer token. The client never supplies a household ID: the service looks up an existing `dinner_household_members` row for the authenticated user and uses its `householdId` for catalog visibility, inventory reads/writes, and recipe matching. The membership schema has no status column, and these ingredient, inventory, and recipe-discovery services do not load or check the referenced household's status. A user without a membership row receives HTTP 403 with `FORBIDDEN` and message `Access is denied`.

| Method | Path                                   | Request                                           | Response data                      |
| ------ | -------------------------------------- | ------------------------------------------------- | ---------------------------------- |
| GET    | `/api/dinner/ingredients`              | None                                              | Accessible active ingredient array |
| GET    | `/api/dinner/inventory`                | None                                              | Current household inventory array  |
| PUT    | `/api/dinner/inventory/{ingredientId}` | JSON body: nullable `quantity`, `unit`, `version` | Created or updated inventory item  |
| DELETE | `/api/dinner/inventory/{ingredientId}` | Required query parameter `version`                | No data                            |
| GET    | `/api/dinner/recipes`                  | Optional query parameters described below         | Matched active system recipe array |

An ingredient response contains `id`, `name`, `category`, and `defaultUnit`. The catalog contains active system ingredients plus active household-scoped ingredients belonging to the current household, ordered by ID.

An inventory response contains `ingredientId`, `name`, `category`, nullable `quantity`, `unit`, `version`, `updatedBy`, and ISO-8601 `updatedAt`, ordered by inventory row ID. A `null` quantity means the household has the ingredient but has not confirmed its amount; it is not the same as deleting the row. `InventoryItemResponse.quantity` is serialized explicitly as JSON `null`. The `ApiResponse` wrapper's `NON_NULL` rule applies only to the wrapper's own fields and does not omit null fields from nested response records.

The PUT body follows these rules:

- `quantity` may be `null`; otherwise it must be nonnegative with at most 9 integer digits and 3 fractional digits.
- `unit` is required, nonblank, at most 16 characters, and is trimmed before persistence.
- `version` is required and nonnegative. Creating a missing inventory row requires exactly `version: 0` and returns persisted version `1`. If a row already exists, request version `0` always conflicts. Updating an existing row requires its exact persisted version of at least `1` and increments it exactly once.
- Only an active system ingredient or an active ingredient owned by the current household can be stocked.

DELETE also requires the exact current version. A stale PUT or DELETE returns HTTP 409, `DINNER_INVENTORY_VERSION_CONFLICT`, and message `Dinner inventory was updated by another member`. Concurrent PUT create races that surface as the inventory unique-key violation, and PUT lock-wait/deadlock acquisition failures, map to the same HTTP 409 conflict; unrelated database failures are not remapped. Deleting a row that does not exist returns HTTP 404, `DINNER_INVENTORY_ITEM_NOT_FOUND`, and message `Dinner inventory item was not found`. An inactive, unknown, or foreign-household ingredient on PUT returns HTTP 400, `DINNER_INGREDIENT_INVALID`, and message `Dinner ingredient is invalid`. Request validation failures return HTTP 400 with `VALIDATION_ERROR`.

`GET /api/dinner/recipes` accepts:

- `includeIngredientIds`: a repeated or comma-separated set of ingredient IDs temporarily treated as present when a matching recipe requirement is absent from inventory.
- `excludeIngredientIds`: a repeated or comma-separated set temporarily removed from effective inventory.
- `onlyCookable`: boolean, default `false`. When `true`, recipes with match status `MISSING` are removed, while both `AVAILABLE` and `UNKNOWN_QUANTITY` remain.

Temporary include/exclude values affect only this response and are never persisted. Existing household inventory takes precedence over include: include does not replace a known quantity or fix an insufficient quantity. Exclude is applied last, so it wins over both inventory and include when an ID appears in both sets.

Each recipe retains the original fields `id`, `name`, `imagePath`, `category`, `flavor`, and `estimatedMinutes`, and adds:

- `ingredients`: ordered items with `ingredientId`, `name`, nullable `quantity`, `unit`, `required`, and `sortOrder`; `RecipeIngredientResponse.quantity` is serialized explicitly as JSON `null` when absent.
- `match`: `status`, `matchedRequired`, `totalRequired`, `matchPercent`, `missingIngredients`, and `unknownQuantityIngredients`.

Only required ingredients contribute to matching. Missing stock, insufficient known quantity, or a unit mismatch is `MISSING`. When matching-unit stock is present, a `null` recipe requirement quantity, a `null` stock quantity, or both contributes to `matchedRequired` but produces `UNKNOWN_QUANTITY` and lists the ingredient in `unknownQuantityIngredients`. Complete required stock with both quantities known is `AVAILABLE`. Optional ingredients remain ignored. A recipe with only optional ingredients, or otherwise zero required ingredients, is `AVAILABLE` with `matchedRequired: 0`, `totalRequired: 0`, `matchPercent: 100`, `missingIngredients: []`, and `unknownQuantityIngredients: []`. Recipes are ordered by status (`AVAILABLE`, `UNKNOWN_QUANTITY`, `MISSING`), then descending `matchPercent`, ascending `estimatedMinutes` with unknown duration last, and finally ascending recipe ID.

This is backward compatible for existing recipe consumers: `GET /api/dinner/recipes` remains the same authenticated route, all query parameters default to the old no-filter call, and no original recipe response field was removed. Existing tonight-menu selection and record endpoints and payloads are unchanged.

## Household Custom Recipe Vertical Slice

Flyway migration `V6__add_household_custom_recipes.sql` evolves recipes into versioned `DRAFT`, `PUBLISHED`, and `ARCHIVED` aggregates, adds one default method with ordered steps, and adds traceable image assets. Existing system recipes are migrated from `ACTIVE` to `PUBLISHED`; existing discovery and tonight-menu contracts continue to accept those system rows. V6 in source or a local test catalog does not imply that the migration has been applied to production.

On 2026-07-20, the explicit guarded `DinnerCustomRecipeMySqlIT` run passed 1/1 on the dedicated local MySQL 8.0 catalog. Flyway validated all six source migrations; that catalog started at schema version 4, so this run applied V5 and V6 and finished at version 6. This is not evidence of a fresh V1-V6 migration, a production migration, deployment, or release. Ordinary `mvn test` does not select `*IT`; this evidence requires the explicit `-Dtest=DinnerCustomRecipeMySqlIT -Dspring.profiles.active=local` command and both dedicated-catalog safety gates.

All `/api/dinner/**` routes below require a bearer token. `GET /media/recipes/**` is the only public route in this feature and serves read-only derivatives bundled under the application's own origin.

| Method | Path                                      | Request                                  | Response data                         |
| ------ | ----------------------------------------- | ---------------------------------------- | ------------------------------------- |
| POST   | `/api/dinner/recipes/drafts`              | None                                     | New aggregate draft                   |
| GET    | `/api/dinner/recipes/family`              | Required `tab` query parameter           | Family recipe list                    |
| GET    | `/api/dinner/recipes/{id}`                | None                                     | Full visible recipe aggregate         |
| PUT    | `/api/dinner/recipes/{id}/basic-info`     | Versioned basic-info body                | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/ingredients`    | Versioned ingredient replacement         | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/default-method` | Versioned default-method replacement     | Updated aggregate draft               |
| PUT    | `/api/dinner/recipes/{id}/image`          | `version`, nullable `imageAssetId`        | Updated aggregate draft               |
| POST   | `/api/dinner/recipes/{id}/publish`        | `version`                                | Published aggregate                   |
| GET    | `/api/dinner/image-assets`                | Optional `query` query parameter          | Approved image metadata array         |

`tab` is exactly `PUBLISHED`, `DRAFT`, or `ARCHIVED`. Draft lists contain only the current user's drafts. Published and archived lists are scoped to the current active household. A draft is visible only to its creator; before publication, the other member receives HTTP 403 even when both users belong to the same household. A published or archived recipe is visible to either active member of that household. The server derives user and household IDs from the bearer token and never trusts either value from a request body.

Creating a draft returns version `1`. Every successful basic-info, ingredient, default-method, or image write increments the aggregate version exactly once. Publication also increments once; for example, the first complete vertical slice progresses `1` (created), `2` (basic), `3` (ingredients), `4` (method), `5` (image), `6` (published). Every write must supply the exact current version. A stale version returns HTTP 409, `DINNER_RECIPE_VERSION_CONFLICT`, with message `Dinner recipe was updated elsewhere`; the server does not replay the write.

Basic-info body:

```json
{
  "version": 1,
  "name": "番茄炒蛋",
  "category": "家常菜",
  "flavor": "酸甜",
  "servings": 2,
  "estimatedMinutes": 15
}
```

Draft saves may remain incomplete. Nonblank publish values are limited to: name 40 characters; category and flavor 16 each; servings 1-20; estimated time 1-1440 minutes. Blank basic strings are stored as absent.

Ingredient replacement body:

```json
{
  "version": 2,
  "ingredients": [
    {
      "ingredientId": 1,
      "quantity": null,
      "unit": "克",
      "required": true
    }
  ]
}
```

The array replaces the entire step in request order and may contain at most 50 distinct, currently visible ingredients. `unit` is required and limited to 16 characters. `required` must be present. `quantity` may be `null`, meaning “适量”; otherwise it is nonnegative with at most 9 integer digits and 3 fractional digits. Publication requires at least one required ingredient.

Default-method replacement body:

```json
{
  "version": 3,
  "name": "家常炒",
  "cookingStyle": "炒",
  "steps": [
    { "instruction": "切好食材" },
    { "instruction": "热锅翻炒至熟" }
  ]
}
```

The body replaces the single default method and all its steps in request order. Method name is limited to 40 characters, cooking style to 32, and the array to 12 steps. Incomplete step text may be saved in a draft; publication requires 1-12 nonblank steps, each at most 160 characters.

A recipe aggregate response contains `id`, `status`, `version`, nullable basic fields, `ingredients`, nullable `defaultMethod`, nullable `image`, `incompleteSteps`, and ISO-8601 `updatedAt`. Ingredient items contain `ingredientId`, `name`, nullable `quantity`, `unit`, `required`, and zero-based `sortOrder`. The default method contains `id`, `name`, `cookingStyle`, and ordered `{instruction, sortOrder}` items.

Family list items contain `id`, `status`, nullable `name` and `imageUrl`, basic fields, `version`, creator and last-modifier IDs/names, `completedStep`, and `updatedAt`. List order is `updatedAt` descending, then recipe ID descending.

Image search returns only `APPROVED` assets. A response item exposes:

- `id`, `displayName`, self-hosted `listUrl` and `detailUrl`;
- `sourcePageUrl`, `author`, `licenseName`, `licenseUrl`, and `acquiredOn`;
- original `width` and `height`.

It never exposes the stored original object key or the third-party original-file URL, and the service never proxies a search result or hotlinks it at runtime. Selecting a missing or no-longer-approved asset returns HTTP 422 with `DINNER_RECIPE_IMAGE_INVALID`. Passing `imageAssetId: null` clears a draft's selection. Publication requires an approved asset.

Publication first loads and validates an immutable snapshot. Validation failures return HTTP 422 with `DINNER_RECIPE_VALIDATION_FAILED`; unlike ordinary errors, `data` is an ordered array of objects with stable `step`, `field`, and user-facing `message` values, for example:

```json
{
  "success": false,
  "errorCode": "DINNER_RECIPE_VALIDATION_FAILED",
  "message": "Dinner recipe is incomplete",
  "data": [
    { "step": "BASIC", "field": "name", "message": "请填写菜名" },
    {
      "step": "IMAGE",
      "field": "imageAssetId",
      "message": "请选择一张已审核真实图片"
    }
  ],
  "requestId": "..."
}
```

The normalized moderation content contains flavor, method name, cooking style, and ordered steps and may not exceed 2500 characters. The service looks up the publishing user's server-side WeChat identity: its `openid` must belong to that active mini-program user and is never accepted from the client. It calls WeChat `msgSecCheck` 2.0 with `scene=3` outside a database transaction. Only `result.suggest=pass` continues. `review` or `risky` returns HTTP 422 with `DINNER_RECIPE_CONTENT_REJECTED`; missing identity, missing configuration, platform error, timeout, or retry exhaustion returns HTTP 503 with `DINNER_RECIPE_MODERATION_UNAVAILABLE`. In every failure case the draft remains a draft.

After moderation passes, a short transaction re-locks the recipe and active household membership, revalidates the same expected version, completeness, and approved image, then atomically sets `PUBLISHED`, `publishedAt`, last modifier, and the next version. A change during moderation therefore returns the same 409 conflict instead of publishing stale text.

This vertical slice intentionally does not add household recipes to discovery or tonight-menu selection. Editing a published recipe through a revision draft, method variants, copying system recipes, and archiving are also outside these endpoints even though V6 reserves model fields/statuses for later work.

## Dinner Menu And Records

All menu and record endpoints require a bearer token and an existing household membership row; the membership schema has no status column. `DinnerMenuService` operations and `DinnerRecordService.complete` additionally load the referenced household and reject a missing or non-`ACTIVE` household. Record list and detail only require the membership row and scope results by its `householdId`. The server derives the household and current user from the token.

| Method | Path                                 | Request body                        | Response data                    |
| ------ | ------------------------------------ | ----------------------------------- | -------------------------------- |
| GET    | `/api/dinner/menus/today`            | None                                | Today's merged menu              |
| PUT    | `/api/dinner/menus/today/selections` | `recipeIds`, `version`              | Updated merged menu              |
| POST   | `/api/dinner/menus/today/confirm`    | `version`, UUID v4 `idempotencyKey` | Confirmed menu                   |
| POST   | `/api/dinner/menus/today/complete`   | `version`, UUID v4 `idempotencyKey` | `recordId` and completed menu    |
| GET    | `/api/dinner/records`                | None                                | Completed record summaries       |
| GET    | `/api/dinner/records/{id}`           | None                                | Record detail and dish snapshots |

The menu business day changes at 04:00 in the household timezone. `TodayMenuResponse` contains `id`, `menuDate`, `status`, `version`, `mySelectionCount`, `partnerSelectionCount`, `consensusCount`, `selectedRecipeIds`, merged `dishes`, confirmation/completion metadata, and optional `recordId`. Dish `source` is relative to the current user: `ME`, `PARTNER`, or `BOTH`.

Selection updates replace only the current member's complete selection set. Every write compares `version`; stale writes return HTTP 409 with `DINNER_MENU_VERSION_CONFLICT`. Confirming an empty menu returns `DINNER_MENU_EMPTY`. Updating a completed menu returns `DINNER_MENU_COMPLETED`, and completing a menu that is not confirmed returns `DINNER_MENU_NOT_CONFIRMED`.

Completion is idempotent by both the request key and the unique menu record. Repeated completion returns the existing record and never creates duplicate snapshots.

## Thought Clusters

| Method | Path                             | Request body | Response data          |
| ------ | -------------------------------- | ------------ | ---------------------- |
| GET    | `/api/thoughts/clusters`         | None         | Cluster array          |
| POST   | `/api/thoughts/clusters/rebuild` | None         | Completed job response |

A cluster contains `id`, `userId`, `title`, `thesis`, `fragmentIds`, `maturityScore`, `missingQuestions`, `status`, and `updatedAt`.

The rebuild response contains `userId`, `status`, `note`, and `jobId`. The first version completes synchronously, so a successful response has `status: "completed"` and a persisted job ID.

## Thought Outlines

| Method | Path                              | Request body | Response data     |
| ------ | --------------------------------- | ------------ | ----------------- |
| POST   | `/api/thoughts/outlines/generate` | None         | Generated outline |
| GET    | `/api/thoughts/outlines/{id}`     | None         | Stored outline    |

Generation selects the current user's latest mature cluster, stores the result, and returns it synchronously. The response contains `id`, `userId`, `clusterId`, `titleCandidates`, `coreArgument`, `outline`, `supportingFragmentIds`, `missingMaterials`, and `createdAt`.

`outline` is an array of sections with `title` and `content`. The rule-based first version always creates `问题背景`, `核心论证`, and `行动与补链`.

If no mature cluster exists, generation returns `BUSINESS_ERROR`.

## Fragment API Status

The thought-fragment module is intentionally pending redesign. The frontend currently contains calls to `/api/thoughts/fragments`, but those CRUD endpoints are not available until that redesign is resumed. Do not treat the frontend calls as an active backend contract yet.

## Local Exploration

Use Swagger UI at `http://localhost:8080/swagger-ui.html` for request and response schemas. After logging in, use the **Authorize** action and paste the access token to call protected endpoints.
