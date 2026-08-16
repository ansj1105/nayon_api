#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="$repo_root/docker-compose.prod.yml"
deploy_script="$repo_root/scripts/deploy-host.sh"

test -f "$repo_root/Dockerfile"
test -f "$compose_file"
test -f "$deploy_script"
bash -n "$deploy_script"
grep -Fq -- '--preserve-env=IMAGE_TAG,MIGRATION_TAG' "$deploy_script"

grep -Fq '127.0.0.1:3200:8080' "$compose_file"
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

echo "nayon_api deployment contract verified"
