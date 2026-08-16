# KORION 월렛 푸시 승인·서명 연동 설계

## 목표

- NYAON HUNTERS에서 KORION TRON 지갑 주소를 입력하면 해당 KORION 월렛 계정의 등록 기기로 푸시를 보낸다.
- 사용자가 푸시를 눌러 KORION WALLET 앱의 승인 팝업에서 확인하면, 월렛 앱이 서버 챌린지에 서명하고 KORION 서버가 주소 소유권을 검증한다.
- NYAON은 KORION 서버의 검증 완료 결과만 신뢰해 현재 Cognito 계정에 지갑을 연결한다.
- Google 계정과 KORION 월렛이 모두 연결된 계정은 서버 경제 원장에서 생애 최초 1회 연동 보상을 받는다.

## 확정된 선택과 대안

### 채택: KORION 소유 푸시·서명 검증

KORION이 주소를 자기 사용자와 디바이스에 매핑하고, FCM 발송·챌린지 생성·서명 검증을 소유한다. NYAON은 내부 API로 요청 생성과 상태 조회만 수행한다. 사용자가 승인한 구체적 제품 흐름과 보안 경계에 맞는다.

### 제외: NYAON이 지갑 서명을 직접 검증

NYAON이 체인별 서명 라이브러리와 KORION 주소 소유 관계를 중복 소유하게 된다. KORION 디바이스·지갑 데이터에 직접 접근해야 하므로 서비스 경계가 깨진다.

### 제외: 6자리 코드 또는 주소 입력만으로 연결

지갑 개인키 소유를 증명하지 못하고 현재 클라이언트 PlayerPrefs 조작만으로 우회할 수 있다. 기존 가짜 코드 흐름은 제거한다.

## 범위

### 포함

- KORION TRON 주소 기반 대상 사용자·FCM 디바이스 확인
- 10분 만료 일회용 챌린지와 요청 상태 저장
- KORION 내부 요청 생성·상태 조회 API
- KORION 사용자용 요청 조회·승인·거절 API
- 푸시 탭 후 기존 앱 위에 표시되는 승인 팝업
- 로컬 보안 저장소의 mnemonic으로 TRON 주소를 재파생하고 챌린지 서명
- NYAON 요청 생성·상태 조회·연동 해제 API
- NYAON 계정별 KORION 링크와 주소 전역 유일성
- 두 계정 연동 보상: `DIAMOND=300`, `SILVER_KEY=1`, `GOLD_KEY=1`, 생애 최초 1회
- Unity의 현재 설정/계정 팝업을 API 상태 기반으로 교체
- 감사 로그, 메트릭, 만료·중복·오류 처리

### 제외

- KORION WALLET의 메뉴·내비게이션 재설계
- ETH, Polygon, BTC 주소 연동
- KORION 잔액이나 NFT 보유량의 NYAON 표시
- 지갑 개인키·mnemonic의 서버 전송 또는 저장
- NYAON이 KORION 운영 DB를 직접 조회하는 방식

## 신뢰 경계와 소유권

- Cognito는 NYAON `account_id`의 인증 소유자다.
- KORION JWT는 승인 요청을 보는 KORION `user_id`의 인증 소유자다.
- KORION DB는 KORION 주소→사용자→FCM 디바이스 매핑의 소유자다.
- KORION API는 챌린지 생성과 TRON 서명 검증의 소유자다.
- NYAON DB는 Cognito 계정과 검증된 KORION 주소의 연결 및 연동 보상 원장의 소유자다.
- Unity `PlayerPrefs`와 KORION 프론트 로컬 상태는 표시 캐시일 뿐 연결·보상 권한이 아니다.
- 서비스 간 인증은 HTTPS와 `X-Internal-Api-Key`를 사용한다. 실제 키는 런타임 Secret에만 두고 저장소에는 변수명과 placeholder만 둔다.

## 전체 흐름

