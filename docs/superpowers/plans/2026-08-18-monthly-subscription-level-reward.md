# NYAON Monthly Subscription and Level Reward Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace local permanent monthly-pass flags and client-granted level rewards with two independent Google Play auto-renewing subscriptions and server-authoritative lifetime-once reward claims.

**Architecture:** Extend the existing store catalog with subscription product types, persist Google subscription lifecycle state from `subscriptionsv2` and authenticated RTDN, and expose account-scoped entitlement/reward APIs. Unity registers subscription products, combines Google localized prices with API reward data, and applies economy only from authoritative API responses.

**Tech Stack:** PostgreSQL 16, Flyway SQL, Java 21, Spring Boot/JdbcTemplate, OpenAPI YAML, Google Play Android Publisher v3, Unity 6000.5.6f1, Unity IAP 5.4.0, NUnit.

## Global Constraints

- Work, commit, and push only on `develop-sj`; `develop`/`main` are read-only rebase bases.
- `MONTHLY_GROWTH` and `MONTHLY_ADVANCED` are independent one-month auto-renewing subscriptions.
- `PREMIUM` requires only `MONTHLY_GROWTH`; `ROYAL` requires only `MONTHLY_ADVANCED`.
- Level reward claims never reset and are unique by account, track, and required level.
- Prices come only from Google Play localized metadata.
- Product IDs, reward values, required levels, and benefit values come from the database.
- Purchase tokens never appear in logs, metrics labels, API responses, docs, or tests.
- OpenAPI changes precede server DTO/controller implementation.
- Database forward and rollback migrations ship together.

---

### Task 1: Add subscription and reward schema

**Files:**
- Create: `nayon_cloud/db/migration/V13__create_subscriptions_and_level_rewards.sql`
- Create: `nayon_cloud/db/rollback/U13__drop_subscriptions_and_level_rewards.sql`
- Create: `nayon_cloud/scripts/verify-v13.sh`

**Interfaces:**
- Produces: `subscription_plans`, `subscription_benefit_versions`, `player_subscriptions`, `google_play_rtdn_events`, `level_reward_versions`, `player_level_reward_claims`, `player_subscription_initial_rewards`, `player_subscription_daily_rewards`.
- Produces: `store_products.product_type IN ('ONE_TIME','SUBSCRIPTION')` and `store_product_versions.fulfillment_type IN ('DIRECT_CURRENCY','LIMITED_BENEFIT','SUBSCRIPTION')`.

- [ ] **Step 1: Create the failing migration verifier**

Add assertions that V13 tables exist, active reward definitions are unique by `(track_code, required_level)`, purchase token hashes are globally unique, level claims reject duplicate `(account_id, track_code, required_level)`, and store products accept `SUBSCRIPTION` but reject unknown types.

- [ ] **Step 2: Run the verifier and confirm RED**

Run: `bash scripts/verify-v13.sh`

Expected: failure because V13 does not exist or subscription tables are missing.

- [ ] **Step 3: Implement V13 and U13**

Use enum-like checks for the fixed plan/track/state codes, partial unique indexes for active catalog rows, account ownership foreign keys, and immutable reward snapshots in claim rows. Seed only stable plan/track codes; do not seed real product IDs, prices, or final reward quantities.

- [ ] **Step 4: Verify forward, constraints, and rollback**

Run: `bash scripts/verify-v13.sh`

Expected: V1→V13 applies, all constraint probes pass, U13 removes only V13 objects, and V1→V12 remains valid afterward.

- [ ] **Step 5: Commit the DB stage**

```bash
git add db/migration/V13__create_subscriptions_and_level_rewards.sql \
  db/rollback/U13__drop_subscriptions_and_level_rewards.sql \
  scripts/verify-v13.sh
git commit -m "feat(db): add monthly subscription authority"
```

---

### Task 2: Publish subscription and reward OpenAPI contracts

**Files:**
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `nayon_api/src/test/java/com/nayon/api/subscription/SubscriptionOpenApiContractTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/levelreward/LevelRewardOpenApiContractTest.java`

