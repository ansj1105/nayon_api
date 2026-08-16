# Account Settings And One-Time Share Reward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist account-scoped game settings and grant the existing share reward exactly once per authenticated account.

**Architecture:** PostgreSQL owns account settings, share state, and the DIAMOND reward ledger. Spring exposes authenticated settings and share-reward endpoints from the OpenAPI source of truth. Unity keeps the existing settings/share popup structure, applies local device settings immediately, and derives share buttons and reward balance only from server responses.

**Tech Stack:** PostgreSQL/Flyway SQL, Spring Boot 3/JdbcTemplate/JUnit 5/MockMvc, Unity 6/C#/Unity Test Framework.

## Global Constraints

- `account_id` always comes from the authenticated Cognito token.
- Share-opened and reward-claimed are lifetime account booleans, not daily flags.
- A successful Android sharesheet launch marks `shared=true`; external delivery success is not asserted.
- Reward amount is exactly `50 DIAMOND` and is written once to `economy_ledger`.
- Graphics quality and vibration remain device-local.
- Existing settings popup layout, labels, tabs, and navigation remain unchanged.
- No new dependencies.

---

### Task 1: Flyway schema and rollback

**Files:**
- Create: `/home/ubuntu/work/.worktrees/nayon-cloud-account-settings-share/db/migration/V6__create_player_settings_and_share_reward.sql`
- Create: `/home/ubuntu/work/.worktrees/nayon-cloud-account-settings-share/db/rollback/U6__drop_player_settings_and_share_reward.sql`
- Create: `/home/ubuntu/work/.worktrees/nayon-cloud-account-settings-share/scripts/verify-v6.sh`

**Interfaces:**
- Produces `player_settings(account_id, ..., revision, created_at, updated_at)`.
- Produces `player_share_rewards(id, account_id, shared, reward_claimed, shared_at, reward_claimed_at, share_target, created_at, updated_at)`.
- Preserves the V5 number reserved by the existing offline-authority work.

- [ ] Write `verify-v6.sh` assertions for table columns, boolean/timestamp checks, account uniqueness, and rollback order; verify it fails because V6/U6 are absent.
- [ ] Add V6 with account foreign keys, defaults, supported locale check, lifetime share constraints, and no backfill.
- [ ] Add U6 dropping `player_share_rewards` before `player_settings`.
- [ ] Run `bash scripts/verify-v6.sh`; expect all static migration assertions to pass.
- [ ] Run `git diff --check` and record that the migration creates empty tables only, with no existing-table rewrite or long lock.

### Task 2: Account settings contract and persistence

**Files:**
- Modify: `src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `src/main/java/com/nayon/api/settings/PlayerSettings.java`
- Create: `src/main/java/com/nayon/api/settings/PlayerSettingsPatch.java`
- Create: `src/main/java/com/nayon/api/settings/PlayerSettingsRepository.java`
- Create: `src/main/java/com/nayon/api/settings/JdbcPlayerSettingsRepository.java`
- Create: `src/main/java/com/nayon/api/settings/PlayerSettingsService.java`
- Create: `src/main/java/com/nayon/api/interfaces/PlayerSettingsController.java`
- Create: `src/main/java/com/nayon/api/interfaces/PlayerSettingsPatchRequest.java`
- Create: `src/main/java/com/nayon/api/interfaces/PlayerSettingsResponse.java`
- Create: `src/test/java/com/nayon/api/settings/PlayerSettingsServiceTest.java`
- Create: `src/test/java/com/nayon/api/interfaces/PlayerSettingsContractTest.java`
- Create: `src/test/java/com/nayon/api/integration/PlayerSettingsPostgresTest.java`

**Interfaces:**
- `GET /api/v1/me/settings -> PlayerSettingsResponse`.
- `PATCH /api/v1/me/settings` consumes nullable fields only and returns the complete updated state.
- Supported language codes are `ko`, `en`, `ja`, `zh-Hans`, `zh-Hant`, `th`, `vi`, `id`, `es`, `pt`, `de`, `fr`, `ru`, `ar`, `tr`.

- [ ] Add OpenAPI paths and schemas first, including authenticated 200/400/401 behavior and examples.
- [ ] Write contract tests proving authentication, defaults, partial patch preservation, empty-patch rejection, and unsupported-language rejection; run them and observe missing-endpoint failures.
- [ ] Write service tests proving account isolation and revision increment; run them and observe missing-service failures.
- [ ] Implement immutable settings records, repository upsert, validation, controller DTO mapping, and exception mapping using existing project patterns.
- [ ] Run `./gradlew test --tests '*PlayerSettings*' --no-daemon`; expect all settings tests to pass.

### Task 3: Server-authoritative one-time share reward

**Files:**
- Modify: `src/main/resources/openapi/nayon-api-v1.yaml`
- Modify: `src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java`
- Modify: `src/main/java/com/nayon/api/economy/EconomyRepository.java`
- Modify: `src/main/java/com/nayon/api/economy/JdbcEconomyRepository.java`
- Create: `src/main/java/com/nayon/api/share/ShareRewardState.java`
- Create: `src/main/java/com/nayon/api/share/ShareRewardResult.java`
- Create: `src/main/java/com/nayon/api/share/ShareRewardRepository.java`
- Create: `src/main/java/com/nayon/api/share/JdbcShareRewardRepository.java`
- Create: `src/main/java/com/nayon/api/share/ShareRewardService.java`
- Create: `src/main/java/com/nayon/api/share/ShareRequiredException.java`
- Create: `src/main/java/com/nayon/api/share/EconomyNotBootstrappedForShareException.java`
- Create: `src/main/java/com/nayon/api/interfaces/ShareRewardController.java`
- Create: `src/main/java/com/nayon/api/interfaces/ShareOpenedRequest.java`
- Create: `src/main/java/com/nayon/api/interfaces/ShareRewardResponse.java`
- Create: `src/test/java/com/nayon/api/share/ShareRewardServiceTest.java`
- Create: `src/test/java/com/nayon/api/interfaces/ShareRewardContractTest.java`
- Create: `src/test/java/com/nayon/api/integration/ShareRewardPostgresTest.java`

**Interfaces:**
- `GET /api/v1/me/share-reward` returns `shared`, `rewardClaimed`, `canShare`, `canClaim`, reward metadata, and current economy when claimed.
- `POST /api/v1/me/share-reward/share-opened` optionally consumes `target` and idempotently returns state.
- `POST /api/v1/me/share-reward/claim` consumes `Idempotency-Key: UUID` and returns state plus current economy.
- `EconomyRepository.creditCurrency(...)` updates `player_wallets` and writes an `economy_ledger` row in the caller transaction.

- [ ] Add OpenAPI paths, request/response/error schemas, and DIAMOND 50 examples first.
- [ ] Write service tests for initial state, idempotent share-opened, share-required rejection, exactly-once claim, and account isolation; verify RED.
- [ ] Write contract tests for auth, response-derived button flags, malformed idempotency key, and `SHARE_REQUIRED`; verify RED.
- [ ] Implement repository row locking and service transaction, using `reward_claimed` recheck before calling `creditCurrency`.
- [ ] Write PostgreSQL integration tests for ledger values, same-request replay, different-request replay, and two concurrent claims; verify RED then GREEN.
- [ ] Run `./gradlew test --tests '*ShareReward*' --no-daemon`; expect all share tests to pass.

### Task 4: Unity API models and account setting synchronization

**Files:**
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/NayonCloudRuntime.cs`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Settings/AccountGameSettings.cs`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Settings/AccountGameSettings.cs.meta`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Settings/AccountSettingsSynchronizer.cs`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Settings/AccountSettingsSynchronizer.cs.meta`
- Modify: `NYAON_HUNTERS/Assets/Tests/Editor/NayonApiClientTests.cs`
- Create: `NYAON_HUNTERS/Assets/Tests/Editor/AccountSettingsSynchronizerTests.cs`
- Create: `NYAON_HUNTERS/Assets/Tests/Editor/AccountSettingsSynchronizerTests.cs.meta`

