#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="$repo_root/docker-compose.prod.yml"
deploy_script="$repo_root/scripts/deploy-host.sh"
env_example="$repo_root/.env.example"
subscription_runbook="$repo_root/docs/google-play-subscription-setup.md"

test -f "$repo_root/Dockerfile"
test -f "$compose_file"
test -f "$deploy_script"
bash -n "$deploy_script"
grep -Fq -- '--preserve-env=IMAGE_TAG,MIGRATION_TAG' "$deploy_script"

grep -Fq '127.0.0.1:3200:8080' "$compose_file"
grep -Fq 'KORION_WALLET_LINK_BASE_URL: ${KORION_WALLET_LINK_BASE_URL}' "$compose_file"
grep -Fq 'KORION_WALLET_LINK_INTERNAL_API_KEY: ${KORION_WALLET_LINK_INTERNAL_API_KEY}' "$compose_file"
grep -Fq 'GOOGLE_PLAY_RTDN_JWK_SET_URI: ${GOOGLE_PLAY_RTDN_JWK_SET_URI' "$compose_file"
grep -Fq 'GOOGLE_PLAY_RTDN_AUDIENCE: ${GOOGLE_PLAY_RTDN_AUDIENCE}' "$compose_file"
grep -Fq 'GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL: ${GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL}' "$compose_file"
grep -Fq '/run/secrets/google-play-service-account.json:ro' "$compose_file"
grep -Fq 'name: coin-shared' "$compose_file"
test "$(grep -Fc 'jdbc:postgresql://nayon-postgres:5432/${DB_NAME}' "$compose_file")" -eq 2
grep -Fq '../nayon_cloud' "$compose_file"
api_block="$(sed -n '/^  api:/,/^volumes:/p' "$compose_file")"
postgres_block="$(sed -n '/^  postgres:/,/^  flyway:/p' "$compose_file")"
grep -Fq '      - nayon-ingress' <<<"$api_block"
if grep -Fq '      - nayon-ingress' <<<"$postgres_block"; then
  echo "postgres must not join the ingress network" >&2
  exit 1
fi
if grep -Eq '^[[:space:]]+ports:' <<<"$postgres_block"; then
  echo "postgres must not publish a host port" >&2
  exit 1
fi

for variable in \
  GOOGLE_PLAY_PACKAGE_NAME \
  GOOGLE_PLAY_CREDENTIALS_HOST_FILE \
  GOOGLE_PLAY_RTDN_JWK_SET_URI \
  GOOGLE_PLAY_RTDN_AUDIENCE \
  GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT_EMAIL; do
  grep -Fq "$variable=" "$env_example"
done

test -f "$subscription_runbook"
grep -Fq '/api/v1/public/google-play/rtdn' "$subscription_runbook"
grep -Fq 'MONTHLY_GROWTH' "$subscription_runbook"
grep -Fq 'MONTHLY_ADVANCED' "$subscription_runbook"

if grep -ERq --include='*.md' --include='*.example' \
  '(AIza[0-9A-Za-z_-]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC )?PRIVATE KEY-----|"private_key"[[:space:]]*:)' \
  "$repo_root/.env.example" "$repo_root/docs"; then
  echo "deployment examples must not contain real credentials" >&2
  exit 1
fi

echo "nayon_api deployment contract verified"
