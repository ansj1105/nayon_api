# KST 공통 게임 시간·보상 기간 통합 설계

## 1. 목표

- 모든 계정의 일간·주간 보상 판정과 NAYON 소유 타이머를 서버 시각과 `Asia/Seoul` 기준으로 통일한다.
- 클라이언트 시각, 국가, 기기 타임존 변경이 보상 가능 여부에 영향을 주지 않게 한다.
- 날짜·만료·남은 시간 계산, 상태 저장, 보상 수령, 오류 응답을 공통 경계로 정규화한다.
- 기존 UI·메뉴·페이지 구조는 유지하고 데이터 권한만 서버로 이동한다.
- 주간선물은 매주 서로 다른 3일의 로그인된 로비 진입을 기록하고 3일째부터 수령 가능하게 한다.

## 2. 확정 정책

- 서버의 유일한 현재 시각 원본은 `ServerClock`이며, 모든 게임 정책 계산은 이를 `Asia/Seoul`로 변환한 공통 KST 시간 계층을 사용한다.
- 일간 경계는 KST 00:00, 주간 경계는 KST 월요일 00:00이다.
- 클라이언트 타임존과 기기 시각은 보상 기간 판정에 사용하지 않는다.
- 일간·주간 보상 수령은 인증된 온라인 상태에서만 허용한다.
- 오프라인에서는 마지막 서버 상태를 표시할 수 있지만 상태 변경과 로컬 지급은 하지 않는다.
- NAYON이 소유한 이벤트 시작·종료, 구매·구독 만료, 우편 만료, 스태미나 충전, TTL과 카운트다운도 공통 KST 시간 계층을 사용한다.
- DB `timestamptz`는 동일한 순간을 안전하게 저장하기 위한 형식으로 유지하되, 정책 계산과 API 시간 표현은 KST 기준으로 통일한다.
- 작업용 `PlayerPrefs` 기간 데이터는 서버로 이관하지 않는다.
- 주간선물 보상 구성은 미확정이다. 보상 미설정 상태에서는 수령 완료와 경제원장 기록을 모두 막는다.

## 3. 현재 상태 감사

| 기능 | 현재 기준 | 문제 | 목표 |
| --- | --- | --- | --- |
| 주간선물 | Unity `PlayerPrefs`, 기기 로컬 주차 | 시간 조작·재설치·다중 기기·로컬 지급 | 서버 KST 주차·3일 체크인·온라인 수령 |
| 출석 | `TimeManager`의 `DateTime.Now`, 로컬 저장 | 날짜 조작·기기별 불일치 | 서버 KST 일자와 계정 상태 |
| 일간 미션 | 로컬 진행도·날짜 키 | 초기화·수령 권한이 클라이언트에 있음 | 서버 KST 일간 진행도·원장 지급 |
| 주간 미션 | 로컬 월요일 계산·날짜 키 | 주차 조작·중복 수령 | 서버 KST 주간 진행도·원장 지급 |
| 데일리 상점 | `DateTime.Today`, 로컬 난수·수령 키 | 상품·무료/광고 횟수 조작 | 서버 KST 상품 회차·수령/구매 상태 |
| 일반 정찰 누적 보상 | `DateTime.Now` 차이, 로컬 24시간 상한 | 기기 시각 조작·다중 기기 불일치 | 서버 KST 앵커·서버 경과시간 |
| 빠른 정찰 | 로컬 날짜·횟수 | 일일 제한 조작 | 서버 KST 사용량 |
| 친구 번개 선물 | 로컬 날짜·로컬 지급 | 무제한 재수령 가능 | 서버 KST 1일 1회 |
| 시즌 이벤트 일일 미션·출석 | 로컬 날짜·진행도 | 기간·진행·수령 조작 | 글로벌 이벤트 기간 + 서버 KST 일간 회차 |
| 월정액 일일보상 | 서버 UTC 날짜 | KST 자정과 불일치 | 공통 KST 일간 회차 |
| 기간한정 혜택 | 서버 KST 고정 | 기능별 시간 계산 중복 | 공통 KST 계산기 사용 |
| 기간한정 혜택 AdMob 세션 | 서버 5분 TTL | 개별 시간 계산 | 공통 KST 만료 계산 |
| 전투 세션 | 서버 설정 TTL, 기본 2시간 | 개별 `Clock`과 만료 계산 | 공통 KST 만료 계산 |
| 오프라인 전투 제출 창 | 서버 24시간 TTL | 개별 `Clock`과 만료 계산 | 공통 KST 만료 계산 |
| KORION 지갑 연결 | 서버 10분 TTL·1분 요청 제한 | 여러 직접 `Instant.now()` 호출 | 공통 KST 만료·제한 계산 |
| 상점 상품·첫 구매 보상 | 서버 `valid_from`/`valid_until` | 기능별 유효기간 판정 | 공통 KST 유효기간 계산 |
| 우편·구독·구매 만료, 스태미나 | 기능별 절대시간 또는 경과시간 계산 | 계산과 표시 기준이 분산됨 | 공통 KST 만료·남은 시간 계산 |

