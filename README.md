# nayon_api

Spring Boot API for NYAON HUNTERS social accounts and cloud saves.

## Requirements

- Java 21
- PostgreSQL 16 with `nayon_cloud` migrations applied
- Cognito access tokens containing:
  - `sub`: stable Cognito subject
  - `nayon:provider`: trusted `GOOGLE` or `APPLE` claim
  - `token_use=access`
  - `client_id`: configured Unity app client ID

The API does not infer the provider from email or username. Configure Cognito token customization to emit `nayon:provider`; tokens without it are rejected.

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

All game endpoints require a Cognito Bearer access token. `GET /actuator/health` and `GET /actuator/info` are public.
