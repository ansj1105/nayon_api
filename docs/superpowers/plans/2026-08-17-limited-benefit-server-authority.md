# Limited Benefit Server Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 24-step Unity-local limited-benefit economy with a KST-daily, account-scoped, exactly-once server campaign covering free, Google Play, and AdMob SSV fulfillment.

**Architecture:** PostgreSQL V11 owns immutable campaign definitions, daily claims, paid receipt correlation, and ad sessions/callback audit. Spring exposes additive campaign, claim, and SSV endpoints and performs every reward bundle in one account-locked transaction. Unity preserves the existing prefabs but renders server offers and never uses device time or `PlayerPrefs` as authority.

**Tech Stack:** PostgreSQL 16, Flyway SQL, Spring Boot 3/Java 21/JdbcTemplate, OpenAPI 3.1, Unity 6000.5.6f1/C#, Google Play Billing, Google Mobile Ads SSV.

## Global Constraints

- Reset each account daily at `00:00 Asia/Seoul`; only server time determines the cycle.
- Keep the existing 24-row order, titles, and visible prefab hierarchy.
- No local reward fallback for FREE, GOOGLE_PLAY, or ADMOB_SSV.
- Existing direct-diamond products remain backward compatible.
- Price is supplied by Google Play; reward definitions are versioned in PostgreSQL.
- Work on existing feature worktrees; do not commit, push, or deploy unless the newest user message explicitly requests it.

---

### Task 1: V11 campaign and proof schema

**Files:**
- Create: `nayon_cloud/db/migration/V11__create_limited_benefit_campaigns.sql`
- Create: `nayon_cloud/db/rollback/U11__drop_limited_benefit_campaigns.sql`
- Create: `nayon_cloud/scripts/verify-v11.sh`

**Interfaces:**
- Consumes: existing `player_accounts`, `store_products`, `store_product_versions`, `store_purchase_receipts`, and `player_equipment` ownership keys.
- Produces: versioned campaign/offer/reward rows, account-cycle claims, ad sessions/callbacks, and `store_product_versions.fulfillment_type`.

- [x] **Step 1: Write `verify-v11.sh` before the migration**

The verifier must apply V1 through V10, expect V11 to be absent, apply V11, and assert:

```sql
select count(*) from limited_benefit_offers where campaign_version_id =
  '00000000-0000-0000-0000-000000001101'; -- 24
select count(*) from limited_benefit_offer_rewards; -- 56
```

It must attempt duplicate account/offer/cycle claims, cross-account receipt ownership, duplicate AdMob transaction IDs, invalid reward codes, and a second active campaign version and require each to raise `unique_violation`, `foreign_key_violation`, or `check_violation` as applicable.

- [x] **Step 2: Run RED**

Run: `bash scripts/verify-v11.sh`

Expected: failure because V11 and its tables do not exist.

- [x] **Step 3: Add the forward/rollback pair**

Seed the exact 24 current offer codes and reward quantities from `UI_BattlePopup.GetLimitedBenefitOffers()`. Use stable UUIDs, KST, inactive provider mappings for paid/ad rows, composite ownership foreign keys, and reverse dependency order in U11.

- [x] **Step 4: Run GREEN and record timings**

Run: `bash scripts/verify-v11.sh`

Expected: forward and rollback pass with measured milliseconds.

- [x] **Step 5: Publishing checkpoint**

Run `git diff --check` and `git status --short`; keep changes local unless the newest user message authorizes publishing.

### Task 2: Additive OpenAPI contract and daily FREE claim engine

**Files:**
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `nayon_api/src/main/java/com/nayon/api/limitedbenefit/LimitedBenefit*.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/LimitedBenefit*.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/interfaces/ApiExceptionHandler.java`
- Modify: PostgreSQL test cleanup lists to include V11 child tables first
- Create: `nayon_api/src/test/java/com/nayon/api/interfaces/LimitedBenefitContractTest.java`
- Create: `nayon_api/src/test/java/com/nayon/api/integration/LimitedBenefitPostgresTest.java`

**Interfaces:**
- Consumes: V11 definitions and `EconomyRepository.creditCurrency/creditItem`.
- Produces: `GET /api/v1/events/limited-benefits/current` and `POST /api/v1/events/limited-benefits/offers/{offerCode}/claims` for FREE offers.