### 3.1 전수검사 범위와 분류

2026-08-19 기준 다음 사용자 소유 범위를 검사했다.

- `Nayon_Hunters`: `Assets/Scripts`의 C# 220개
- `nayon_api`: `src/main/java`의 Java 257개와 `src/main/resources` 5개
- `nayon_cloud`: Flyway 마이그레이션 13개
- 검색 기준: 현재 시각 생성, 날짜 생성, 타임존 상수, 만료·유효기간·TTL, 남은 시간, 일간·주간 초기화, `Duration` 비교, DB 시간 컬럼과 OpenAPI `date-time`

검출 결과는 다음 세 종류로 나눈다.

1. KST 공통 계층 적용 대상
   - 보상·출석·미션·상점·이벤트·우편·정찰·스태미나
   - 전투 세션과 오프라인 전투 창 TTL
   - KORION 지갑 연결 TTL과 요청 제한
   - 상품·첫 구매·구독·캠페인 유효기간
   - AdMob 보상 세션 만료와 모든 관련 Unity 카운트다운
2. 공통 현재 시각은 사용하지만 KST 달력 경계가 없는 기록 시각
   - 계정 생성·로그인, 저장 완료, 보상 지급·검증·처리·감사 시각
   - 전투 시작·완료·체크포인트 시각
   - DB에는 `timestamptz`로 저장하고 API에서는 KST 오프셋으로 표현한다.
3. KST 게임 시간 계층 적용 제외
   - Unity `Time.time`, `Time.unscaledTime`, 코루틴, 애니메이션, 공격 쿨다운 등 프레임·플레이 시간
   - Cognito 토큰 만료, Google Play이 제공한 구매·구독 원본 시각, AdMob 공개키 캐시, HTTP timeout처럼 외부 계약이 소유한 시간
   - 백업 파일명·로그 파일명 생성용 시각

제외 대상은 게임 정책 타이머가 아니므로 KST로 바꾸지 않는다. 외부에서 받은 순간값은 변조하지 않고, 사용자에게 표시할 때만 KST로 변환한다.

## 4. 공통 시간 아키텍처

### 4.1 서버 시간 원본

`ServerClock`은 주입된 `java.time.Clock`에서 현재 순간만 반환한다. 테스트는 고정 `Clock`을 주입한다. 게임 도메인 코드에서 직접 `Instant.now()`, `LocalDate.now()`, `ZonedDateTime.now()`를 호출하지 않는다.

### 4.2 KST 공통 시간 계산기

`KstGameTimeCalculator`는 `ServerClock`의 현재 순간을 `Asia/Seoul`로 변환하며 모든 NAYON 소유 타이머의 단일 진입점이다.

- `now()`: 현재 KST `ZonedDateTime`
- `expiresAt(startedAt, duration)`: KST 만료 시각
- `remainingUntil(expiresAt)`: 서버 현재 시각 기준 남은 `Duration`, 음수면 0
- `isExpired(expiresAt)`: 서버 현재 시각 기준 만료 여부
- `dailyPeriod()`, `weeklyPeriod()`: KST 일간·주간 기간

경과시간은 타임존에 따라 길이가 달라지지 않지만, 각 기능이 UTC나 기기 시각을 직접 섞지 않도록 시작·종료·만료 판정과 반환 표현을 이 계산기로 통일한다. 외부 API와 Unity에 전달하는 `serverTime`, `startsAt`, `endsAt`, `expiresAt`, `nextResetAt`은 `+09:00` 오프셋을 포함한다.