1. Unity 사용자는 Google 로그인된 상태에서 KORION TRON 주소를 입력한다.
2. Unity가 `POST /api/v1/me/wallet-links/korion/requests`를 호출한다.
3. NYAON은 계정별 요청 속도·중복을 검사하고 로컬 요청 UUID를 만든다.
4. NYAON이 KORION 내부 요청 생성 API에 `requestId`, 정규화 주소, 만료 시간을 전송한다.
5. KORION은 주소가 정확히 한 KORION 사용자에게 귀속되는지 확인하고 요청·챌린지를 저장한다.
6. KORION은 해당 사용자의 활성 FCM 기기에 `type=NAYON_WALLET_LINK`, `requestId` 데이터를 보낸다.
7. 사용자가 푸시를 누르면 KORION WALLET가 요청을 조회하고 NYAON 연동 승인 팝업을 표시한다.
8. 확인을 누르면 앱이 보안 저장소의 mnemonic으로 TRON 지갑을 재파생한다. 입력 주소와 다르면 서명하지 않는다.
9. 앱이 챌린지에 서명해 KORION 승인 API로 보낸다. mnemonic과 개인키는 기기 밖으로 나가지 않는다.
10. KORION은 `RecoverySignatureVerifier.verifyTronSignature`로 검증하고 요청을 `APPROVED`로 원자 전이한다.
11. Unity는 NYAON 상태 API를 2초 간격으로 조회한다.
12. NYAON은 KORION 내부 상태가 `APPROVED`이면 같은 트랜잭션에서 요청을 완료하고 계정 링크를 저장한다.
13. Unity는 서버 응답의 `linked=true`만 보고 연동 완료 UI를 표시한다.
14. 연동 보상은 별도 `POST /api/v1/me/account-link-reward/claim`에서 서버가 두 연결 상태를 다시 확인한 후 1회 지급한다.

## 챌린지 계약

KORION이 다음 형식의 UTF-8 문자열을 생성한다.

```text
NYAON HUNTERS Wallet Link
Request: <uuid>
Wallet: <base58-tron-address>
Nonce: <32-byte-base64url>
ExpiresAt: <UTC ISO-8601>
```

- nonce는 암호학적 난수다.
- 요청당 챌린지는 하나이며 변경하지 않는다.
- 서명은 TRON 주소의 secp256k1 키로 prefixed-message 서명한다.
- 성공·거절·만료 후 재사용할 수 없다.
- 실패 서명은 최대 5회까지만 허용한다.

## 데이터 모델

### NYAON DB V7

#### `korion_wallet_link_requests`

