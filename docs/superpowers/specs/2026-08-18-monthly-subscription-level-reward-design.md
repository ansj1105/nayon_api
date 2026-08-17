# NYAON 월정액 구독 및 레벨 보상 설계

## 목표

Google Play 자동 갱신 월정액 두 종류와 계정 레벨 보상을 서버 권한으로 전환한다. 상품 가격, 상품 ID, 보상 수치와 요구 레벨은 배포 없이 변경할 수 있어야 하며, 클라이언트 저장값으로 구독이나 지급 여부를 위조할 수 없어야 한다.

## 확정 정책

- `MONTHLY_GROWTH`와 `MONTHLY_ADVANCED`는 각각 1개월 자동 갱신 Google Play 구독이다.
- 두 구독은 서로 독립적이다. 한 구독이 다른 구독의 혜택이나 보상 트랙을 열지 않는다.
- 성장 기금의 `프리미엄` 트랙은 `MONTHLY_GROWTH`, `로열` 트랙은 `MONTHLY_ADVANCED`에 연결한다.
- 두 구독을 동시에 유지할 수 있다. 각 구독의 보상 트랙과 혜택은 독립 적용되며, 수치형 전투 보너스는 각 구독에 명시된 값을 합산한다.
- 계정 레벨 보상은 `FREE`, `PREMIUM`, `ROYAL` 각 트랙과 요구 레벨 조합마다 계정 평생 한 번만 지급한다.
- 결제 주기 갱신, 해지, 만료, 재구독으로 레벨 보상 수령 기록을 초기화하지 않는다.
- 구독이 만료되면 해당 유료 트랙의 미수령 보상만 잠긴다. 재구독 후에는 남은 보상을 이어서 받을 수 있다.
- 해지했더라도 Google Play가 반환한 만료 시각까지는 혜택을 유지한다.
- 가격은 Google Play 현지화 가격이 표시 권한을 가진다. Unity와 API는 원화 가격을 하드코딩하지 않는다.
- 상품 ID, 보상량, 요구 레벨, 즉시/일일 보상과 전투 혜택 수치는 DB 카탈로그가 권한을 가진다.
- 오프라인에서는 마지막 서버 상태를 읽기 전용으로 표시할 수 있지만 구매 활성화, 구독 활성 판정 또는 보상 지급은 하지 않는다.

## 현재 구현의 정책 위반

- Unity의 성장 기금 프리미엄/로열과 미션 준비의 일반/고급 월정액이 `PlayerPrefs`의 만료 없는 구매 플래그를 사용한다.
- Unity IAP가 모든 상품을 `ProductType.Consumable`로 등록한다.
- API와 DB가 `ONE_TIME`만 지원하고 Google Play `productsv2`만 조회한다.
- 갱신, 해지, 유예, 보류, 일시중지, 만료와 RTDN 상태 변경을 저장하지 않는다.
- 성장 기금 가격, 레벨 조건과 보상이 `UI_BattlePopup`에 하드코딩되어 있다.
- 무료/유료 레벨 보상을 Unity가 직접 지급하고 수령 여부도 로컬에 저장한다.
- 테스트용 `ResetHunterLevelFundRewardsOnce`가 수령 기록을 초기화한다.
- 미션 준비 월정액의 즉시/일일 지급과 전투 혜택은 서버 권한으로 구현되어 있지 않다.

## 접근 방식

### 채택: 서버 권한 구독 + RTDN

구매 직후 서버가 `purchases.subscriptionsv2.get`으로 검증하고, 이후 Google Cloud Pub/Sub RTDN을 받아 동일 API로 최신 상태를 다시 조회한다. 서버 DB의 entitlement만 게임 기능과 보상 청구의 권한으로 사용한다.

이 방식은 앱이 실행되지 않은 동안 발생한 자동 갱신, 결제 실패, 해지와 환불도 반영할 수 있다.

### 제외: 앱 실행 시에만 영수증 재검증

구현량은 적지만 앱이 실행되지 않으면 해지·보류·환불 상태가 서버에 늦게 반영된다. 서버 보상과 계정 간 동기화 요구를 충족하지 못한다.

### 제외: 외부 구독 관리 SaaS