### 4.3 보상 기간 계산

`RewardPeriodCalculator`는 `KstGameTimeCalculator`를 사용한다.

- `daily(now)`
  - `periodKey`: KST `LocalDate`
  - `startsAt`: 해당 날짜 KST 00:00
  - `endsAt`: 다음 날짜 KST 00:00
- `weekly(now)`
  - `periodKey`: 해당 KST 날짜가 속한 월요일 `LocalDate`
  - `startsAt`: 월요일 KST 00:00
  - `endsAt`: 다음 월요일 KST 00:00

공통 반환형 `RewardPeriod`는 `periodType`, `periodKey`, `startsAt`, `endsAt`, `zoneId`를 가진다.

### 4.4 적용 경계

일간·주간 기간은 `RewardPeriodCalculator`, 만료·TTL·충전·카운트다운은 `KstGameTimeCalculator`가 담당한다. 두 계산기는 같은 `ServerClock`과 `Asia/Seoul` 상수를 공유한다. 기능별 서비스가 별도 타임존 상수나 현재 시각 호출을 갖지 않는다.

## 5. 주간선물 데이터 모델

### `weekly_gift_reward_versions`

- 운영 기본 데이터는 비워두며 실제 품목·수량 정책 확정 전에는 활성 버전을 만들지 않는다.
- `id uuid primary key`, `version integer unique`, `reward_asset_type`, `reward_asset_code`, `reward_amount`, `valid_from`, `valid_until`, `active`, 생성·수정 시각을 가진다.
- 구현 통합 테스트에서만 `DIAMOND 1` 버전을 넣어 3일 체크인부터 실제 원장 지급까지 검증한다.

### `player_weekly_gift_weeks`

- `account_id uuid not null`
- `week_start date not null`
- `claimed_at timestamptz`
- `claim_request_id uuid unique`
- `reward_version_id uuid`
- `claim_response jsonb`
- `created_at timestamptz not null`
- `updated_at timestamptz not null`
- 기본키: `(account_id, week_start)`
- `reward_version_id`와 실제 원장 참조는 보상 정책 확정 후 연결한다.

### `player_weekly_gift_login_days`

- `account_id uuid not null`
- `week_start date not null`
- `login_date date not null`
- `first_seen_at timestamptz not null`
- 기본키: `(account_id, week_start, login_date)`
- `(account_id, week_start)`는 주간 상태 행을 참조한다.

같은 날 여러 기기에서 동시에 체크인해도 기본키 충돌을 정상적인 멱등 재시도로 처리한다. `loginDays`는 해당 주의 로그인 일자 행 수로 계산하며 최대 3까지만 UI에 노출한다.

## 6. 주간선물 API 계약

OpenAPI를 먼저 변경하고 구현·DTO·테스트를 같은 계약에 맞춘다.

### `GET /api/v1/me/weekly-gift`

- 현재 KST 주차 상태를 조회한다.
- 로그인 일자를 기록하지 않는다.

### `POST /api/v1/me/weekly-gift/check-in`

- 로그인된 사용자가 로비에 처음 진입할 때 호출한다.
- 서버가 현재 KST `weekStart`와 `loginDate`를 계산한다.
- 같은 KST 날짜의 재호출은 새 일수를 만들지 않는다.
- 성공 시 현재 상태를 반환한다.

### `POST /api/v1/me/weekly-gift/claim`

- `Idempotency-Key: UUID`를 필수로 받는다.
- 로그인 일자가 3개 미만이면 `409 WEEKLY_GIFT_NOT_ELIGIBLE`이다.
- 보상 버전이 없으면 `409 WEEKLY_GIFT_REWARD_NOT_CONFIGURED`이며 수령 상태를 변경하지 않는다.
- 보상 정책 확정 후에는 상태 잠금, 자격 재검증, 보상 선택, 경제원장 지급, 수령 상태 저장을 하나의 트랜잭션으로 처리한다.
- 같은 요청의 재시도는 저장된 기존 응답을 반환한다.
- 통합 테스트는 서로 다른 KST 3일의 체크인 후 테스트 전용 활성 보상을 실제 경제원장에 한 번 지급하고, 동일 요청 재호출에서 추가 지급이 없음을 확인한다.

