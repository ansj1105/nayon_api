# KORION Wallet Push Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Link a Cognito-authenticated NYAON account to a KORION TRON wallet only after the KORION app receives a push, signs a one-time challenge, and KORION verifies ownership; then grant the dual-link reward exactly once from the NYAON ledger.

**Architecture:** KORION owns address resolution, FCM delivery, challenge lifecycle, and TRON signature verification. NYAON calls authenticated internal KORION endpoints, stores only verified link state, and owns the account-link reward transaction. Unity and the KORION frontend are state-machine clients and never become authorities for linking or rewards.

**Tech Stack:** PostgreSQL/Flyway, Vert.x Java 17 Foxya API, React/TypeScript/Capacitor KORION wallet, Spring Boot 3.5/Java 21 NYAON API, Unity 6000/C#.

## Global Constraints

- Only TRON/KORION addresses are supported in this stage.
- Requests expire after 10 minutes and allow at most 5 signature failures.
- Never send or persist a mnemonic/private key outside KORION secure local storage.
- Real internal keys live only in runtime Secrets; source and docs use placeholders.
- Preserve current menus, account-tab layout, popup prefabs, and labels except replacing the fake code flow with push approval state.
- `DIAMOND=300`, `SILVER_KEY=1`, and `GOLD_KEY=1` are granted together exactly once by the NYAON ledger.
- All API source-of-truth changes update OpenAPI/contracts before implementation.
- Database changes include forward and rollback migrations and realistic integration verification.

---

### Task 1: Freeze Cross-Service Contracts

**Files:**
- Create: `coin_system_flyway/docs/nayon-wallet-link-contract.md`
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `fox_coin/docs/NAYON_WALLET_LINK.md`

**Interfaces:**
- Produces: exact public/internal paths, request fields, status enum, challenge text, headers, and error codes consumed by all later tasks.

- [ ] **Step 1: Add contract tests that search the OpenAPI and Foxya route contract for every required path and enum.**
- [ ] **Step 2: Run the contract tests and confirm they fail because wallet-link paths are absent.**
- [ ] **Step 3: Add the paths and schemas from the approved design, including `202`, `400`, `401`, `404`, `409`, and `429` responses.**
- [ ] **Step 4: Run the contract tests and confirm they pass.**
- [ ] **Step 5: Commit with `docs(wallet): define push link contract`.**

### Task 2: Add KORION Request Persistence

**Files:**
- Create: `coin_system_flyway/src/main/resources/db/migration/V179__20260817_Create_nayon_wallet_link_requests.sql`
- Create: `coin_system_flyway/src/main/resources/db/rollback/V179__20260817_Create_nayon_wallet_link_requests_rollback.sql`
- Modify: `coin_system_flyway/src/test/java/com/coinsystem/flyway/DatabaseSchemaTest.java`

**Interfaces:**
- Produces: `nayon_wallet_link_requests` with status/expiry/attempt constraints and active-address uniqueness.

- [ ] **Step 1: Add schema assertions for columns, foreign key, status check, attempt range, and the partial unique active-address index.**
- [ ] **Step 2: Run `./gradlew test` and confirm the V179 assertions fail.**
- [ ] **Step 3: Add V179 forward and rollback SQL without data backfill or existing-table rewrite.**
- [ ] **Step 4: Run migration up/down tests and record duration and lock-risk result.**
- [ ] **Step 5: Commit with `feat(db): add nayon wallet link requests`.**

### Task 3: Implement KORION Challenge and Internal APIs

**Files:**
- Create: `fox_coin/src/main/java/com/foxya/coin/nayonlink/NayonWalletLinkRequest.java`
- Create: `fox_coin/src/main/java/com/foxya/coin/nayonlink/NayonWalletLinkRepository.java`
- Create: `fox_coin/src/main/java/com/foxya/coin/nayonlink/NayonWalletLinkService.java`
- Create: `fox_coin/src/main/java/com/foxya/coin/nayonlink/InternalNayonWalletLinkHandler.java`
- Create: `fox_coin/src/main/java/com/foxya/coin/nayonlink/NayonWalletLinkHandler.java`
- Modify: `fox_coin/src/main/java/com/foxya/coin/verticle/ApiVerticle.java`
- Create: `fox_coin/src/test/java/com/foxya/coin/nayonlink/NayonWalletLinkServiceTest.java`
- Create: `fox_coin/src/test/java/com/foxya/coin/nayonlink/NayonWalletLinkHandlerTest.java`

**Interfaces:**
- Consumes: V179 table, `DeviceRepository`, `NotificationService`, `RecoverySignatureVerifier.verifyTronSignature`.
- Produces: internal create/status and authenticated user get/approve/reject endpoints.

