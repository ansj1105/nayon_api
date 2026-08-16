#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose_file="$repo_root/docker-compose.prod.yml"
env_file="$repo_root/.env"
cloud_root="$(cd "$repo_root/../nayon_cloud" 2>/dev/null && pwd || true)"

if [[ ! -f "$env_file" ]]; then
  echo "missing runtime config: $env_file" >&2
  exit 1
fi
if [[ -z "$cloud_root" || ! -f "$cloud_root/Dockerfile" ]]; then
  echo "nayon_cloud must be checked out beside nayon_api" >&2
  exit 1
fi

image_tag="$(git -C "$repo_root" rev-parse --short=12 HEAD)"
migration_tag="$(git -C "$cloud_root" rev-parse --short=12 HEAD)"
compose=(sudo --preserve-env=IMAGE_TAG,MIGRATION_TAG docker compose --env-file "$env_file" -f "$compose_file")

export IMAGE_TAG="$image_tag"
export MIGRATION_TAG="$migration_tag"

"${compose[@]}" build flyway api
"${compose[@]}" up -d postgres
"${compose[@]}" run --rm flyway
"${compose[@]}" up -d --no-deps api

for _ in {1..36}; do
  if curl --fail --silent http://127.0.0.1:3200/actuator/health >/dev/null; then
    sudo docker tag "nayon-api:$image_tag" nayon-api:current
    "${compose[@]}" ps
    exit 0
  fi
  sleep 5
done

"${compose[@]}" logs --tail=100 api >&2
exit 1