### `WeeklyGiftResponse`

```json
{
  "serverTime": "2026-08-19T12:00:00+09:00",
  "zoneId": "Asia/Seoul",
  "weekStart": "2026-08-17",
  "nextResetAt": "2026-08-24T00:00:00+09:00",
  "loginDays": 2,
  "requiredLoginDays": 3,
  "claimable": false,
  "claimEnabled": false,
  "claimed": false,
  "reward": null,
  "economy": null
}
```

`claimable`은 출석 자격, `claimEnabled`는 실제 보상 설정과 지급 가능 여부를 뜻한다. 두 값을 합치지 않는다.

## 7. 공통 오류 모델

서버 도메인 오류는 기능별 예외를 공통 API 오류 응답으로 변환한다.

| HTTP | 코드 | 의미 |
| --- | --- | --- |
| 401 | `AUTH_REQUIRED` | 인증 세션 없음 또는 만료 |
| 409 | `REWARD_NOT_ELIGIBLE` | 현재 기간의 수령 조건 미충족 |
| 409 | `REWARD_ALREADY_CLAIMED` | 다른 요청 ID로 이미 수령 완료 |
| 409 | `REWARD_PERIOD_CHANGED` | 요청 중 기간 경계가 변경됨 |
| 409 | `REWARD_NOT_CONFIGURED` | 지급할 보상 버전이 없음 |
| 503 | `REWARD_SERVICE_UNAVAILABLE` | 재시도 가능한 서버 장애 |

기능 API는 필요하면 `WEEKLY_GIFT_*`처럼 세부 코드를 제공하되 Unity의 공통 분류는 위 범주로 정규화한다. 서버 오류나 타임아웃에서는 로컬 보상을 지급하지 않는다.

## 8. Unity 공통 계층

### `NayonServerTimeService`

- API 응답의 KST `serverTime`과 Unity의 단조 증가 시간을 앵커로 저장한다.
- 카운트다운 표시용 예상 서버 시각을 제공한다.
- 보상 자격을 직접 판정하지 않는다.
- 전역 `DateTime`을 대체하거나 monkey patch하지 않는다.
- NAYON 소유 화면의 만료·충전·TTL·초기화 카운트다운은 이 서비스만 사용하고 `DateTime.Now`, `DateTime.Today`, `DateTime.UtcNow`를 직접 호출하지 않는다.

### `RewardPeriodStateCache`

- 기능별 마지막 서버 응답을 메모리와 안전한 로컬 캐시에 보관한다.
- 오프라인 화면 표시만 지원하며 수령 가능 여부의 최종 권한이 아니다.

### `RewardApiErrorMapper`

- 인증 만료, 오프라인, 조건 미충족, 기간 변경, 보상 미설정, 재시도 가능 장애를 공통 UI 상태로 변환한다.
- 기능별 화면은 토스트 문구, 버튼 상태, 새로고침만 담당한다.

주간선물 화면은 로비 진입 시 `check-in` 응답을 저장하고, 팝업과 빨간 점을 그 상태에서 그린다. 기존 `NyaonWeeklyGift*` `PlayerPrefs`, `DateTime.Today`, 로컬 난수, `ExchangeMaterial()` 지급은 권한 경로에서 제거한다.

## 9. 기존 기능 적용 단계

### 1단계: 공통 기반과 기존 서버 기능

- 공통 `ServerClock`, `KstGameTimeCalculator`, `RewardPeriodCalculator`, `RewardPeriod` 추가
- 주간선물 상태·체크인·수령 기반 추가
- 월정액 일일보상의 UTC 날짜를 공통 KST 일간 기간으로 변경
- 기간한정 혜택과 AdMob 세션이 공통 KST 일간 기간을 사용하도록 변경
- 우편·구독·구매 만료, 이벤트 기간, 스태미나 충전, TTL을 공통 KST 시간 계산기로 연결

### 2단계: 단순 일간 상태 서버화

- 출석 및 출석 보상
- 친구 번개 선물
- 빠른 정찰 일일 횟수
- 데일리 상점 회차·무료/광고 보상·상품 목록

각 기능은 전용 상태·수령 테이블과 API를 유지하고 기간 계산만 공유한다. 하나의 범용 보상 테이블에 서로 다른 기능 상태를 억지로 넣지 않는다.