**Interfaces:**
- Produces: `GET /subscriptions/catalog`, `GET /me/subscriptions`, `POST /store/subscriptions/google-play/verify`, `POST /public/google-play/rtdn`, `GET /me/level-rewards`, `POST /me/level-rewards/{trackCode}/{requiredLevel}/claim`, and `POST /me/subscriptions/{planCode}/daily-reward/claim`.
- Produces enums: `SubscriptionPlanCode`, `SubscriptionState`, `LevelRewardTrackCode`.

- [ ] **Step 1: Write RED contract tests**

Assert exact operation IDs, required `Idempotency-Key` references, status codes `200/201/400/401/403/404/409/422/429/503`, int64 reward quantities, nullable expiry fields, and absence of client-supplied price/reward/level fields in write requests.

- [ ] **Step 2: Run contract tests and confirm RED**

Run: `./gradlew test --tests '*SubscriptionOpenApiContractTest' --tests '*LevelRewardOpenApiContractTest'`

Expected: failure because the paths and schemas are absent.

- [ ] **Step 3: Add OpenAPI paths and schemas**

The verify request contains only `productId` and `purchaseToken`. The reward claim has no body. Responses include authoritative entitlement, claim state, reward snapshot, and economy balance.

- [ ] **Step 4: Run contract tests and existing store contract tests**

Run: `./gradlew test --tests '*OpenApiContractTest'`

Expected: all contract tests pass.

- [ ] **Step 5: Commit the contract stage**

```bash
git add src/main/resources/openapi/nayon-api-v1.yaml \
  src/test/java/com/nayon/api/subscription/SubscriptionOpenApiContractTest.java \
  src/test/java/com/nayon/api/levelreward/LevelRewardOpenApiContractTest.java
git commit -m "feat(api): publish subscription contracts"
```

---

### Task 3: Implement subscription catalog and Google verification

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionPlanCode.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionState.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionPlan.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/PlayerSubscription.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/JdbcSubscriptionRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/SubscriptionException.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/google/GooglePlaySubscription.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/google/GooglePlaySubscriptionGateway.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/google/HttpGooglePlaySubscriptionGateway.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/SubscriptionController.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/SubscriptionVerifyRequest.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/SubscriptionCatalogResponse.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/SubscriptionResponse.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java`
- Modify: `nayon_api/src/main/resources/application.yml`
- Create: `nayon_api/src/test/java/com/nayon/api/subscription/google/HttpGooglePlaySubscriptionGatewayTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/subscription/SubscriptionServiceTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/integration/SubscriptionPostgresTest.java`

**Interfaces:**
- Consumes: V13 schema and OpenAPI Task 2.
- Produces: `SubscriptionService.catalog(accountId)`, `findAll(accountId)`, and `verify(accountId, requestId, productId, purchaseToken)`.
- Produces: `GooglePlaySubscriptionGateway.get(purchaseToken)` using `/androidpublisher/v3/applications/{package}/purchases/subscriptionsv2/tokens/{token}`.

- [ ] **Step 1: Write RED parser and service tests**

Cover active, canceled-before-expiry, grace, hold, paused, expired, revoked, wrong product, wrong obfuscated account, linked token, duplicate request, duplicate token across accounts, and transient Google failure.

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests '*SubscriptionServiceTest' --tests '*HttpGooglePlaySubscriptionGatewayTest'`

- [ ] **Step 3: Implement the minimal gateway and repository**

Parse `subscriptionState`, `lineItems[].productId`, maximum `expiryTime`, `linkedPurchaseToken`, `acknowledgementState`, and `externalAccountIdentifiers.obfuscatedExternalAccountId`. Hash tokens with SHA-256 for lookup; return no token in response objects.

- [ ] **Step 4: Implement service and controller**

Serialize by account, request ID, and token hash advisory locks. Preserve the last authoritative entitlement on ambiguous Google failures. Treat canceled subscriptions as entitled only before expiry; hold/paused/expired/revoked are not entitled.

- [ ] **Step 5: Run unit and PostgreSQL integration tests**

Run: `./gradlew test --tests '*Subscription*Test'`

Run: `E2E_DB=1 ./gradlew test --tests '*SubscriptionPostgresTest'`

- [ ] **Step 6: Commit the verification stage**