- [x] **Step 1: Write OpenAPI/contract RED tests**

Assert the source contains both paths and schemas for campaign, offer, reward, proof request, claim response, states, `204`, `409`, `422`, and `503`.

- [x] **Step 2: Write PostgreSQL RED tests**

Cover KST cycle/reset timestamps, row-0 availability, predecessor locking, atomic multi-reward credit, different-key duplicate conflict, same-key replay, concurrent claim exact-once, and account isolation.

- [x] **Step 3: Run RED**

Run:

```bash
./gradlew cleanTest test --tests '*LimitedBenefitContractTest' --tests '*LimitedBenefitPostgresTest' --no-daemon
```

Expected: compile/test failure because the contract and domain do not exist.

- [x] **Step 4: Implement the minimum domain/repository/service/controller**

Use `Clock` plus `ZoneId.of("Asia/Seoul")`, one `limited-benefit-account:<accountId>` advisory lock, DB constraints, and durable response JSON. FREE claims accept no proof and credit all rows before writing the successful claim response.

- [x] **Step 5: Run GREEN**

Run the same focused Gradle command with `E2E_DB=1` through `scripts/verify-postgres-integration.sh`; expect zero failures.

- [x] **Step 6: Publishing checkpoint**

Run `git diff --check` and keep the API changes local.

### Task 3: Google Play receipt fulfillment

**Files:**
- Modify: `nayon_api/src/main/java/com/nayon/api/store/JdbcStorePurchaseRepository.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/store/StorePurchaseReceipt.java`
- Modify: `nayon_api/src/main/java/com/nayon/api/interfaces/StorePurchaseResponse.java`
- Modify: `nayon_api/src/test/java/com/nayon/api/integration/StorePurchasePostgresTest.java`
- Modify: `nayon_api/src/test/java/com/nayon/api/integration/LimitedBenefitPostgresTest.java`

**Interfaces:**
- Consumes: `store_product_versions.fulfillment_type` values `DIRECT_CURRENCY` and `LIMITED_BENEFIT`, existing verified receipt UUIDs, and the claim endpoint proof body.
- Produces: paid receipts that are verified exactly once without direct reward, then consumed by exactly one matching daily offer claim.

- [ ] **Step 1: Write RED tests**

Test that `DIRECT_CURRENCY` is unchanged; `LIMITED_BENEFIT` receipt verification writes `GRANTED` without a standalone ledger credit; wrong account/product/cycle and receipt reuse return `LIMITED_BENEFIT_PROOF_INVALID`; the matching claim grants one bundle.

- [ ] **Step 2: Run RED**

Run the two PostgreSQL test classes; expect the event-fulfillment behavior to fail.

- [ ] **Step 3: Implement receipt discrimination and claim proof checks**

Do not infer an offer by title or price. Join the receipt product UUID to the exact offer mapping and persist the receipt reference on the claim.

- [ ] **Step 4: Run GREEN**

Run `scripts/verify-postgres-integration.sh`; expect all store and limited-benefit integration tests to pass.

- [ ] **Step 5: Publishing checkpoint**

Run `git diff --check`; keep changes local.

### Task 4: AdMob SSV session and callback verification

**Files:**
- Modify: `nayon_api/build.gradle`
- Modify: `nayon_api/src/main/resources/application.yml`
- Modify: `nayon_api/src/main/java/com/nayon/api/config/SecurityConfig.java`
- Create: `nayon_api/src/main/java/com/nayon/api/limitedbenefit/admob/*.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/AdMobRewardCallbackController.java`
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `nayon_api/src/test/java/com/nayon/api/limitedbenefit/admob/AdMobSsvVerifierTest.java`
- Modify: `nayon_api/src/test/java/com/nayon/api/integration/LimitedBenefitPostgresTest.java`

**Interfaces:**
- Consumes: the original ordered callback URL bytes and Google verifier key ID; account-created ad session UUID in `custom_data`.
- Produces: `POST .../{offerCode}/ad-sessions`, public `GET /api/v1/public/admob/rewarded-callback`, and verified session proof consumable by claims.