### 3단계: 진행도 누적 기능 서버화

- 일간 미션
- 주간 미션
- 시즌 이벤트 일일 미션·출석

전투·강화·합성·가챠 등 서버가 확인한 이벤트를 기간별 진행도에 반영한다. 보상 수령과 경제원장 기록은 같은 트랜잭션에서 처리한다.

## 10. 전환 정책

- 로컬 작업용 일간·주간 상태는 서버로 이관하지 않는다.
- 주간 기능은 활성화 후 첫 KST 월요일 00:00부터 서버 상태를 사용한다.
- 일간 기능은 단계별 활성화 후 첫 KST 00:00부터 서버 상태를 사용한다.
- 활성화 경계 이후에는 로컬 상태 변경과 로컬 지급을 중단한다.
- 배포 플래그는 서버 응답에 포함하고 Unity는 서버 활성화 전후를 명시적으로 구분한다.
- UI·메뉴·팝업 구조와 문구는 별도 제품 요청이 없으면 변경하지 않는다.

## 11. 검증 기준

### 시간 계산

- KST 23:59:59와 00:00:00의 일간 키·`nextResetAt`
- 일요일 23:59:59와 월요일 00:00:00의 주간 키·`nextResetAt`
- 우편·구독·구매 만료와 이벤트 종료 전후 1초의 만료 판정
- 스태미나 충전 간격과 장시간 오프라인 복귀 시 누적량
- API의 모든 게임 시간 필드가 `+09:00` 오프셋을 포함함
- Unity 기기 시각 변경 후에도 카운트다운과 서버 만료 판정이 달라지지 않음
- 서버 JVM·DB 세션 타임존이 달라도 결과가 동일함
- 클라이언트 타임존과 기기 시각 변경이 결과에 영향을 주지 않음

### 주간선물

- 같은 날 반복·동시 체크인은 1일만 기록
- 서로 다른 3일째 체크인부터 `claimable=true`
- 3일 미만 수령 거부
- 보상 미설정 수령 거부 후 `claimed_at`과 원장 행이 없음
- 여러 기기 동시 수령과 네트워크 재시도에서 한 번만 처리
- 주간 경계 직전 요청이 경계 이후 상태에 잘못 반영되지 않음

### 기존 기능 회귀

- 월정액 일일보상이 KST 자정에만 새 회차가 됨
- 기간한정 혜택·AdMob 세션·수령이 같은 KST `cycleDate`를 사용
- Unity 오프라인 상태에서 수령 버튼이 비활성화되고 로컬 재화가 변하지 않음
- 기존 페이지·팝업·빨간 점 위치와 노출 구조가 유지됨

## 12. 저장소별 변경 범위

### `nayon_cloud`

- 공통 기간 자체는 DB 함수로 중복 구현하지 않는다.
- 주간선물과 단계별 기능의 상태·수령·원장 참조 테이블 및 롤백 마이그레이션을 추가한다.
- 일간·주간 유니크 키가 KST 기간 키를 기준으로 동시성을 차단한다.

### `nayon_api`

- OpenAPI가 계약의 원본이다.
- 공통 KST 시간·기간·오류 계층과 기능별 서비스·저장소·컨트롤러를 추가한다.
- NAYON 소유 타이머에서 직접 현재 시각과 개별 타임존을 계산하는 코드를 제거한다.
- 보상 수령 서비스는 경제원장과 같은 트랜잭션 경계를 가진다.

### `Nayon_Hunters`

- 공통 서버 시각 앵커, 상태 캐시, 오류 매퍼를 추가한다.
- 기존 화면 구조는 보존하고 로컬 날짜 판정·로컬 보상 지급만 서버 응답 기반으로 교체한다.
- 일반 구현 검증은 Unity Editor에서 수행하되 APK 빌드는 별도 명시 요청이 있을 때만 수행한다.

## 13. 비범위

- 주간선물 실제 보상 품목·수량·확률 결정
- 새로운 메뉴·팝업·내비게이션 추가
- 국가별 타임존 또는 사용자 타임존 보상 정책
- 외부 서비스가 소유한 토큰·SDK·프로토콜 만료 규칙 변경
- APK 빌드·기기 설치·스토어 배포