```bash
git add src/main/java/com/nayon/api/subscription \
  src/main/java/com/nayon/api/interfaces/Subscription* \
  src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java \
  src/main/resources/application.yml \
  src/test/java/com/nayon/api/subscription \
  src/test/java/com/nayon/api/integration/SubscriptionPostgresTest.java
git commit -m "feat(api): verify Google Play subscriptions"
```

---

### Task 4: Implement authenticated RTDN lifecycle sync

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/rtdn/GooglePlayRtdnMessage.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/rtdn/GooglePlayRtdnAuthenticator.java`
- Create: `nayon_api/src/main/java/com/nayon/api/subscription/rtdn/GooglePlayRtdnService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/GooglePlayRtdnController.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/config/SecurityConfig.java`
- Modify: `nayon_api/src/main/resources/application.yml`
- Create: `nayon_api/src/test/java/com/nayon/api/subscription/rtdn/GooglePlayRtdnServiceTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/integration/GooglePlayRtdnPostgresTest.java`

**Interfaces:**
- Consumes: `SubscriptionService.reconcileByToken(token)` and V13 event table.
- Produces: authenticated Pub/Sub push endpoint that returns 204 for a newly processed or duplicate valid message.

- [ ] **Step 1: Write RED auth and idempotency tests**

Reject missing/invalid bearer tokens, wrong audience, wrong service account email, wrong package name, oversized/base64-invalid payloads, and duplicate message IDs. Verify a valid notification calls subscriptions v2 and updates entitlement once.

- [ ] **Step 2: Confirm RED**

Run: `./gradlew test --tests '*GooglePlayRtdn*Test'`

- [ ] **Step 3: Implement OIDC validation and message parsing**

Use configured Google JWKS/issuer, exact audience, and exact service account email. Do not trust notification status; use only its token to call the subscription gateway.

- [ ] **Step 4: Implement event dedupe and reconciliation**

Insert `message_id` before processing under a transaction-safe ownership rule, persist result code, and allow retry only for retryable failures without granting stale entitlement.

- [ ] **Step 5: Run RTDN and security tests**

Run: `./gradlew test --tests '*GooglePlayRtdn*Test' --tests '*Security*Test'`

- [ ] **Step 6: Commit the RTDN stage**

```bash
git add src/main/java/com/nayon/api/subscription/rtdn \
  src/main/java/com/nayon/api/interfaces/GooglePlayRtdnController.java \
  src/main/java/com/nayon/api/config/SecurityConfig.java \
  src/main/resources/application.yml \
  src/test/java/com/nayon/api/subscription/rtdn \
  src/test/java/com/nayon/api/integration/GooglePlayRtdnPostgresTest.java
git commit -m "feat(api): sync subscription RTDN events"
```

---

### Task 5: Implement lifetime-once level and monthly rewards

**Files:**
- Create: `nayon_api/src/main/resources/progression/account-level-catalog-v1.json`
- Create: `nayon_api/src/main/java/com/nayon/api/progression/AccountLevelCatalog.java`
- Create: `nayon_api/src/main/java/com/nayon/api/levelreward/LevelRewardTrackCode.java`
- Create: `nayon_api/src/main/java/com/nayon/api/levelreward/LevelRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/levelreward/JdbcLevelRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/levelreward/LevelRewardService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/levelreward/LevelRewardException.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/LevelRewardController.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/LevelRewardResponse.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/SubscriptionDailyRewardController.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/economy/EconomyRepository.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/economy/JdbcEconomyRepository.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java`
- Create: `nayon_api/src/test/java/com/nayon/api/progression/AccountLevelCatalogTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/levelreward/LevelRewardServiceTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/integration/LevelRewardPostgresTest.java`

**Interfaces:**
- Consumes: authoritative `player_progression.account_exp`, active subscription entitlement, V13 reward catalog.
- Produces: `LevelRewardService.get(accountId)`, `claim(accountId, requestId, track, requiredLevel)`, and daily subscription reward claim.

- [ ] **Step 1: Export and contract-test the level curve**

Copy the exact Unity `AccountLevelData` requirements into the versioned JSON. Test levels 1, 2, 5, 50 and the maximum boundary against known Unity totals.

- [ ] **Step 2: Write RED reward tests**

Cover insufficient level, inactive paid track, independent premium/royal entitlement, free track, replay, different-key duplicate claim, concurrent claims, catalog version changes, renewal/re-subscription without reset, initial reward once, and daily reward once per server date.

- [ ] **Step 3: Confirm RED**

Run: `./gradlew test --tests '*AccountLevelCatalogTest' --tests '*LevelReward*Test'`

- [ ] **Step 4: Implement transactional claims**

Acquire the existing `battle-account:<accountId>` advisory lock before progression/economy writes. Read catalog and entitlement in the same transaction, insert the immutable claim snapshot, then credit the ledger and return the authoritative balance.

- [ ] **Step 5: Run all API and PostgreSQL tests**

Run: `./gradlew test`

Run: `./scripts/verify-postgres-integration.sh`

- [ ] **Step 6: Commit the reward stage**

```bash
git add src/main/resources/progression \
  src/main/java/com/nayon/api/progression \
  src/main/java/com/nayon/api/levelreward \
  src/main/java/com/nayon/api/interfaces/LevelReward* \
  src/main/java/com/nayon/api/interfaces/SubscriptionDailyRewardController.java \
  src/main/java/com/nayon/api/economy \
  src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java \
  src/test/java/com/nayon/api/progression \
  src/test/java/com/nayon/api/levelreward \
  src/test/java/com/nayon/api/integration/LevelRewardPostgresTest.java
