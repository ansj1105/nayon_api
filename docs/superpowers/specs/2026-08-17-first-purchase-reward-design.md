# NYAON First Purchase Reward Design

## 목표

- Google Play에서 서버 검증이 끝난 계정의 첫 유료 구매에만 최초 구매 보상을 자동 지급한다.
- 장애, 재시도, 동시 구매, 앱 재설치에도 계정당 한 번만 지급한다.
- Unity는 지급 여부를 판단하거나 재화를 생성하지 않고 서버 결과만 표시한다.

## 현재 상태

- 일반 상품 구매는 `POST /api/v1/store/purchases/google-play/verify`가 Google 구매 토큰을 검증하고 구매 상품 보상을 서버 경제 원장에 한 번만 지급한다.
- 최초 구매 이벤트 화면은 구매 자격을 확인하지 않는다.
- 장비, 보석 50개, 골드 10,000개를 Unity에서 직접 지급하고 세 개의 `PlayerPrefs` 키로만 수령 여부를 기록한다.
- 앱 데이터 삭제나 변조로 재수령할 수 있고, 다른 기기에서는 지급 여부가 일치하지 않는다.
- 현재 장비 보상은 `EquipmentDic`의 첫 항목을 사용하므로 보상 결과가 안정적으로 재현되지 않는다.

## 확정된 방식

- 첫 `GRANTED` Google Play 구매와 같은 DB 트랜잭션에서 최초 구매 보상을 자동 지급한다.
- 보상은 일반 장비 상자 1개에 해당하는 랜덤 일반 등급 장비 1개, `DIAMOND=50`, `GOLD=10000`이다.
- 상자는 별도 클라이언트 아이템으로 남기지 않고 서버가 즉시 개봉 결과를 결정한다. 따라서 상자 소비 API나 클라이언트 난수에 의존하지 않는다.
- 장비 후보와 선택 로직은 서버 장비 카탈로그를 사용하고, 선택된 장비 코드·등급·카탈로그 버전을 지급 행에 스냅샷으로 저장한다.
- 상품 가격과 일반 구매 보상은 기존 서버 상품 버전 체계를 그대로 사용한다. 최초 구매 보상 금액은 별도 버전 레코드로 관리해 이후 변경이 과거 지급 기록을 바꾸지 않게 한다.

## 불변식과 소유권

- Google Play는 유료 구매 완료 여부의 소유자다.
- NYAON DB는 최초 구매 여부, 보상 버전, 선택된 장비, 지급 시각과 경제 원장의 소유자다.
- 계정별 최초 구매 보상 행은 최대 하나다.
- 최초 구매 보상 행의 `qualifying_receipt_id`는 `GRANTED` 상태인 동일 계정 구매 영수증만 참조한다.
- 상품 보상과 최초 구매 보상은 모두 성공하거나 모두 롤백된다.
- 재생된 구매 검증 응답은 저장된 최초 구매 보상 결과를 반환하며 보상을 다시 만들지 않는다.
- Unity `PlayerPrefs`는 표시 캐시로도 사용하지 않는다. 서버 조회·구매 응답만 권위가 있다.

## 데이터 모델

### `first_purchase_reward_versions`

- `id uuid primary key`
- `version integer unique not null`
- `equipment_catalog_version varchar(40) not null`
- `equipment_grade varchar(20) not null default 'COMMON'`
- `diamond_amount bigint not null check (diamond_amount > 0)`
- `gold_amount bigint not null check (gold_amount > 0)`
- `active boolean not null`
- `valid_from timestamptz not null`, `valid_until timestamptz`
- 활성 기간이 겹치는 버전을 허용하지 않는다.

### `player_first_purchase_rewards`

- `id uuid primary key`
- `account_id uuid not null unique references player_accounts(id)`
- `qualifying_receipt_id uuid not null unique`
- `store_purchase_receipts(id, account_id)` unique key와 `(qualifying_receipt_id, account_id)` 복합 FK로 구매 영수증 소유권을 강제한다.
- `reward_version_id uuid not null references first_purchase_reward_versions(id)`
- `equipment_id uuid not null unique`
- `player_equipment(id, account_id)` unique key와 `(equipment_id, account_id)` 복합 FK로 선택 장비 소유권을 강제한다.
- `equipment_code varchar(80) not null`, `equipment_grade varchar(20) not null`
- `diamond_amount bigint not null`, `gold_amount bigint not null`
- `diamond_balance bigint not null`, `gold_balance bigint not null`
- `granted_at timestamptz not null`

Forward migration은 기존 영수증·장비 테이블에 복합 unique constraint를 추가하고 신규 테이블·인덱스·초기 버전을 만든다. 두 constraint 생성 중 짧은 쓰기 잠금과 전체 인덱스 스캔이 발생하므로 배포 전 행 수·소요 시간을 스테이징에서 측정한다. Rollback은 먼저 보상 지급 행, 버전 테이블, 장비·영수증 복합 unique constraint 순서로 제거하며 운영 지급 데이터가 생긴 뒤에는 실행하지 않는다. `GRANTED` 상태는 일반 FK로 표현할 수 없으므로 동일 트랜잭션의 서비스 검증과 통합 테스트로 강제한다.