현재 두 상품과 Android 단일 스토어에는 추가 공급자, 비용과 데이터 경계가 불필요하다. Google Play Developer API와 기존 Spring 서비스로 충분하다.

## 데이터 모델

Flyway `V13`/`U13` 쌍으로 다음 스키마를 추가한다.

### 기존 스토어 카탈로그 확장

- `store_products.product_type`에 `SUBSCRIPTION`을 허용한다.
- 구독 상품도 기존 `store_offers`와 `store_products`의 안정적인 offer/product 매핑을 재사용한다.
- `store_product_versions.fulfillment_type`에 `SUBSCRIPTION`을 허용하되 직접 재화를 지급하지 않는다.

### 구독 플랜과 혜택

- `subscription_plans`
  - `plan_code`: `MONTHLY_GROWTH` 또는 `MONTHLY_ADVANCED`
  - `offer_id`, `reward_track_code`, `active`, `valid_from`, `valid_until`
- `subscription_benefit_versions`
  - 플랜, 버전, 혜택 코드, 수치, 유효 기간, 활성 여부
  - 즉시 보석, 일일 보석, 새로고침, 부활, 최대 에너지, 골드 보너스, 전투 가속, 광고 스킵을 행 단위로 저장한다.

### 계정 구독 상태

- `player_subscriptions`
  - 계정, 플랜, Google purchase token 및 SHA-256, 현재/연결된 토큰, 상태, 시작/만료 시각, 자동 갱신 여부, acknowledgement 상태, 마지막 검증 시각
  - purchase token hash는 전역 유일하다.
  - 계정과 플랜별 현재 entitlement는 한 행만 유지한다.
- 상태는 `PENDING`, `ACTIVE`, `CANCELED`, `GRACE_PERIOD`, `ON_HOLD`, `PAUSED`, `EXPIRED`, `REVOKED`를 보존한다.
- 접근 가능 상태는 현재 시각이 `expires_at`보다 이전이며 상태가 `ACTIVE`, `CANCELED`, `GRACE_PERIOD`인 경우다.

### RTDN 멱등성

- `google_play_rtdn_events`
  - Pub/Sub `message_id`를 기본 키로 저장한다.
  - package name, notification type, purchase token hash, 수신/처리 시각, 처리 결과를 저장한다.
  - 같은 메시지는 다시 처리하지 않는다.

### 레벨 보상

- `level_reward_versions`
  - 카탈로그 버전, 트랙(`FREE`, `PREMIUM`, `ROYAL`), 요구 레벨, 자산 코드, 수량, 유효 기간, 활성 여부
  - 활성 정의는 트랙과 요구 레벨마다 하나만 허용한다.
- `player_level_reward_claims`
  - 계정, 트랙, 요구 레벨, 적용한 보상 버전과 지급 스냅샷, 지급 원장 ID, 지급 시각
  - `(account_id, track_code, required_level)` 유일 제약으로 결제 주기와 무관한 평생 1회를 보장한다.

### 월정액 지급

- `player_subscription_initial_rewards`
  - `(account_id, plan_code)` 유일 제약으로 최초 활성화 즉시 보상을 평생 한 번만 지급한다.
- `player_subscription_daily_rewards`
  - `(account_id, plan_code, reward_date)` 유일 제약으로 서버 기준 날짜마다 한 번만 지급한다.
- 구독이 활성인 날짜에만 일일 지급을 청구할 수 있다.

## 계정 레벨 권한

- 보상 청구는 Unity가 보낸 레벨을 신뢰하지 않는다.
- 서버의 `player_progression.account_exp`와 Unity의 현재 `AccountLevelData` 요구 경험치를 동기화한 버전 카탈로그로 계정 레벨을 계산한다.
- 레벨 곡선 버전을 서버 리소스로 고정하고 Unity 데이터와 일치하는 계약 테스트를 둔다.
- 보상 청구 트랜잭션은 계정 progression을 잠근 뒤 레벨, 구독 entitlement, 기존 claim을 검사한다.

## API 계약

OpenAPI를 구현보다 먼저 변경한다.

### `GET /subscriptions/catalog`

- 두 독립 플랜의 `planCode`, Google Play `productId`, 연결된 reward track, 활성 benefit 버전을 반환한다.
- 가격 문자열은 반환하지 않는다. Unity IAP가 Google Play 현지화 가격을 결합한다.

