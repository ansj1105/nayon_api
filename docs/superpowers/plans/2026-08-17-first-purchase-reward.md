# NYAON First Purchase Reward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grant one server-authoritative first-purchase bundle exactly once and make Unity display the persisted result without local reward authority.

**Architecture:** Extend the existing Google Play receipt grant transaction with a one-row-per-account first-purchase reward. The server resolves the random normal-grade equipment, stores the complete snapshot, credits both currencies, and exposes the result through purchase verification and a read endpoint. Unity consumes those responses and removes its PlayerPrefs/local grant path.

**Tech Stack:** PostgreSQL 16, Flyway SQL, Java 21/Spring Boot/JdbcTemplate, OpenAPI 3.1, Unity 6/C#/Newtonsoft JSON/Unity Test Framework.

## Global Constraints

- Remote Git state is authoritative; keep the Unity feature branch rebased on `origin/develop`.
- Google Play verification remains the only eligibility proof.
- Account reward and normal purchase grant are one transaction and use existing `battle-account:<accountId>` lock order.
- No client-supplied reward, price, equipment, or eligibility fields.
- No local fallback grant when the API is unavailable.
- Do not commit, push, or deploy unless the latest user message explicitly requests it.

---

### Task 1: PostgreSQL first-purchase authority

**Files:**
- Create: `nayon_cloud/db/migration/V10__create_first_purchase_rewards.sql`
- Create: `nayon_cloud/db/rollback/U10__drop_first_purchase_rewards.sql`
- Create: `nayon_cloud/scripts/verify-v10.sh`

**Interfaces:**
- Produces: `first_purchase_reward_versions`, `player_first_purchase_rewards`, and unique `(id, account_id)` receipt ownership key.

- [ ] Write `verify-v10.sh` first. It must apply V1 through V10 and fail until V10 exists; fixtures assert one reward per account, one qualifying receipt per reward, same-account composite FK, positive reward balances, one active version, and reverse-order rollback.
- [ ] Run `bash scripts/verify-v10.sh`; expected RED is the missing V10 migration.
- [ ] Add V10 with the version table, account reward table, ownership FK, checks, indexes, and seeded version 1 (`COMMON`, diamond 50, gold 10000, current catalog version).
- [ ] Add U10 that drops the reward table, version table, and receipt composite unique constraint in dependency order.
- [ ] Run `bash scripts/verify-v10.sh`; expected GREEN with forward and rollback timings.

### Task 2: API exact-once grant and query contract

**Files:**
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `nayon_api/src/main/java/com/nayon/api/store/FirstPurchaseReward.java`
- Create: `nayon_api/src/main/java/com/nayon/api/store/FirstPurchaseRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/store/JdbcFirstPurchaseRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/store/FirstPurchaseRewardService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/FirstPurchaseRewardController.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/FirstPurchaseRewardResponse.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/store/JdbcStorePurchaseRepository.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/store/StorePurchaseReceipt.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/store/StorePurchaseResult.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/interfaces/StorePurchaseResponse.java`
- Modify: `nayon_api/src/test/java/com/nayon/api/store/StoreOpenApiContractTest.java`
- Modify: `nayon_api/src/test/java/com/nayon/api/integration/StorePurchasePostgresTest.java`

**Interfaces:**
- Produces: `GET /api/v1/store/first-purchase-reward` and nullable `StorePurchaseResponse.firstPurchaseReward`.
- `FirstPurchaseReward` contains status, qualifying receipt, version, selected equipment, two credited amounts/balances, economy snapshot, and granted timestamp.

- [ ] Update the OpenAPI contract test and PostgreSQL integration test first. Tests must assert pre-purchase `NOT_GRANTED`, first grant bundle, receipt replay, later purchase null bonus, concurrent first purchases, account isolation, and rollback when reward configuration is invalid.
- [ ] Run targeted tests; expected RED is missing route/schema/types and tables.
- [ ] Update OpenAPI first with the GET route, schemas, and nullable purchase-response field. This is additive and non-breaking.
- [ ] Implement the minimal reward records/repository/service/controller. Reuse `GachaCatalog` normal non-chroma candidates and `SecureRandom`; do not create a new catalog or client-controlled picker.
- [ ] Extend `JdbcStorePurchaseRepository.grant` so ordinary purchase credit, selected `player_equipment`, diamond/gold ledger credits, reward snapshot insert, and receipt grant share one transaction. Existing reward rows are returned without new ledger writes.
- [ ] Run targeted tests until GREEN, then run `./gradlew test` and `./scripts/verify-postgres-integration.sh`.

### Task 3: Unity API models and bridge state

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Store/NayonStoreRuntime.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/NayonCloudRuntime.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/EditMode/NayonApiClientTests.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/EditMode/StorePurchaseCoordinatorTests.cs`

**Interfaces:**
- Consumes: API `FirstPurchaseRewardResponse` and `StorePurchaseResponse.firstPurchaseReward`.
- Produces: account-scoped cached reward state, refresh method, and purchase-granted event containing the authoritative bonus.

- [ ] Add failing JSON/client/runtime tests for GET parsing, nullable purchase bonus, account-switch cache isolation, exact equipment-ID application, and no local application on request failure.
- [ ] Run Unity EditMode tests; expected RED is missing models/client methods.
- [ ] Add minimal models and API method, propagate the bonus through store runtime, and cache only when the current PublicId still matches the request owner.
- [ ] Apply the economy snapshot and selected equipment once using an account/equipment-ID key; never create a random local equipment.
- [ ] Re-run targeted EditMode tests until GREEN.

### Task 4: Unity first-purchase event popup

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/EditMode/StoreUiPolicyTests.cs`

**Interfaces:**
- Consumes: `NayonGameCloudBridge` cached/refreshed first-purchase reward.
- Produces: disabled pre-purchase cards and read-only granted-state presentation.

- [ ] Add failing UI-policy tests proving `NOT_GRANTED` disables all cards, `GRANTED` marks all cards complete, and API error cannot enable a reward.
- [ ] Run targeted tests; expected RED is the current PlayerPrefs/local grant behavior.
- [ ] Remove `EventPurchaseRewardClaimed_*` ownership and all three local grant branches. On popup open, fetch server state with a generation/lifecycle guard; before purchase show disabled cards plus `상점 보기`; after grant show all complete and selected equipment detail.
- [ ] Re-run targeted tests and the complete Unity EditMode suite.

### Task 5: Cross-repo verification and Windows Unity sync

**Files:**
- No new production files.

- [ ] Run `git diff --check` in all three worktrees and inspect diffs for unrelated changes or secrets.
- [ ] Run V10 verification, API unit/integration suites, and Unity EditMode tests from the reviewed WSL SHA.
- [ ] Update `C:\work\Nayon_Hunters-develop` to the exact reviewed feature SHA through Windows Git while preserving `Library/`.
- [ ] Open Unity 6000.5.6f1 on that clone, confirm zero compile errors, and inspect the store/first-purchase popup states.
- [ ] Report exact local verification results and explicitly state that commit, push, and deploy were not performed.
