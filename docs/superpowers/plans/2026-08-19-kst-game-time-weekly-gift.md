# KST Game Time and Weekly Gift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make server-authoritative KST time the common basis for NYAON business timers and implement weekly-gift check-in and claim eligibility without configuring a reward.

**Architecture:** A single API `ServerClock` supplies the current instant and `KstGameTimeCalculator` owns all KST conversion, periods, expiry, and remaining-time calculations. Weekly gift keeps dedicated week/login-day tables and APIs; Unity only renders server state and uses a monotonic server-time anchor for countdown display. Existing feature tables remain separate and only their time calculation is shared.

**Tech Stack:** Java 21, Spring Boot 3.5, JDBC, PostgreSQL/Flyway SQL, OpenAPI 3, Unity 6000/C#, NUnit.

## Global Constraints

- Time zone is fixed to `Asia/Seoul`; client time zone and device clock never authorize state changes.
- Daily reset is KST 00:00 and weekly reset is KST Monday 00:00.
- All NAYON-owned expiry, TTL, recharge, event, and countdown policy uses the shared server/KST layer.
- DB `timestamptz` stores instants; API business-time fields include `+09:00`.
- Weekly gift requires three distinct KST login dates and must not mark claimed while reward configuration is absent.
- Preserve current Unity navigation, popup hierarchy, labels, and visibility.
- Work only on `develop-sj`; do not commit, push, deploy, run Unity, or build an APK in this execution.

---

### Task 1: Weekly-gift schema

**Files:**
- Create in `nayon_cloud`: `db/migration/V14__create_weekly_gift.sql`
- Create in `nayon_cloud`: `db/rollback/U14__drop_weekly_gift.sql`
- Create in `nayon_cloud`: `scripts/verify-v14.sh`

**Interfaces:**
- Produces: empty-by-default `weekly_gift_reward_versions` configuration; production has no seeded reward until product policy is decided.
- Produces: `player_weekly_gift_weeks(account_id, week_start, claimed_at, claim_request_id, reward_version_id, claim_response, created_at, updated_at)`.
- Produces: `player_weekly_gift_login_days(account_id, week_start, login_date, first_seen_at)` with a composite FK and `login_date` constrained to that seven-day week.

- [ ] **Step 1: Write forward, rollback, and verification files together**

```sql
create table player_weekly_gift_weeks (
    account_id uuid not null references player_accounts(id) on delete cascade,
    week_start date not null,
    claimed_at timestamptz,
    claim_request_id uuid unique,
    reward_version_id uuid,
    claim_response jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (account_id, week_start),
    check (extract(isodow from week_start) = 1),
    check ((claimed_at is null and claim_request_id is null and claim_response is null)
        or (claimed_at is not null and claim_request_id is not null and claim_response is not null))
);
```

- [ ] **Step 2: Run `bash scripts/verify-v14.sh` and require forward plus rollback PASS**
- [ ] **Step 3: Record forward/rollback timing and lock risk in the implementation report**

### Task 2: Common server/KST time layer

**Files:**
- Create: `src/main/java/com/nayon/api/time/ServerClock.java`
- Create: `src/main/java/com/nayon/api/time/KstGameTimeCalculator.java`
- Create: `src/main/java/com/nayon/api/time/RewardPeriod.java`
- Test: `src/test/java/com/nayon/api/time/KstGameTimeCalculatorTest.java`

**Interfaces:**
- Produces: `Instant ServerClock.now()`.
- Produces: `ZonedDateTime KstGameTimeCalculator.now()`, `RewardPeriod dailyPeriod()`, `RewardPeriod weeklyPeriod()`, `ZonedDateTime expiresAt(Instant, Duration)`, `Duration remainingUntil(Instant)`, and `boolean isExpired(Instant)`.

- [ ] **Step 1: Write failing boundary tests for KST midnight, Monday reset, expiry, and non-negative remaining duration**
- [ ] **Step 2: Run `./gradlew test --tests com.nayon.api.time.KstGameTimeCalculatorTest` and confirm missing types fail**
- [ ] **Step 3: Implement the three focused classes using only `java.time` and fixed `ZoneId.of("Asia/Seoul")`**
- [ ] **Step 4: Re-run the targeted test and require PASS**