### `GET /me/subscriptions`

- 계정별 두 플랜의 상태, entitlement 활성 여부, 시작/만료 시각, 자동 갱신 여부를 반환한다.

### `POST /store/subscriptions/google-play/verify`

- `Idempotency-Key`, `productId`, `purchaseToken`을 받는다.
- obfuscated account ID, 상품 ID, 구독 상태와 expiry를 Google Play 응답으로 검증한다.
- 최초 활성화 보상은 같은 트랜잭션에서 정확히 한 번 지급한다.
- 동일 요청/토큰 재시도는 동일 결과를 반환한다.

### `POST /public/google-play/rtdn`

- Google Cloud Pub/Sub push OIDC 토큰의 audience와 허용 service account를 검증한다.
- `messageId`를 중복 제거하고 `purchases.subscriptionsv2.get`으로 최신 상태를 조회해 entitlement를 갱신한다.
- 알림 본문의 상태만 신뢰하지 않는다.

### `GET /me/level-rewards`

- 서버 계산 계정 레벨, 세 트랙, 현재 보상 카탈로그, claim 여부와 claim 가능 여부를 반환한다.
- 유료 트랙은 연결 구독의 현재 entitlement를 함께 반환한다.

### `POST /me/level-rewards/{trackCode}/{requiredLevel}/claim`

- `Idempotency-Key`가 필수다.
- 서버 레벨, 구독 상태, 활성 보상 버전과 기존 claim을 한 트랜잭션에서 확인한다.
- 원장 지급과 claim insert를 원자적으로 수행하고 authoritative balance를 반환한다.

### `POST /me/subscriptions/{planCode}/daily-reward/claim`

- 서버 날짜와 entitlement를 기준으로 일일 보상을 정확히 한 번 지급한다.

## Google Play 상태 처리

- `ACTIVE`: 만료 시각까지 활성.
- `CANCELED`: 자동 갱신은 꺼졌지만 만료 시각까지 활성.
- `GRACE_PERIOD`: Google 정책에 따라 혜택 유지.
- `ON_HOLD`, `PAUSED`, `EXPIRED`, `REVOKED`: 즉시 게임 entitlement 비활성.
- purchase token이 교체되면 `linkedPurchaseToken`으로 기존 계정/플랜 소유권을 이어가고 다른 계정의 토큰 재사용을 거부한다.
- 서버가 검증한 expiry만 저장하고 기기 시각은 사용하지 않는다.

## Unity 변경

- `UnityIapStoreGateway`는 카탈로그의 product type에 따라 `ProductType.Subscription`을 등록한다.
- 성장 기금과 미션 준비 화면은 같은 서버 subscription snapshot을 사용한다.
- `PlayerPrefs` 구매 플래그와 레벨 보상 수령 플래그는 권한 판정에서 제거한다.
- 화면의 가격은 Unity IAP `localizedPriceString`, 보상/레벨은 API 응답을 사용한다.
- 무료/유료 보상 클릭은 서버 claim 성공 응답 후에만 로컬 경제 스냅샷을 갱신한다.
- 구독 만료 시 이미 받은 셀은 `수령 완료`, 미수령 셀은 `구독 필요`로 표시한다.
- 네트워크 실패 시 권한이나 보상을 추측하지 않고 재시도 가능한 오류를 표시한다.
- `ResetHunterLevelFundRewardsOnce`와 관련 초기화 키를 제거한다.

## 미션 준비 월정액 혜택

- 일반/고급 월정액 카드는 독립 entitlement 상태와 Google 현지화 가격을 표시한다.
- 최초 즉시 보상은 계정/플랜별 평생 한 번, 일일 보상은 활성 기간 중 서버 날짜별 한 번이다.
- 전투 새로고침, 부활, 최대 에너지, 골드 보너스, 가속, 광고 스킵은 서버 benefit snapshot에서 계산한다.
- 두 구독이 동시에 활성인 경우 서로 다른 플랜의 수치형 보너스는 합산한다.
- `영구 전투 패스`는 자동 갱신 구독이 아니므로 이번 변경에서 기존 일회성 상품으로 유지하며 구독 상태에 포함하지 않는다.