git commit -m "feat(api): grant lifetime level rewards"
```

---

### Task 6: Connect Unity subscription runtime

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Store/UnityIapStoreGateway.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Store/NayonStoreRuntime.cs`
- Create: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Subscription/SubscriptionSynchronizer.cs`
- Create corresponding Unity `.meta` files.
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/NayonCloudRuntime.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs`
- Create: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/Editor/SubscriptionSynchronizerTests.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/Editor/NayonApiClientTests.cs`

**Interfaces:**
- Consumes: Task 2 API contract.
- Produces: account-scoped subscription/level reward snapshots, subscription purchase by plan code, claim calls, and store product definitions with `ProductType.Subscription`.

- [ ] **Step 1: Write RED API and synchronization tests**

Assert exact paths, idempotency headers, JSON mapping, account-switch stale-response rejection, subscription product registration, localized price mapping, pending purchase retry, and no entitlement inference on network failure.

- [ ] **Step 2: Run the filtered Unity tests and confirm RED**

Run Windows Unity EditMode tests for `SubscriptionSynchronizerTests` and `NayonApiClientTests`; expected failure is missing subscription API/model/runtime behavior.

- [ ] **Step 3: Implement API models and subscription synchronizer**

Cache snapshots only under the current public account ID. Use request generation/cancellation guards. Keep pending purchase tokens in the existing account-scoped encrypted/file queue pattern and delete only after server grant plus Unity IAP confirmation.

- [ ] **Step 4: Register correct Unity IAP product types**

Map `ONE_TIME` to `Consumable` and `SUBSCRIPTION` to `Subscription`; never infer product type from product ID or display name.

- [ ] **Step 5: Run filtered and full EditMode tests**

Expected: subscription tests pass, then the complete EditMode suite passes with zero failures.

- [ ] **Step 6: Commit the Unity runtime stage**

```bash
git add NYAON_HUNTERS/Assets/Scripts/Cloud \
  NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs \
  NYAON_HUNTERS/Assets/Tests/Editor