### Task 3: Weekly-gift OpenAPI contract

**Files:**
- Modify: `src/main/resources/openapi/nayon-api-v1.yaml`
- Create: `src/test/java/com/nayon/api/weeklygift/WeeklyGiftOpenApiContractTest.java`

**Interfaces:**
- Produces: `GET /me/weekly-gift`, `POST /me/weekly-gift/check-in`, and `POST /me/weekly-gift/claim`.
- Produces: `WeeklyGiftResponse` with `serverTime`, `zoneId`, `weekStart`, `nextResetAt`, `loginDays`, `requiredLoginDays`, `claimable`, `claimEnabled`, `claimed`, nullable `reward`, and nullable `economy`.

- [ ] **Step 1: Add a failing contract test asserting all three paths, required idempotency header on claim, response fields, and `+09:00` examples**
- [ ] **Step 2: Run the targeted test and confirm it fails because paths/components are absent**
- [ ] **Step 3: Add the exact paths, schemas, examples, and 401/409/503 responses to OpenAPI**
- [ ] **Step 4: Re-run the contract test and require PASS**

### Task 4: Weekly-gift API and persistence

**Files:**
- Create: `src/main/java/com/nayon/api/weeklygift/WeeklyGiftRepository.java`
- Create: `src/main/java/com/nayon/api/weeklygift/JdbcWeeklyGiftRepository.java`
- Create: `src/main/java/com/nayon/api/weeklygift/WeeklyGiftService.java`
- Create: `src/main/java/com/nayon/api/weeklygift/WeeklyGiftState.java`
- Create: `src/main/java/com/nayon/api/weeklygift/WeeklyGiftException.java`
- Create: `src/main/java/com/nayon/api/interfaces/WeeklyGiftController.java`
- Create: `src/main/java/com/nayon/api/interfaces/WeeklyGiftResponse.java`
- Test: `src/test/java/com/nayon/api/weeklygift/WeeklyGiftServiceTest.java`
- Test: `src/test/java/com/nayon/api/interfaces/WeeklyGiftContractTest.java`
- Test: `src/test/java/com/nayon/api/integration/WeeklyGiftPostgresTest.java`

**Interfaces:**
- `WeeklyGiftState get(UUID accountId)` is read-only.
- `WeeklyGiftState checkIn(UUID accountId)` inserts one date idempotently.
- `WeeklyGiftState claim(UUID accountId, UUID requestId)` rejects fewer than three dates and rejects missing reward configuration without mutation.

- [ ] **Step 1: Write failing service tests for read-only get, same-day idempotency, third-day eligibility, Monday reset, under-three-day claim, and unconfigured reward claim**
- [ ] **Step 2: Run the service test and confirm missing implementation failure**
- [ ] **Step 3: Implement minimal domain types and service using `KstGameTimeCalculator`**
- [ ] **Step 4: Add controller contract tests for auth, response serialization, and normalized 409 codes; confirm RED**
- [ ] **Step 5: Implement controller/response/error mapping and require contract PASS**
- [ ] **Step 6: Add PostgreSQL concurrency/idempotency tests and implement JDBC upsert/count/lock queries until PASS**
- [ ] **Step 7: Insert a test-only `DIAMOND 1` reward version, check in on three distinct KST dates, claim through the service, and assert one economy-ledger grant plus idempotent replay**

### Task 5: Existing API timer migration

**Files:**
- Modify: subscription, limited-benefit, AdMob reward, battle, offline-battle, and KORION wallet-link services under `src/main/java/com/nayon/api`.
- Modify tests beside each affected service.

**Interfaces:**
- All services consume `ServerClock` or `KstGameTimeCalculator`; business code has no direct `Instant.now()`, `LocalDate.now()`, `ZoneOffset.UTC`, or local `Asia/Seoul` constants.

- [ ] **Step 1: Add tests proving subscription daily reward changes at KST midnight and existing TTL lengths remain unchanged**
- [ ] **Step 2: Confirm the new tests fail against current UTC/direct-clock code**
- [ ] **Step 3: Replace direct clocks in the affected services with the common layer without changing external-provider timestamps**
- [ ] **Step 4: Run all affected package tests and scan production Java for forbidden direct business-time calls**

