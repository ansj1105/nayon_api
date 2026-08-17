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
- `GET /api/v1/subscriptions/catalog?platform=GOOGLE_PLAY`
- `GET /api/v1/me/subscriptions`
- `POST /api/v1/store/subscriptions/google-play/verify`
- `POST /api/v1/public/google-play/rtdn`
- `GET /api/v1/me/level-rewards`
- `POST /api/v1/me/level-rewards/{trackCode}/{requiredLevel}/claim`
- `POST /api/v1/me/subscriptions/{planCode}/daily-reward/claim`

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

## Google Play monthly subscriptions

`MONTHLY_GROWTH` and `MONTHLY_ADVANCED` are separate one-month auto-renewing
subscriptions. Their Play product IDs, prices, benefit values, and level-reward
amounts are deployment data rather than application constants. Level rewards are
lifetime one-time claims; they do not reset when a subscription renews.

RTDN push authentication requires `GOOGLE_PLAY_RTDN_JWK_SET_URI`,
`GOOGLE_PLAY_RTDN_AUDIENCE`, and
`GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL`. See
`docs/google-play-subscription-setup.md` for the Play Console, Pub/Sub, catalog,
and license-test setup order. Keep the catalog inactive until V13 and the API
health check have passed.