- [ ] **Step 1: Write failing service tests for exact-one-user address resolution, idempotent creation, expiry, five-attempt limit, owner isolation, valid approval, invalid signature, and one-way status transitions.**
- [ ] **Step 2: Run the focused Maven/Gradle test command with workspace JDK and confirm expected failures.**
- [ ] **Step 3: Implement repository operations with QueryBuilder for CRUD and one explicit address-owner query where joins/unions require it.**
- [ ] **Step 4: Implement cryptographic nonce generation, exact challenge formatting, masked logging, and conditional status updates.**
- [ ] **Step 5: Write failing handler tests for internal key, validation, response status, and authenticated user ownership.**
- [ ] **Step 6: Implement thin handlers and mount only the named internal and wallet subroutes.**
- [ ] **Step 7: Send FCM through `NotificationService` with `type=NAYON_WALLET_LINK` and `requestId`; never log token or challenge.**
- [ ] **Step 8: Run focused tests, full backend tests, and secret scan.**
- [ ] **Step 9: Commit with `feat(wallet): verify nayon link approvals`.**

### Task 4: Add KORION Push Approval Popup

**Files:**
- Modify: `fox_coin_frontend/src/shared/lib/pushNotifications.ts`
- Modify: `fox_coin_frontend/src/api/client.ts`
- Create: `fox_coin_frontend/src/features/nayon-wallet-link/model/nayonWalletLink.ts`
- Create: `fox_coin_frontend/src/features/nayon-wallet-link/model/nayonWalletLink.test.ts`
- Create: `fox_coin_frontend/src/features/nayon-wallet-link/ui/NayonWalletLinkApprovalDialog.tsx`
- Create: `fox_coin_frontend/src/features/nayon-wallet-link/ui/NayonWalletLinkApprovalDialog.css`
- Create: `fox_coin_frontend/src/features/nayon-wallet-link/index.ts`
- Modify: `fox_coin_frontend/src/App.tsx`

**Interfaces:**
- Consumes: push data `type/requestId`, KORION user request endpoints, `getMnemonic`, `deriveWalletFromMnemonic`, `signWalletMessage`.
- Produces: a hidden global approval dialog opened only by the matching push action.

- [ ] **Step 1: Write failing model tests for strict payload parsing, TRON address match, missing mnemonic, expired request, approve payload, and reject action.**
- [ ] **Step 2: Run focused Vitest and confirm failures.**
- [ ] **Step 3: Extend the push action callback with sanitized data while keeping existing refresh reasons compatible.**
- [ ] **Step 4: Implement the API client and model that re-derives TRON address and signs only after exact address equality.**
- [ ] **Step 5: Implement the popup in its feature-owned TSX/CSS and mount it in `PushNotificationBootstrap` without adding navigation/menu entries.**
- [ ] **Step 6: Run focused tests, `npm run build`, and a Playwright DOM pass for open/approve/reject/error states.**
- [ ] **Step 7: Commit with `feat(wallet): approve nayon links from push`.**

### Task 5: Add NYAON Link and Reward Schema

**Files:**
- Create: `nayon_cloud/db/migration/V7__create_korion_wallet_links.sql`
- Create: `nayon_cloud/db/rollback/U7__drop_korion_wallet_links.sql`
- Create: `nayon_cloud/scripts/verify-v7.sh`
- Modify: `nayon_cloud/docs/nayon-db-erd.md`

**Interfaces:**
- Produces: request, verified-link, and account-link-reward tables plus economy-ledger reason/reference constraints compatible with the existing schema.

- [ ] **Step 1: Write V7 verifier assertions for account ownership, one active request, address uniqueness, verified request uniqueness, reward checks, and cross-account FK rejection.**
- [ ] **Step 2: Run the verifier and confirm it fails before V7 exists.**
- [ ] **Step 3: Add forward and reverse migrations using only additive DDL.**
- [ ] **Step 4: Run V1→V7, negative constraint checks, and U7 rollback in an isolated PostgreSQL database.**
- [ ] **Step 5: Record execution time and confirm no existing large table rewrite.**
- [ ] **Step 6: Commit with `feat(db): add korion wallet links`.**