### Task 6: Unity common time and weekly-gift client

**Files:**
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Time/NayonServerTimeService.cs`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Time/RewardPeriodStateCache.cs`
- Create: `NYAON_HUNTERS/Assets/Scripts/Cloud/Time/RewardApiErrorMapper.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/INayonCloudApi.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiClient.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/Api/NayonApiModels.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Cloud/NayonCloudRuntime.cs`
- Modify: `NYAON_HUNTERS/Assets/Scripts/Manager/Contents/NayonGameCloudBridge.cs`
- Test: `NYAON_HUNTERS/Assets/Tests/Editor/NayonServerTimeServiceTests.cs`
- Test: `NYAON_HUNTERS/Assets/Tests/Editor/NayonApiClientTests.cs`

**Interfaces:**
- API methods: `GetWeeklyGiftAsync`, `CheckInWeeklyGiftAsync`, `ClaimWeeklyGiftAsync`.
- Server-time service anchors an API `DateTimeOffset` to `Time.realtimeSinceStartupAsDouble` and only estimates display time.

- [ ] **Step 1: Write failing edit-mode tests for monotonic server-time anchoring and all weekly-gift HTTP requests**
- [ ] **Step 2: Run Unity edit-mode tests and confirm RED**
- [ ] **Step 3: Implement the minimal models, client methods, cache, error mapping, and runtime bridge**
- [ ] **Step 4: Re-run targeted edit-mode tests and require PASS**

### Task 7: Weekly-gift UI authority switch

**Files:**
- Modify: `NYAON_HUNTERS/Assets/Scripts/UI/Popup/LobbyScene/UI_BattlePopup.cs`
- Test: add focused policy tests under `NYAON_HUNTERS/Assets/Tests/Editor` if logic is extracted from the popup.

**Interfaces:**
- The existing popup and red-dot layout consume cached `WeeklyGiftResponse`.
- Lobby authenticated entry calls check-in once per runtime entry.
- Claim is disabled when offline, not eligible, already claimed, or `claimEnabled=false`.

- [ ] **Step 1: Write a failing pure policy test for button/red-dot state**
- [ ] **Step 2: Confirm RED, then implement the smallest policy and popup binding**
- [ ] **Step 3: Remove weekly-gift `PlayerPrefs`, local RNG, and local `ExchangeMaterial` from the authority path**
- [ ] **Step 4: Run the focused policy/API tests and confirm existing UI structure was not changed**

### Task 8: Unity local business-timer migration

**Files:**
- Modify only the detected business-time owners: `TimeManager.cs`, `GameManager.cs`, `UI_BattlePopup.cs`, `UI_ShopPopup.cs`, and `UI_Season01EmberFangEvent.cs`.
- Keep auth token, external provider, backup filename, battle-frame, animation, and combat cooldown clocks unchanged.

**Interfaces:**
- Countdown displays use `NayonServerTimeService`.
- State-changing daily/weekly/expiry checks use server API state; offline state is display-only.

- [ ] **Step 1: Add failing policy tests for KST daily keys, mail expiry display, stamina/idle elapsed display, and device-clock independence**
- [ ] **Step 2: Confirm RED before replacing direct local clock reads**
- [ ] **Step 3: Route display timers through the common server-time service and disable unsupported offline mutations**
- [ ] **Step 4: Scan the five owner files for remaining direct device-clock authority and run all edit-mode tests**

### Task 9: Full verification and review

**Files:**
- Modify documentation only if implementation changes the approved contract.

**Interfaces:**
- Produces a local-only implementation report; no publishing action.

- [ ] **Step 1: Run `bash scripts/verify-v14.sh` in `nayon_cloud` and record timings**
- [ ] **Step 2: Run `./gradlew test` in `nayon_api`**
- [ ] **Step 3: Run Unity edit-mode tests without opening Unity or building an APK**
- [ ] **Step 4: Run the PostgreSQL-backed three-day check-in → eligible → actual test reward claim scenario and verify the balance and ledger changed exactly once**
- [ ] **Step 5: Run `git diff --check` and inspect each repository status/diff**
- [ ] **Step 6: Confirm no commit, push, deploy, Unity launch, or APK build occurred**
