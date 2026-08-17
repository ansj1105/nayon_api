# Google Play 결제 검증·상품 카탈로그 API 설계

## 목표

- Google Play 결제가 확인된 NYAON 계정에만 서버 경제 원장 보상을 지급한다.
- 동일 `purchaseToken`은 재시도·동시 요청에서도 한 번만 지급한다.
- 가격, Google 상품 ID, 보상 구성이 바뀌어도 과거 구매의 검증·감사·재처리가 유지된다.
- 1차 상품은 Android 보석 패키지 6종으로 제한한다.

## 현재 문제

- Unity 프로젝트에 Unity IAP 패키지와 Google Play 상품 ID가 없다.
- 현재 인앱결제 버튼은 스토어 호출 없이 완료 콜백을 실행하고 로컬 재화를 지급한다.
- 가격·보상·구매 여부가 Unity 코드와 `PlayerPrefs`에 저장된다.
- NYAON API에는 스토어 상품 카탈로그, 영수증 검증, 구매 이력, 중복 지급 방지가 없다.

## 확정된 선택과 대안

### 채택: DB 버전형 상품 카탈로그와 서버 검증

- 안정적인 내부 `offer_code`와 변경 가능한 Google `store_product_id`를 분리한다.
- 상품 변경은 기존 행 수정이 아니라 새 버전 추가와 이전 버전 비활성화로 처리한다.
- Unity는 Google Play가 반환한 현지화 가격만 표시한다.
- NYAON API는 Google Play Developer API의 구매 상태와 서버 상품 버전만 신뢰한다.
- 보상은 구매 당시 상품 버전의 스냅샷으로 경제 원장에 정확히 한 번 지급한다.

### 제외: Unity 또는 API 코드에 상품 전체 하드코딩

- 상품 변경마다 클라이언트 또는 API 재배포가 필요하다.
- 과거 결제와 현재 보상 정의가 달라질 때 지급 근거를 재현할 수 없다.

### 제외: 클라이언트 전달 가격·보상 신뢰

- 변조된 APK나 프록시 요청으로 임의 보상을 만들 수 있다.
- 요청 DTO에는 가격, 통화, 보상 수량 필드를 두지 않는다.

## 범위

### 포함

- Android `GOOGLE_PLAY` 카탈로그 조회
- 소비성 일회성 보석 패키지 6종
- Google purchase token 서버 검증
- 구매 요청 멱등성, 토큰 전역 유일성, 계정 격리
- 보석 지급과 `economy_ledger` 기록
- 지급 후 Unity IAP가 수행할 Google 소비 확정 대기
- 과거 비활성 상품 ID의 미처리 토큰 검증
- OpenAPI, 단위·계약·PostgreSQL 통합 테스트
- 검증·지급 실패 메트릭과 비밀정보 비기록

### 제외

- 성장 기금, 영구 패스, 월정액, 한정 패키지의 실제 결제
- KORI 결제
- 구독과 자동 갱신
- Unity IAP 연결과 결제 화면 수정
- Play Console 상품 생성 자동화
- 환불 RTDN과 이미 사용한 재화의 회수 정책
- 운영자 상품 관리 UI

## 불변식과 소유권

- Google Play는 결제 상태, 구매 토큰, 스토어 상품 ID의 소유자다.
- NYAON DB는 내부 상품 버전, 보상 정의, 구매 이력, 지급 원장의 소유자다.
- Unity는 구매 시작과 표시만 담당하며 지급 권한이 없다.
- Unity IAP는 서버가 `GRANTED`를 반환한 뒤 Google 소비를 확정하는 단일 소유자다.
- `store_product_id` 매핑과 보상 버전은 생성 후 의미를 바꾸지 않는다.
- 가격은 Google Play 카탈로그의 현지화 값이며 NYAON 지급 판단에 사용하지 않는다.
- 과거 상품 버전은 판매만 중지하고 구매 검증 데이터에서는 삭제하지 않는다.
- 보상은 `store_purchase_receipts.id`를 원장 참조 ID로 사용해 한 번만 기록한다.
- purchase token 원문은 로그·응답·메트릭·예외 메시지에 남기지 않는다.

