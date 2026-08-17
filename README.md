# nayon_api

Spring Boot API for NYAON HUNTERS social accounts and cloud saves.

## Requirements

- Java 21
- PostgreSQL 16 with `nayon_cloud` migrations applied
- Cognito access tokens containing:
  - `sub`: stable Cognito subject
  - `token_use=access`
  - `client_id`: configured Unity app client ID

Set `NAYON_AUTH_PROVIDER=GOOGLE` for the dedicated Google Cognito app client. The API accepts a trusted `nayon:provider` token claim when present and otherwise uses this app-client-bound provider setting. It never infers the provider from email or username.

## Configuration

Copy `.env.example` values into the runtime secret/config system. Do not put real values in git.

## Build and test

```bash
GRADLE_USER_HOME=/home/ubuntu/work/.gradle-cache ./gradlew clean test build
```

Run the real PostgreSQL integration check:

```bash
scripts/verify-postgres-integration.sh
```

## API contract

The source contract is `src/main/resources/openapi/nayon-api-v1.yaml`.

- `GET /api/v1/me`
- `PATCH /api/v1/me`
- `GET /api/v1/save`
- `PUT /api/v1/save`
- `POST /api/v1/save/import`
- `POST /api/v1/offline-battles/sync`
- `POST /api/v1/offline-battles`
- `GET /api/v1/store/catalog?platform=GOOGLE_PLAY`
- `POST /api/v1/store/purchases/google-play/verify`

All game endpoints require a Cognito Bearer access token. `GET /actuator/health` and `GET /actuator/info` are public.

## Google Play one-time products

The API trusts only Google ProductPurchaseV2 verification. The client does not send a
price or reward amount, and ledger credit is keyed by the durable purchase receipt.
Localized display prices must come from Google Play Billing.

Runtime secrets/configuration:

- `STORE_ACCOUNT_HASH_KEY`: random secret used to bind BillingFlow purchases to one NYAON account
- `GOOGLE_PLAY_PACKAGE_NAME`: Android package (`com.korion.Nayon`)
- `GOOGLE_PLAY_CREDENTIALS_HOST_FILE`: host path mounted read-only into the API container

Keep the service-account JSON in Secrets Manager/runtime storage only. See
`docs/google-play-store-setup.md` for Play Console products and DB activation order.