## 오류와 보안

- 상품/토큰/계정 불일치는 지급 없이 terminal 409 또는 422로 기록한다.
- Google timeout/5xx/429는 로컬 상태를 임의로 만료시키지 않고 재시도 가능한 503/429로 반환한다.
- purchase token 원문은 로그, API 응답과 메트릭 label에 기록하지 않는다.
- RTDN payload와 메시지 ID는 크기 제한을 적용하고 인증 실패는 401/403으로 거부한다.
- 보상 지급은 기존 economy account advisory lock과 동일한 순서로 직렬화하여 교착과 중복 지급을 방지한다.

## 배포 단계

1. `nayon_cloud/develop-sj`: V13/U13과 검증 스크립트를 배포한다. 신규 테이블 및 제약 추가만 수행한다.
2. `nayon_api/develop-sj`: OpenAPI, 구독 검증/RTDN/레벨 보상 API를 배포한다. 상품 데이터가 없으면 빈 카탈로그를 반환한다.
3. Google Play Console과 Google Cloud Pub/Sub에 두 구독 상품, base plan, RTDN push 인증을 등록한다.
4. 운영 DB에 활성 상품/혜택/보상 카탈로그를 입력한다.
5. `Nayon_Hunters/develop-sj`: Unity를 서버 API와 `ProductType.Subscription`에 연결한다.
6. license tester로 신규 구매, 자동 갱신, 해지 후 만료, grace period, hold, restore와 재구독을 검증한다.

## 마이그레이션 위험과 롤백

- forward migration은 기존 대형 테이블의 데이터 backfill 없이 신규 테이블/제약을 추가하므로 잠금 위험이 낮다.
- `store_products` check constraint 교체는 짧은 테이블 잠금을 요구하므로 배포 전 행 수와 실행 시간을 staging에서 측정한다.
- U13은 신규 claim/구독 기록을 삭제하므로 트래픽 중지 및 기능 비활성화 후에만 실행한다.
- API 배포 실패 시 Unity가 신규 API를 사용하기 전에는 V13을 유지해도 기존 기능에 영향이 없다.
- Unity 배포 후에는 DB rollback보다 API 이전 이미지 복구와 구독 기능 비활성화를 우선한다.

## 검증 기준

- OpenAPI 계약 테스트가 두 구독을 독립 entitlement로 표현한다.
- Google subscription v2 응답 parser가 active, canceled-before-expiry, grace, hold, paused, expired, revoked를 구분한다.
- 같은 RTDN `messageId`와 같은 purchase token 재처리가 멱등이다.
- 다른 계정이 같은 token을 사용할 수 없다.
- 일반 구독만 활성일 때 PREMIUM만, 고급 구독만 활성일 때 ROYAL만 claim 가능하다.
- 결제 갱신이나 재구독 후에도 이미 claim한 레벨 보상은 다시 지급되지 않는다.
- 동시 claim 두 건에서 원장 지급은 한 번만 발생한다.
- 보상 카탈로그 변경 후 새 claim은 새 버전, 기존 claim은 저장된 지급 스냅샷을 유지한다.
- Unity EditMode 계약 테스트와 Windows Unity Play Mode에서 가격, 활성/만료, 수령 완료/구독 필요 상태를 확인한다.
- 배포 후 authenticated smoke test, RTDN test notification, 로그/메트릭/알람을 확인한다.

## 외부 설정 필요 사항

- Google Play Console에 일반/고급 자동 갱신 구독과 1개월 base plan을 생성해야 한다.
- Play Console RTDN topic과 Google Cloud Pub/Sub push subscription을 연결해야 한다.
- Pub/Sub push service account와 API audience를 운영 설정에 등록해야 한다.
- 실제 product ID와 출시 가격은 코드가 아닌 Play Console 및 DB 카탈로그에 입력한다.

## 공식 참고 자료

- Google Play 구독 수명주기: <https://developer.android.com/google/play/billing/lifecycle/subscriptions>
- Google Play 서버 백엔드 연동: <https://developer.android.com/google/play/billing/backend>
- Google Play RTDN: <https://developer.android.com/google/play/billing/rtdn-reference>
- `purchases.subscriptionsv2`: <https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2>