### Task 6: Implement NYAON Link Orchestration

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/korion/KorionWalletLinkStatus.java`
- Create: `nayon_api/src/main/java/com/nayon/api/korion/KorionWalletLinkRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/korion/JdbcKorionWalletLinkRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/korion/KorionWalletGateway.java`
- Create: `nayon_api/src/main/java/com/nayon/api/korion/HttpKorionWalletGateway.java`
- Create: `nayon_api/src/main/java/com/nayon/api/korion/KorionWalletLinkService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/KorionWalletLinkController.java`
- Create: request/response DTOs under `nayon_api/src/main/java/com/nayon/api/interfaces/`
- Modify: `nayon_api/src/main/resources/application.yml`
- Create: unit, contract, and PostgreSQL tests under matching `src/test/java/com/nayon/api/` packages.

**Interfaces:**
- Consumes: KORION internal endpoints using `X-Internal-Api-Key` from runtime configuration.
- Produces: authenticated NYAON create/status/unlink endpoints and authoritative link state.

- [ ] **Step 1: Add failing OpenAPI/MockMvc tests for all status/error shapes and account scoping.**
- [ ] **Step 2: Add failing service tests for create idempotency, gateway failure, approve reconciliation, expiry, unlink race, and address conflict.**
- [ ] **Step 3: Add failing PostgreSQL tests for locking and unique constraints.**
- [ ] **Step 4: Implement thin controller, transactional service, JDBC repository, and timeout-bounded RestClient gateway.**
- [ ] **Step 5: Load URL/key from `KORION_WALLET_LINK_BASE_URL` and `KORION_WALLET_LINK_INTERNAL_API_KEY`; fail link calls clearly when absent.**
- [ ] **Step 6: Run focused tests, full Gradle tests, PostgreSQL integration, and deploy-contract checks.**
- [ ] **Step 7: Commit with `feat(api): link verified korion wallets`.**

### Task 7: Implement Server-Authoritative Dual-Link Reward

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/accountlink/AccountLinkRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/accountlink/JdbcAccountLinkRewardRepository.java`
- Create: `nayon_api/src/main/java/com/nayon/api/accountlink/AccountLinkRewardService.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/AccountLinkRewardController.java`
- Create: reward DTOs and unit/contract/PostgreSQL tests.

**Interfaces:**
- Consumes: authenticated Google identity, verified `player_korion_wallet_links`, economy wallet/ledger tables.
- Produces: exact-once reward state and authoritative `EconomyResponse`.

- [ ] **Step 1: Write failing tests for missing Google identity, missing wallet link, first claim, same-key replay, different-key replay, concurrent claim, and account isolation.**
- [ ] **Step 2: Run focused tests and confirm expected failures.**
- [ ] **Step 3: Implement one account lock order shared with economy writers and update all three assets plus ledger entries in one transaction.**
- [ ] **Step 4: Return current state without extra credit for every replay after success.**
- [ ] **Step 5: Run focused, contract, and PostgreSQL concurrency tests.**
- [ ] **Step 6: Commit with `feat(api): grant dual-link reward once`.**

### Task 8: Replace Unity Fake Wallet Link Flow

**Files:**
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Cloud/NayonCloudRuntime.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs`
- Modify: `Nayon_Hunters/NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Create: focused EditMode tests under `Nayon_Hunters/NYAON_HUNTERS/Assets/Tests/Editor/`.

**Interfaces:**
- Consumes: NYAON link/status/unlink/reward endpoints.
- Produces: current prefab-driven address request, two-second polling, server link display, and server economy reward application.

- [ ] **Step 1: Rebase the Unity feature branch on the latest remote `develop`.**
- [ ] **Step 2: Write failing API and pure state tests for create/status/poll termination/unlink/reward and the rule that local state cannot mark linked before `APPROVED`.**
- [ ] **Step 3: Run Windows Unity filtered EditMode tests and confirm expected failures.**
- [ ] **Step 4: Add client/runtime/bridge methods and nullable-safe models aligned with OpenAPI.**
- [ ] **Step 5: Replace fake code confirmation with address submission, server expiry display, cancellable polling, and API-backed unlink.**
- [ ] **Step 6: Replace local reward grants with server claim and authoritative economy snapshot application.**
- [ ] **Step 7: Incrementally sync changed Scripts/Tests to the Windows Unity 6000 project and run all EditMode tests.**
- [ ] **Step 8: Verify no UI prefab, navigation, or unrelated UX asset changed.**
- [ ] **Step 9: Commit with `feat(unity): link korion wallet by push`.**

### Task 9: Stage, Deploy, and Observe

**Files:**
- Modify only existing deployment manifests/workflows that require the two new runtime variables.

**Interfaces:**
- Produces: deployed DB/API/frontend/Unity-compatible contracts with health and observability evidence.

- [ ] **Step 1: Request static review for each repo diff and fix all Critical/Important findings.**
- [ ] **Step 2: Run secret scans; confirm no real key, JWT, FCM token, signature, nonce, mnemonic, or private key is present.**
- [ ] **Step 3: Push each verified stage to its normal branch without force-push.**
- [ ] **Step 4: Apply KORION V179, deploy KORION API, deploy KORION frontend, apply NYAON V7, and deploy NYAON API in that order.**
- [ ] **Step 5: Add the same generated internal key to both runtime secret stores without printing it and restart only affected services.**
- [ ] **Step 6: Smoke-test internal unauthorized/authorized behavior, public authentication, health, and one controlled non-production request lifecycle.**
- [ ] **Step 7: Check request/error/FCM/signature metrics and logs for 15 minutes; confirm no alert regression.**
- [ ] **Step 8: Keep current and immediate previous deploy artifacts, and document rollback triggers without dropping audit tables.**