- `id uuid primary key`
- `account_id uuid not null references player_accounts(id)`
- `address varchar(64) not null`
- `status varchar(16) not null`: `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `FAILED`
- `expires_at timestamptz not null`
- `failure_code varchar(64)` nullable
- `created_at`, `updated_at`, `completed_at`
- 한 계정당 활성 `PENDING` 요청은 하나다.

#### `player_korion_wallet_links`

- `account_id uuid primary key references player_accounts(id)`
- `address varchar(64) not null unique`
- `verified_request_id uuid not null unique references korion_wallet_link_requests(id)`
- `verified_at`, `created_at`, `updated_at`

#### `player_account_link_rewards`

- `id uuid not null unique`
- `account_id uuid primary key references player_accounts(id)`
- `reward_claimed boolean not null default false`
- `reward_claimed_at timestamptz`
- check constraint로 수령 여부와 시각을 일치시킨다.

경제 원장은 `reason_code=ACCOUNT_LINK_REWARD`, `reference_type=PLAYER_ACCOUNT_LINK_REWARD`, 안정적인 보상 행 UUID와 요청 멱키를 기록한다.

### KORION DB V179

#### `nayon_wallet_link_requests`

- `id uuid primary key`: NYAON 요청 UUID
- `user_id bigint not null references users(id)`
- `address varchar(64) not null`
- `chain varchar(16) not null check (chain='TRON')`
- `challenge text not null`
- `status varchar(16) not null`: `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`
- `attempt_count smallint not null default 0 check (0..5)`
- `expires_at`, `approved_at`, `rejected_at`, `created_at`, `updated_at`
- 동일 주소의 활성 `PENDING` 요청은 하나다.

원문 서명, 개인키, mnemonic은 저장하지 않는다. 승인 감사에는 요청 ID·사용자 ID·주소·결과·시각만 남긴다.

## API 계약

### NYAON 공개 API

- `GET /api/v1/me/wallet-links/korion`
  - 현재 링크와 진행 중 요청 상태를 반환한다.
- `POST /api/v1/me/wallet-links/korion/requests`
  - 본문 `{ "address": "T..." }`, 성공 `202`.
  - 동일 계정·주소의 진행 중 요청은 같은 요청을 반환한다.
- `GET /api/v1/me/wallet-links/korion/requests/{requestId}`
  - 현재 계정 소유 요청만 조회한다.
  - 조회 시 KORION 내부 상태를 확인하고 승인 결과를 링크로 원자 반영한다.
- `DELETE /api/v1/me/wallet-links/korion`
  - NYAON 링크만 해제한다. 이미 받은 보상은 되돌리지 않는다.
- `POST /api/v1/me/account-link-reward/claim`
  - `Idempotency-Key` 필수.
  - Google identity와 KORION 링크를 다시 확인하고 세 자산을 한 트랜잭션에서 최초 1회 지급한다.

### KORION 내부 API

- `POST /api/v1/internal/nayon/wallet-link-requests`
  - 내부 키 필수, `requestId`, `address` 수신.
  - KORION이 10분 만료 시각을 생성하고 응답의 `expiresAt`으로 반환한다. NYAON의 생성 전 로컬 만료 시각은 전송 결과가 불명확할 때만 쓰는 임시값이며, 원격 조회 결과가 최종 권위다.
  - 대상이 없으면 `404`; 활성 FCM 발송기 또는 등록 토큰이 없으면 요청은 만들되 `pushTargetAvailable=false`를 반환한다. 이 필드는 실제 단말 전달 성공을 의미하지 않는다.
- `GET /api/v1/internal/nayon/wallet-link-requests/{requestId}`
  - 내부 키 필수, 상태와 검증 주소만 반환한다.

### KORION 사용자 API

- `GET /api/v1/wallets/nayon-link-requests/{requestId}`
  - JWT 사용자 소유 요청만 챌린지와 만료 시각을 반환한다.
- `POST /api/v1/wallets/nayon-link-requests/{requestId}/approve`
  - 본문 `{ "signature": "..." }`.
  - 만료·소유자·주소·시도 횟수·서명을 검증해 `APPROVED`로 전이한다.
- `POST /api/v1/wallets/nayon-link-requests/{requestId}/reject`
  - 사용자가 팝업을 거절한 상태를 기록한다.

## 오류·경쟁조건 처리

- 유효하지 않은 TRON 주소는 양쪽 API가 모두 거절한다.
- 다른 KORION 사용자에게 속한 주소나 여러 사용자에게 모호하게 귀속된 주소는 요청을 만들지 않는다.
- 같은 KORION 주소는 NYAON의 두 계정에 연결할 수 없다.
- 승인과 거절은 `PENDING` 조건부 UPDATE로 한 번만 성공한다.
- NYAON 폴링은 다른 계정의 요청 ID를 조회할 수 없다.
- KORION 내부 API 장애 시 NYAON 요청은 `PENDING`으로 위장하지 않고 `FAILED` 또는 재시도 가능한 명시 오류를 반환한다.
- FCM 실패는 요청 자체를 삭제하지 않는다. Unity는 사용자에게 KORION 앱의 푸시 설정을 확인하도록 안내하고 재요청할 수 있다.
- 만료 요청은 조회 시 `EXPIRED`로 전이한다.
- 계정당 1분 3회, 주소당 10분 5회의 요청 제한을 둔다.
- 링크 해제와 승인 완료가 경쟁하면 NYAON 계정 행 잠금 아래 마지막 명시 사용자 동작을 보존한다.

## Unity 흐름

- 현재 계정 탭·팝업·라벨·프리팹 구조를 유지한다.
- `코드보내기` 동작은 `인증 요청하기`가 되며 주소를 서버에 보낸다.
- 6자리 코드 입력과 아무 코드 승인 로직을 제거한다.
- 요청 후 `KORION WALLET에서 알림을 눌러 승인해주세요`와 서버 만료 시간을 표시한다.
- 2초 폴링은 팝업 닫기, 성공, 거절, 만료, 로그인 해제 시 중지한다.
- 성공 전에는 `KorionWallet_Linked`를 쓰지 않는다. 표시 상태는 API 응답을 로컬 캐시로 반영한다.
- 연동 보상은 서버 claim 응답의 경제 스냅샷만 적용한다.

## KORION WALLET 흐름

- 푸시 data의 `type=NAYON_WALLET_LINK`, `requestId`만 신뢰 가능한 형식으로 파싱한다.
- 푸시 탭에서만 승인 팝업을 연다. 일반 알림 목록이나 메뉴에 새 항목을 추가하지 않는다.
- 팝업은 요청 서비스명, 대상 주소, 만료 시각을 표시한다.
- 확인 시 secure storage의 mnemonic을 읽고 TRON 주소를 재파생한다.
- 파생 주소가 요청 주소와 다르면 서명하지 않고 복구 문구 확인 안내를 표시한다.
- 생체/PIN 재인증은 기존 월렛 인증 컴포넌트를 재사용할 수 있을 때만 연결하며, 이번 단계의 서버 불변식은 유효한 개인키 서명이다.

## 관측성

- NYAON: 요청 생성·KORION 호출 실패·승인 반영·주소 충돌·보상 성공/중복/거절 카운터.
- KORION: 주소 해석 실패·FCM 대상 없음·FCM 발송·승인·거절·만료·서명 실패 카운터.
- 로그는 요청 ID, NYAON account public ID 또는 내부 account UUID, KORION user ID, 마스킹 주소, 상태 코드만 기록한다.
- 서명·nonce·내부 키·JWT·FCM 토큰·mnemonic은 로그에 남기지 않는다.

## 배포 순서와 롤백

1. `coin_system_flyway` V179 적용.
2. KORION API 배포. 기존 앱은 새 경로를 호출하지 않아 영향이 없다.
3. KORION WALLET 프론트 배포.
4. `nayon_cloud` V7 적용.
5. NYAON API 배포 및 내부 통신 smoke test.
6. Unity 클라이언트 배포.

롤백은 Unity → NYAON API → KORION 프론트 → KORION API 순으로 기능 호출을 제거한다. 요청·링크·보상 원장 데이터는 감사와 중복 지급 방지를 위해 운영 롤백에서 삭제하지 않는다. 테이블 삭제용 down migration은 비운영 검증에만 사용한다.

## 테스트 기준

- 마이그레이션 forward/rollback, 제약조건, 주소 전역 유일성.
- KORION 주소가 정확히 한 사용자에게 해석되는지 검증.
- 내부 키 누락/오류 거절.
- 동일 요청 생성 멱등성과 활성 요청 제한.
- 다른 사용자의 요청 조회·승인 거절.
- 유효/무효/재사용/만료 TRON 서명.
- 5회 실패 후 차단.
- FCM data에 정확한 type/requestId 포함.
- NYAON 요청의 계정 격리, 승인 반영, 주소 충돌.
- Google+KORION 조건 충족 전 보상 거절.
- 동일/상이한 멱키와 동시 claim에서 세 자산 각 1회 지급.
- Unity 성공 전 로컬 linked 금지, 상태별 폴링 종료, 서버 경제 스냅샷 적용.
- KORION 프론트 푸시 payload 파싱, 주소 재파생 불일치 차단, 승인/거절 API 호출.