**Interfaces:**
- `INayonCloudApi.GetSettingsAsync`, `PatchSettingsAsync`, `GetShareRewardAsync`, `MarkShareOpenedAsync`, and `ClaimShareRewardAsync` mirror OpenAPI paths.
- Local settings apply synchronously; failed account PATCH keeps only changed fields pending for retry.
- Quality and vibration never enter an API request.

- [ ] Add failing API-client request/response tests for all five endpoints.
- [ ] Add failing synchronizer tests for first-link seed, server restore, partial dirty retry, and device-local exclusions.
- [ ] Implement models and client methods using the existing authenticated request transport.
- [ ] Implement the minimal synchronizer and hook it into successful account initialization and runtime retry.
- [ ] Run Unity EditMode tests for API client and settings synchronizer; expect GREEN.

### Task 5: Unity settings consumers and share popup flow

**Files:**
- Modify: `NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/UI/Popup/GameScene/UI_BattleSettingsGeneralPopup.cs`
- Modify: the existing concrete joystick/effect/critical-effect runtime owners found by source tracing; do not add UI-side fallbacks.
- Create: `NYAON_HUNTERS/Assets/Tests/Editor/ShareRewardFlowTests.cs`
- Create: `NYAON_HUNTERS/Assets/Tests/Editor/ShareRewardFlowTests.cs.meta`

**Interfaces:**
- Popup state is derived from server `shared/rewardClaimed`; local dates are removed from authority decisions.
- `share-opened` is called only after the native sharesheet starts without exception.
- Claim displays the server economy result and never calls `GrantAttendanceMaterial` locally.

- [ ] Trace and name the real runtime owners for joystick visibility, reduced effects, and reduced critical effects before editing.
- [ ] Add failing tests for the three share button states and for no local reward grant.
- [ ] Replace PlayerPrefs daily share checks with API-backed lifetime state while preserving the current prefab names and layout.
- [ ] Connect settings toggles to their owning runtime systems; keep local PlayerPrefs only as cache/offline state.
- [ ] Run targeted Unity EditMode tests and a Windows Unity compile.
- [ ] Open the current popup in Windows Unity, verify initial/shared/claimed button transitions with a non-production test response, and confirm no new console exception.

### Task 6: Cross-repository verification

**Files:**
- Modify only documentation or tests required by observed verification failures.

- [ ] Run Flyway V6 static checks and `git diff --check`.
- [ ] Run the complete API `./gradlew test --no-daemon` suite.
- [ ] Run PostgreSQL integration tests with migrations V1 through V6 and record duration.
- [ ] Run Unity targeted EditMode tests and Windows compile.
- [ ] Compare OpenAPI fields to Unity JSON models field-for-field.
- [ ] Review diffs for secrets, unrelated UI changes, local-only reward authority, and accidental V5 reuse.
- [ ] Report local completion separately from commit, push, and deployment state.