- [ ] **Step 1: Write verifier RED tests**

Use a test EC key pair to sign an official-format ordered query. Assert valid acceptance plus rejection of altered query, wrong key, wrong ad unit, expired session, duplicate transaction replay, and cross-account session.

- [ ] **Step 2: Run RED**

Run `./gradlew test --tests '*AdMobSsvVerifierTest'`; expect missing verifier/session failures.

- [ ] **Step 3: Implement verification**

Use Java `Signature` with `SHA256withECDSA`, cache Google's public keys with bounded expiry, preserve the substring before `&signature=`, validate exact configured ad metadata, and never grant from the public callback.

- [ ] **Step 4: Implement session/callback persistence and claim consumption**

Session creation requires the current unlocked ad offer; callback marks it verified transactionally; authenticated claim consumes it once.

- [ ] **Step 5: Run GREEN**

Run focused verifier tests and PostgreSQL integration tests with zero failures.

- [ ] **Step 6: Publishing checkpoint**

Run secret scan and `git diff --check`; no real ad unit or credential belongs in the repository.

### Task 5: Unity server-driven popup

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Create: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/LimitedBenefit/LimitedBenefitSynchronizer.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Modify: matching Editor tests under `Assets/Tests/Editor`

**Interfaces:**
- Consumes: campaign/offer DTOs, claim responses, receipt UUID, and ad session DTO.
- Produces: existing popup rendered entirely from server state, account-scoped cache, safe retry, Play purchase correlation, and SSV ad correlation.

- [ ] **Step 1: Write Unity RED tests**

Cover JSON routes/models, account cache isolation, server `resetsAt`, disabled provider state, FREE claim request, paid receipt correlation, ad session correlation, and absence of local claim keys/grant invocation.

- [ ] **Step 2: Run RED**

Run Unity EditMode tests on the exact Windows feature SHA; expect missing DTO/synchronizer failures.

- [ ] **Step 3: Implement API client and synchronizer**

Use per-account persisted idempotency only for in-flight claim retries; clear it after authoritative success. Apply response snapshots and server equipment UUIDs through existing bridge helpers.

- [ ] **Step 4: Replace the popup's economic source**

Keep prefab/layout code. Remove hard-coded offers, device countdown, `NyaonLimitedBenefit*` keys, and local grants. Bind row buttons by server fulfillment/state.

- [ ] **Step 5: Wire Play and AdMob proof flows**

Reuse `NayonStoreRuntime` for paid products. Configure `ServerSideVerificationOptions` before showing rewarded ads; after client completion, poll/refetch until the server session is verified, then claim.

- [ ] **Step 6: Run GREEN and synchronize Windows**

Run all Unity EditMode tests, confirm zero compile errors, copy only reviewed source through Git to `C:\work\Nayon_Hunters-develop`, verify identical SHA/files, and reopen Unity 6000.5.6f1.

- [ ] **Step 7: Publishing checkpoint**

Run `git diff --check`; keep changes local.

### Task 6: Full verification and release evidence

**Files:**
- Modify only test cleanup or contract documentation if verification exposes a defect.

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: release-ready local evidence without publication.

- [ ] **Step 1: Run full backend checks**

```bash
bash scripts/verify-v11.sh
./gradlew cleanTest test --console=plain --no-daemon
NAYON_CLOUD_DIR=/home/ubuntu/work/.worktrees/nayon-cloud-korion-wallet-link ./scripts/verify-postgres-integration.sh
bash scripts/verify-deploy-contract.sh
```

- [ ] **Step 2: Run full Unity checks**

Run all EditMode tests on Windows Unity 6000.5.6f1 and verify WSL/Windows source byte equality.

- [ ] **Step 3: Review trust-boundary cases**

Confirm device clock/PlayerPrefs cannot grant, no client-success fallback exists, receipt/ad proof is exact and account-owned, every bundle is atomic, and provider absence disables instead of granting.

- [ ] **Step 4: Report migration/contract evidence**

Report V11 up/down paths and timings, lock risk, rollback trigger, OpenAPI paths/components/DTO/tests, additive compatibility, exact commands, and local-only publication state.