## 1차 상품

| 내부 offer code | 기본 보상 | 유형 |
|---|---:|---|
| `diamond_100` | `DIAMOND=100` | 소비성 일회성 |
| `diamond_600` | `DIAMOND=600` | 소비성 일회성 |
| `diamond_1500` | `DIAMOND=1500` | 소비성 일회성 |
| `diamond_3000` | `DIAMOND=3000` | 소비성 일회성 |
| `diamond_7000` | `DIAMOND=7000` | 소비성 일회성 |
| `diamond_15000` | `DIAMOND=15000` | 소비성 일회성 |

- 표의 수량은 초기 서버 보상 정의다.
- 보상 변경은 새 상품 버전으로만 적용한다.
- Google 상품 ID와 가격은 Play Console 확정 후 버전 행에 등록한다.
- 상품 ID가 없는 offer는 카탈로그 응답에 노출하지 않는다.

## 데이터 모델

### `store_offers`

- 목적: 변경되지 않는 게임 내부 상품 식별자
- 필드:
  - `id uuid primary key`
  - `offer_code varchar(64) not null unique`
  - `display_order integer not null`
  - `active boolean not null default true`
  - `created_at`, `updated_at`

### `store_products`

- 목적: 내부 offer와 변경 가능한 플랫폼 상품 ID의 매핑
- 필드:
  - `id uuid primary key`
  - `offer_id uuid not null references store_offers(id)`
  - `platform varchar(20) not null check (platform='GOOGLE_PLAY')`
  - `store_product_id varchar(200) not null`
  - `product_type varchar(20) not null check (product_type='ONE_TIME')`
  - `active boolean not null default false`
  - `created_at`, `updated_at`
- 제약:
  - `(platform, store_product_id)` 유일
  - offer·platform별 활성 상품 ID 하나
  - 상품 ID 교체 시 새 행을 추가하고 이전 행은 비활성화

### `store_product_versions`

- 목적: 동일 스토어 상품의 기간별 불변 보상 버전
- 필드:
  - `id uuid primary key`
  - `product_id uuid not null references store_products(id)`
  - `version integer not null check (version >= 1)`
  - `reward_asset_type varchar(20) not null`
  - `reward_asset_code varchar(40) not null`
  - `reward_amount bigint not null`
  - `active boolean not null default false`
  - `valid_from timestamptz not null`, `valid_until timestamptz`
  - `created_at timestamptz not null default now()`
- 제약:
  - `(product_id, version)` 유일
  - 스토어 상품별 활성 버전 하나
  - `valid_until`이 있으면 `valid_until > valid_from`
  - 운영 변경은 한 트랜잭션에서 이전 버전 `valid_until` 설정 후 새 버전을 추가한다.

### `store_purchase_receipts`

- 목적: 외부 구매 검증과 지급 상태의 감사 원장
- 필드:
  - `id uuid primary key`
  - `account_id uuid not null references player_accounts(id)`
  - `request_id uuid not null`
  - `request_hash char(64) not null`
  - `platform varchar(20) not null check (platform='GOOGLE_PLAY')`
  - `store_product_id varchar(200) not null`
  - `purchase_token text not null`
  - `purchase_token_hash char(64) not null unique`
  - `product_id uuid not null references store_products(id)`
  - `product_version_id uuid references store_product_versions(id)`
  - `state varchar(30) not null`
  - `google_order_id varchar(200)`, `google_purchase_time timestamptz`
  - `reward_asset_code`, `reward_amount`, `total_asset_balance`
  - `rejection_code`, `last_failure_code`, 검증 시도 횟수
  - `verified_at`, `granted_at`
  - `created_at`, `updated_at`
- 상태:
  - `PENDING_VERIFICATION`: 토큰 저장 완료, Google 확인 전 또는 재시도 대기
  - `REJECTED`: 구매 완료가 아니거나 상품 매핑 불일치
  - `GRANTED`: 서버 보상 지급 완료, Unity IAP 소비 확정 가능
