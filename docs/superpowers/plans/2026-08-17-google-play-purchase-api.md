# Google Play Purchase API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task by task.

**Goal:** Add an API-first Google Play one-time-product flow that exposes a mutable catalog and grants server-authoritative DIAMOND rewards exactly once after Google verification.

**Architecture:** PostgreSQL owns offer, product, reward-version, receipt, and state-transition history. Spring exposes an authenticated catalog and verification endpoint, isolates Google Play behind a gateway, and performs external verification outside the database transaction. A transactionally locked finalization step snapshots the verified product version, credits the existing economy ledger once, and records the authoritative wallet total. Unity IAP confirms and consumes only after the server returns `GRANTED`.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring JDBC, PostgreSQL/Flyway, Google Auth Library, OpenAPI, JUnit 5.

## Global constraints

- Do not trust client price, reward amount, order ID, account ID, or purchase state.
- Do not grant from Unity/local state; only Google verification may authorize a grant.
- Preserve immutable historical reward versions and receipt snapshots.
- Never commit service-account JSON or any real key/token.
- Do not commit, push, or deploy until a later user message explicitly requests it.

### Task 1: V9 store schema and rollback

**Files:**
- Create: `nayon_cloud/db/migration/V9__create_store_purchase_tables.sql`
- Create: `nayon_cloud/db/rollback/U9__drop_store_purchase_tables.sql`
- Create: `nayon_cloud/scripts/verify-v9.sh`

1. Add a verification script that asserts tables, constraints, active-version uniqueness, receipt token uniqueness, and six stable offers; run it against V1-V8 and observe failure.
2. Add `store_offers`, `store_products`, `store_product_versions`, and `store_purchase_receipts` with composite ownership/correlation constraints and timestamp checks.
3. Seed only the six stable offer codes; leave Google product IDs and reward versions for deployment-time configuration.
4. Add rollback in reverse dependency order.
5. Apply V1-V9 to an empty PostgreSQL database, run `verify-v9.sh`, exercise expected constraint failures, and statically validate rollback order.

### Task 2: OpenAPI catalog and purchase contract

**Files:**
- Modify: `nayon_api/src/main/resources/openapi/nayon-api-v1.yaml`
- Create/modify: `nayon_api/src/test/java/com/nayon/api/interfaces/StoreContractTest.java`

1. Add failing contract assertions for `GET /api/v1/store/catalog` and `POST /api/v1/store/purchases/google-play/verify`.
2. Define request/response DTO schemas, stable state enums, required `Idempotency-Key`, and 400/401/404/409/422/503 errors.
3. Verify the contract tests pass without changing unrelated paths.

### Task 3: Catalog repository, account hash, and endpoint

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/store/*` catalog domain/repository/service files
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/StoreCatalogController.java`
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/StoreCatalogResponse.java`
- Modify: `nayon_api/src/main/resources/application.yml`
- Create/modify: catalog unit and PostgreSQL integration tests

1. Write tests for active product/version filtering, offer ordering, no configured catalog, and deterministic account-scoped HMAC output.
2. Implement the minimum JDBC query and HMAC helper; reject use when the runtime hash key is not configured.
3. Expose only stable offer code, Google product ID, reward summary/version, and obfuscated account ID.
4. Run focused unit and PostgreSQL tests.

### Task 4: Google verification gateway

**Files:**
- Modify: `nayon_api/build.gradle`
- Create: `nayon_api/src/main/java/com/nayon/api/store/google/*`
- Modify: `nayon_api/src/main/resources/application.yml`
- Create: gateway tests with a local HTTP server

1. Write failing tests for PURCHASED, pending/cancelled, product mismatch, account mismatch, malformed response, 404, rate limit, and transport failure.
2. Load credentials only from a configured runtime file and obtain the Android Publisher OAuth scope using Google Auth Library.
3. Call ProductPurchaseV2 and map remote responses to typed internal results; never log purchase tokens or credentials.
4. Do not expose a consume operation from the server gateway; Unity IAP owns Google consumption after `GRANTED`.

### Task 5: Receipt idempotency and exactly-once grant

**Files:**
- Create: `nayon_api/src/main/java/com/nayon/api/store/*` receipt repository/service/domain files
- Create: `nayon_api/src/main/java/com/nayon/api/interfaces/GooglePlayPurchaseController.java`
- Create: request/response DTOs and exception mappings
- Create/modify: unit and PostgreSQL integration tests

1. Write failing tests for same-key replay, key/hash conflict, token reuse by another account, unknown/inactive product, non-PURCHASED verification, account-hash mismatch, and concurrent verify calls.
2. Persist or reload the request identity before the external call; keep ambiguous gateway outcomes pending under the same request ID.
3. Re-verify Google, then finalize under account/request/token advisory locks and row locks.
4. Snapshot the immutable product version and Google order/time, call existing `creditCurrency` with reason `STORE_PURCHASE`, and persist the authoritative DIAMOND total in one transaction.
5. Prove one receipt and one ledger credit under concurrency and replay.

### Task 6: Post-grant handoff and operations

**Files:**
- Modify: `nayon_api/README.md` and deployment example/config files that already own environment variables
- Create/modify: contract and configuration tests

1. Write tests proving successful verification remains `GRANTED` and never calls a server-side consume operation.
2. Document that Unity IAP calls `ConfirmPurchase` only after `GRANTED` and retries with the same request ID after interruption.
3. Document package name, credentials-file mount, account-hash key, product-registration mapping, and safe deploy order.
4. Add verification counters/log fields without logging purchase tokens or credentials.

### Task 7: Full verification and handoff

1. Run `./gradlew test` and `./gradlew build` in `nayon_api`.
2. Run all PostgreSQL integration tests from `nayon_api/scripts/verify-postgres-integration.sh`.
3. Apply V1-V9 on a clean DB, run V9 verification, and validate the rollback script in an isolated disposable DB.
4. Run `git diff --check` and targeted secret-pattern scans in both repositories.
5. Review the final diff against this plan and report exact remaining Google Play Console/AWS secret actions. Do not publish without a new explicit instruction.