## API 계약

### `GET /api/v1/store/first-purchase-reward`

- 로그인 계정의 서버 지급 상태를 반환한다.
- 응답:
  - `status`: `NOT_GRANTED` 또는 `GRANTED`
  - `qualifyingReceiptId`, `grantedAt`
  - `rewardVersion`
  - `rewards`: 선택된 장비, 보석, 골드와 지급 후 잔액
  - `economy`: 현재 서버 경제 스냅샷
- 구매 전에는 `200 NOT_GRANTED`이며 클라이언트가 별도 claim을 호출하지 않는다.

### `POST /api/v1/store/purchases/google-play/verify`

- 기존 응답에 nullable `firstPurchaseReward`를 추가한다.
- 해당 요청이 계정 최초 지급을 만들었거나 기존 최초 지급 영수증을 재생한 경우 `GRANTED` 상세를 반환한다.
- 이후 구매에는 `firstPurchaseReward: null`을 반환한다.
- `firstPurchaseReward`에는 선택 장비와 두 통화 보상, 지급 시각, 보상 버전, 지급 직후 전체 `economy` 스냅샷이 포함된다.
- 기존 필드와 상태 코드는 유지하므로 기존 클라이언트에는 비파괴 추가 변경이다.

## 서버 처리 흐름

1. 기존 흐름대로 Google 구매를 검증한다.
2. `battle-account:<accountId>` 계정 잠금과 구매 영수증 행 잠금을 획득한다.
3. 일반 상품 보상을 원장에 지급한다.
4. 계정의 최초 구매 보상 행을 조회한다.
5. 행이 없으면 구매 시각에 유효한 최초 구매 보상 버전을 하나 선택한다.
6. 서버 카탈로그와 보안 난수로 일반 등급 장비 한 개를 선택해 `player_equipment`에 기록한다.
7. 같은 보상 ID를 참조해 보석 50개와 골드 10,000개를 `economy_ledger`에 기록한다.
8. 선택 결과와 지급 후 잔액을 `player_first_purchase_rewards`에 저장한다.
9. 구매 영수증과 최초 보상 결과를 한 응답으로 반환한다.

동시 첫 구매는 계정 잠금과 `account_id unique`가 직렬화한다. 두 번째 트랜잭션은 이미 저장된 행을 읽고 신규 지급하지 않는다.

## Unity 변경

- 상점 결제 버튼과 Unity IAP 흐름은 그대로 유지한다.
- 구매 검증 응답의 `firstPurchaseReward`가 있으면 기존 보상 팝업 자산으로 장비 상자, 보석 50, 골드 10,000 지급 완료를 표시한다.
- 이벤트 팝업을 열 때 `GET /store/first-purchase-reward`를 호출한다.
- `NOT_GRANTED`이면 세 보상 카드는 비활성화하고 `상점 보기`만 제공한다.
- `GRANTED`이면 세 카드 모두 `지급 완료`로 표시하고 서버가 선택한 장비 상세를 보여준다.
- `OnClickEventPurchaseReward`, `GrantAttendanceMaterial`, 임의 장비 생성, `EventPurchaseRewardClaimed_*` 기반 지급 코드를 제거한다.
- 지급 응답의 경제 스냅샷을 기존 `NayonGameCloudBridge.ApplyEconomy` 경로로 반영한다. 선택 장비의 서버 UUID를 `Equipment.ServerId`로 게임 저장 데이터에 함께 기록하고 같은 UUID가 이미 있으면 다시 추가하지 않는다. 지급 상태 판단에는 `PlayerPrefs`를 사용하지 않는다.
- 조회 실패 시 보상을 로컬로 추정하지 않고 재시도 안내만 표시한다.

## 테스트 기준

- 첫 검증 구매가 일반 상품 보상과 최초 구매 보상 전체를 한 번만 지급한다.
- 같은 idempotency key, 새 idempotency key와 동일 token 재생 모두 추가 지급하지 않는다.
- 서로 다른 두 구매의 동시 검증에서도 최초 보상 행과 장비가 하나만 생긴다.
- 두 계정은 각각 독립적으로 한 번 지급받는다.
- Google 미검증·보류·취소 구매와 경제 미부트스트랩 계정은 최초 보상을 만들지 않는다.
- 활성 보상 버전이 없거나 중복되거나 장비 후보가 없으면 일반 구매 지급까지 롤백된다.
- GET은 구매 전 `NOT_GRANTED`, 구매 후 저장된 정확한 스냅샷을 반환한다.
- OpenAPI, DTO, 컨트롤러 계약 테스트가 nullable 추가 필드와 조회 응답을 검증한다.
- Unity 테스트는 미지급 카드 비활성화, 구매 응답 적용, 재오픈 후 지급 완료 복원, API 실패 시 로컬 미지급을 검증한다.

## 배포 순서

1. DB forward migration 배포
2. 하위 호환 응답을 제공하는 NYAON API 배포
3. Unity 클라이언트 배포
4. 최초 구매 보상 GET 및 지급 메트릭 확인

이 문서 작성 단계에서는 커밋, 푸시, 배포를 수행하지 않는다.