- 제약:
  - `request_id` 전역 유일
  - 동일 요청 ID에 다른 토큰 사용 시 `409`
  - 동일 토큰 재요청은 기존 결과 반환
  - `PENDING_VERIFICATION`은 상품 매핑만 고정하고 보상 버전은 Google 구매 시각 확인 후 결정
  - `GRANTED` 상태만 `granted_at`, 보상 스냅샷, 지급 후 잔액 보유

## API 계약

### 판매 가능 상품 조회

- API endpoint: `GET /api/v1/store/catalog?platform=GOOGLE_PLAY`
- 권한: Cognito 인증 계정
- 응답 필드:
  - `platform`
  - `offers[].offerCode`
  - `offers[].productId`
  - `offers[].productType`
  - `offers[].reward`: assetCode, amount, version
  - `obfuscatedAccountId`: Unity가 Google 결제 시작 시 그대로 전달할 계정 결합값
- 가격 필드는 반환하지 않는다. Unity가 해당 `productId`로 Google Play 상품정보를 조회한다.
- 현재 시각에 판매 가능한 활성 버전만 반환한다.

### Google Play 구매 검증·지급

- API endpoint: `POST /api/v1/store/purchases/google-play/verify`
- 권한: Cognito 인증 계정
- 헤더: `Idempotency-Key: <uuid>` 필수
- 요청:

```json
{
  "productId": "nayon.diamond.100",
  "purchaseToken": "opaque-google-token"
}
```

- 응답 필드:
  - `receiptId`
  - `offerCode`
  - `productId`
  - `state`: `GRANTED`
  - `reward`
  - `totalAssetBalance`
  - `replay`
- 오류:
  - `400`: UUID·상품 ID·토큰 형식 오류
  - `401`: 인증 없음
  - `409`: 멱등키 payload 충돌 또는 토큰·계정 충돌
  - `422`: 구매 미완료, 취소, 상품 매핑 불일치
  - `503`: Google Play 일시 장애; 저장된 동일 토큰으로 재시도 가능

## 검증·지급 흐름

1. Controller: 인증 계정과 `Idempotency-Key` 확인
2. Service: 요청 hash와 purchase token SHA-256 계산
3. Repository 짧은 트랜잭션:
   - 동일 요청·토큰 재생 확인
   - 토큰 전역 유일성 확인
   - 알려진 현재 또는 과거 `store_products` 매핑 확인
   - `PENDING_VERIFICATION`과 상품 매핑 저장; 보상 버전은 아직 비움
4. Google gateway: 패키지 `com.korion.Nayon`, 상품 ID, 토큰으로 구매 조회
5. Service 검증:
   - 구매 상태 `PURCHASED`
   - 요청 상품 ID와 Google 상품 ID 일치
   - Google `obfuscatedExternalAccountId`와 현재 NYAON 계정 결합값 일치
6. Repository 지급 트랜잭션:
   - `battle-account:<accountId>`와 같은 계정 경제 잠금 순서 사용
   - 구매 행 `FOR UPDATE`
   - Google 구매 시각에 유효했던 `store_product_versions`가 정확히 하나인지 확인
   - 해당 상품 버전 보상을 구매 행에 스냅샷 저장
   - `player_wallets`와 `economy_ledger` 갱신
   - 구매 상태 `GRANTED` 저장
7. `GRANTED` 응답 후 Unity IAP가 `ConfirmPurchase`를 호출해 Google 소비 확정

## 외부 연동 경계

### `GooglePlayPurchaseGateway`

- 입력: package name, product ID, purchase token
- 출력: 구매 상태, 외부 주문 ID, 구매 시각, 상품 ID, 계정 식별 hash
- 책임:
  - Google Play Developer API 호출
  - transport·rate limit·invalid response 오류의 명시적 매핑
  - 토큰·서비스 계정 정보 비기록

### 런타임 설정