git commit -m "feat(unity): sync monthly subscriptions"
```

---

### Task 7: Replace local monthly and level reward UI authority

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Modify only if serialized binding is required: `Nayon_Hunters/NYAON_HUNTERS/Assets/GameContent/UI/Prefabs/Lobby/Pages/Battle/Events/MonthlyPayment/UI_MissionPrepPassPopup.prefab`
- Modify only if serialized binding is required: `Nayon_Hunters/NYAON_HUNTERS/Assets/GameContent/UI/Prefabs/Lobby/Pages/Battle/Events/HunterLevelReward/UI_HunterLevelRewardPage.prefab`
- Create: `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/Editor/MonthlySubscriptionUiPolicyTests.cs`

**Interfaces:**
- Consumes: `NayonGameCloudBridge.SubscriptionSnapshot`, `LevelRewardSnapshot`, localized price lookup, purchase and claim methods.
- Removes authority from: `MissionPrepPassPurchased_*`, `HunterLevelFundPurchased_*`, `HunterLevelFundRewardReceived_*`, and `NyaonGrowthFundRewardReset_*`.

- [ ] **Step 1: Write RED UI policy tests**

Assert general-only unlocks PREMIUM, advanced-only unlocks ROYAL, both remain independent, expired subscriptions lock only unclaimed paid cells, claimed cells remain complete, free claims need no subscription, and missing server state never becomes purchased.

- [ ] **Step 2: Confirm RED**

Run the filtered EditMode policy test; expected failure is local PlayerPrefs authority and hardcoded reward definitions.

- [ ] **Step 3: Bind current prefabs to server snapshots**

Preserve current hierarchy, Korean labels, navigation, and popup ownership. Replace only data and actions: localized prices, plan state, expiry, reward rows, purchase action, claim action, daily claim, and active benefit checks.

- [ ] **Step 4: Remove reset and local grant paths**

Delete `ResetHunterLevelFundRewardsOnce`, direct `ExchangeMaterial` reward grants, and purchase completion methods that set PlayerPrefs. Keep unrelated one-time permanent battle pass behavior unchanged.

- [ ] **Step 5: Verify Unity tests and live screen**

Synchronize Windows Git to the exact reviewed `develop-sj` SHA, run full EditMode tests, open the current growth-fund and monthly-pass pages, and confirm no new console exception. Verify Google test products only after Play Console setup.

- [ ] **Step 6: Commit the UI stage**

```bash
git add NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs \
  NYAON_HUNTERS/Assets/Tests/Editor/MonthlySubscriptionUiPolicyTests.cs \
  NYAON_HUNTERS/Assets/GameContent/UI/Prefabs/Lobby/Pages/Battle/Events
git commit -m "feat(unity): bind monthly reward screens"
```

---

### Task 8: Publish and deploy with external-configuration guard

**Files:**
- Modify: `nayon_api/.env.example`
- Modify: `nayon_api/README.md`
- Create: `nayon_api/docs/google-play-subscription-setup.md`
- Modify: `nayon_api/scripts/verify-deploy-contract.sh`

**Interfaces:**
- Produces exact runtime settings for Google package name, service-account credentials, RTDN issuer/JWKS, audience, push service account, and catalog activation order.

- [ ] **Step 1: Add RED deployment contract assertions**

Assert all non-secret variable names, Docker read-only credential mount, health route, and RTDN route are present. Assert no sample contains a real product token, private key, client secret, or service-account JSON.

- [ ] **Step 2: Document Play Console and Pub/Sub steps**

Document two subscription products with monthly auto-renew base plans, RTDN topic permission, authenticated push subscription, exact product ID insertion SQL template, and license tester scenarios. Use placeholders only for operator-supplied identifiers and explicitly label them as operator inputs, not unresolved software behavior.

- [ ] **Step 3: Run final verification**

Run:

```bash
./gradlew clean test build
./scripts/verify-postgres-integration.sh
./scripts/verify-deploy-contract.sh
git diff --check origin/main
```

Run the full Unity EditMode suite against the exact WSL/Windows SHA.

- [ ] **Step 4: Push only `develop-sj` branches**

Before each push:

```bash
test "$(git branch --show-current)" = "develop-sj"
git push --force-with-lease origin HEAD:refs/heads/develop-sj
```

Never update `refs/heads/main` or `refs/heads/develop`.

- [ ] **Step 5: Deploy DB then API**

Deploy V13 before the API. Keep subscription catalog inactive until both API health and migration verification pass. Deploying Unity/APK and enabling products waits for Play Console product IDs and RTDN configuration.

- [ ] **Step 6: Verify production**

Check API health, authenticated catalog and entitlement reads, RTDN test notification, duplicate RTDN idempotency, metrics/log redaction, and alarms. Do not perform a real charge; use a license tester purchase.

- [ ] **Step 7: Commit operational documentation**

```bash
git add .env.example README.md docs/google-play-subscription-setup.md \
  scripts/verify-deploy-contract.sh
git commit -m "docs(ops): add subscription release runbook"
```