- `GOOGLE_PLAY_PACKAGE_NAME=com.korion.Nayon`
- `GOOGLE_PLAY_CREDENTIALS_HOST_FILE`
- `STORE_ACCOUNT_HASH_KEY`
- 실제 서비스 계정 JSON은 AWS Secrets Manager에만 저장한다.
- 저장소와 `.env.example`에는 변수명과 placeholder만 둔다.
- `obfuscatedAccountId`는 계정 UUID를 런타임 secret으로 HMAC-SHA256한 64자리 hex다.

## 실패·재시도 정책

- Google 조회 timeout: 구매 행을 `PENDING_VERIFICATION`으로 유지하고 `503` 반환
- 앱 종료: Unity가 복구한 미처리 purchase token을 동일 API로 재전송
- 동시 동일 토큰: DB unique constraint 승자 결과 재조회
- 지급 트랜잭션 실패: 상태를 `GRANTED`로 만들지 않으므로 재시도 가능
- Unity 소비 확정 실패: 앱이 같은 구매와 요청 ID를 복구하고 서버 `GRANTED`를
  재조회한 뒤 `ConfirmPurchase`를 재시도하며 추가 지급 금지
- 비활성 상품: 새 구매 노출은 중지하지만 기존 토큰은 과거 버전으로 검증
- 알 수 없는 상품 ID: 지급 없이 `REJECTED/UNKNOWN_PRODUCT`
- 다른 계정에서 사용된 토큰: `409/PURCHASE_TOKEN_ACCOUNT_CONFLICT`

## 관측성

- 로그 필드:
  - receipt ID, product ID, 검증 상태와 오류 코드
- 로그 제외:
  - purchase token 원문, Cognito JWT, Google 서비스 계정 JSON, 내부 키

## 배포 단계

1. Stage 1 — `nayon_cloud` V9 상품·구매 스키마와 제약조건
2. Stage 2 — 카탈로그 조회 API와 OpenAPI
3. Stage 3 — Google gateway와 검증 상태 저장
4. Stage 4 — 경제 원장 정확히 한 번 지급
5. Stage 5 — 운영 Secret·Play Console 연결 후 sandbox smoke test
6. Stage 6 — Unity IAP 연결, 가짜 지급 제거, 보석 6종만 활성화

## 테스트 기준

- V9 forward/rollback과 모든 unique/check/FK 제약
- offer별 활성 스토어 상품 하나, 스토어 상품별 활성 보상 버전 하나
- 상품 ID 교체와 동일 상품 ID의 보상 버전 교체 후 과거 버전 조회 가능
- 카탈로그에 현재 판매 가능 상품만 노출
- 동일 요청·동일 토큰 replay
- 동일 요청·다른 토큰 `409`
- 다른 계정의 동일 토큰 `409`
- 구매 미완료·취소·알 수 없는 상품 지급 없음
- Google timeout 후 동일 토큰 재시도
- 지급 트랜잭션 실패 후 원장·잔액 미변경
- 동시 검증 요청에서 보석과 원장 각 1회 증가
- Unity 소비 확정 전 서버 재시도에서도 추가 지급 없음
- 과거 비활성 상품 토큰의 정상 지급
- OpenAPI status·schema와 Spring Controller 계약 일치

## Play Console에서 필요한 사용자 작업

- 패키지 `com.korion.Nayon` 앱과 결제 프로필 준비
- 보석 6종을 소비성 일회성 상품으로 생성
- 상품 ID 확정 후 변경 불가 특성을 고려해 안정적인 ID 사용
- 가격 미확정 시 상품을 초안/비활성으로 유지
- 테스트 트랙 APK/AAB 업로드와 라이선스 테스터 등록
- Google Play Developer API 서비스 계정 연결
- 서비스 계정 JSON을 지정된 AWS Secrets Manager 보안 암호에 등록

## 완료 기준

- 스토어가 확인하지 않은 요청은 경제 원장을 변경할 수 없다.
- 유효한 구매 하나는 장애·재시도·동시 요청에서도 한 번만 지급된다.
- 가격과 신규 상품 ID를 바꿔도 API 재배포 없이 새 버전을 활성화할 수 있다.
- 과거 구매는 당시 상품·보상 스냅샷으로 감사 가능하다.
