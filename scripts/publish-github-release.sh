#!/usr/bin/env bash
# Локальная/CI публикация в GitHub Release.
#
# Требуемые переменные:
#   GITHUB_TOKEN  — token с правами repo (contents: write)
#   GITHUB_OWNER  — владелец репозитория (org/user)
#   GITHUB_REPO   — имя репозитория
#
# Опционально:
#   GITHUB_REF    — ref для создания тега, если его нет (default: текущая ветка)
#   PACKAGE_REGISTRY_NAME — префикс в имени asset (default: geoworker)
#
# Usage:
#   ./scripts/publish-github-release.sh [VERSION]
#   make release VERSION=0.1.0
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(node -p "require('./package.json').version")"
fi
VERSION="${VERSION#v}"
TAG="v${VERSION}"

TOKEN="${GITHUB_TOKEN:-}"
OWNER="${GITHUB_OWNER:-}"
REPO="${GITHUB_REPO:-}"
REF="${GITHUB_REF:-}"
PKG_NAME="${PACKAGE_REGISTRY_NAME:-geoworker}"

if [[ -z "$OWNER" || -z "$REPO" ]]; then
  if [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
    OWNER="${OWNER:-${GITHUB_REPOSITORY%/*}}"
    REPO="${REPO:-${GITHUB_REPOSITORY#*/}}"
  fi
fi

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: задайте GITHUB_TOKEN" >&2
  exit 1
fi
if [[ -z "$OWNER" || -z "$REPO" ]]; then
  echo "ERROR: задайте GITHUB_OWNER и GITHUB_REPO (или GITHUB_REPOSITORY=owner/repo)" >&2
  exit 1
fi

if [[ -z "$REF" ]]; then
  REF="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
  if [[ "$REF" == "HEAD" ]]; then
    REF="$(git rev-parse HEAD)"
  fi
fi

TGZ="$ROOT/dist/transline-geoworker-${VERSION}.tgz"
if [[ ! -f "$TGZ" ]]; then
  echo "ERROR: нет $TGZ — сначала: make pack-npm VERSION=${VERSION}" >&2
  exit 1
fi

api() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local http_code response_file
  response_file="$(mktemp)"
  if [[ -n "$body" ]]; then
    http_code="$(curl --silent --show-error -o "$response_file" -w '%{http_code}' \
      -X "$method" \
      -H "Accept: application/vnd.github+json" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      -H "Content-Type: application/json" \
      "$url" \
      -d "$body")"
  else
    http_code="$(curl --silent --show-error -o "$response_file" -w '%{http_code}' \
      -X "$method" \
      -H "Accept: application/vnd.github+json" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "$url")"
  fi
  local resp
  resp="$(cat "$response_file")"
  rm -f "$response_file"
  if [[ "$http_code" -ge 200 && "$http_code" -lt 300 ]]; then
    echo "$resp"
  else
    echo "ERROR: HTTP ${http_code} — ${url}" >&2
    echo "$resp" >&2
    return 1
  fi
}

echo "==> GitHub publish @transline/geoworker@${VERSION}"
echo "    repo: ${OWNER}/${REPO}"
echo "    tag:  ${TAG} (ref: ${REF})"

RELEASE_JSON=""
if RELEASE_JSON="$(api GET "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${TAG}" 2>/dev/null)"; then
  echo "==> Release ${TAG} уже есть, обновляю assets"
else
  TAG_EXISTS=$(curl --silent -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${OWNER}/${REPO}/git/refs/tags/${TAG}")

  if [[ "$TAG_EXISTS" == "200" ]]; then
    CREATE_PAYLOAD="$(TAG="$TAG" PKG_NAME="$PKG_NAME" node -e "
      console.log(JSON.stringify({
        tag_name: process.env.TAG,
        name: 'TranslineGeoWorker ' + process.env.TAG,
        draft: false,
        prerelease: false,
        generate_release_notes: false,
        body: 'Release ' + process.env.TAG + ' (' + process.env.PKG_NAME + ')'
      }));
    ")"
  else
    CREATE_PAYLOAD="$(TAG="$TAG" REF="$REF" PKG_NAME="$PKG_NAME" node -e "
      console.log(JSON.stringify({
        tag_name: process.env.TAG,
        target_commitish: process.env.REF,
        name: 'TranslineGeoWorker ' + process.env.TAG,
        draft: false,
        prerelease: false,
        generate_release_notes: false,
        body: 'Release ' + process.env.TAG + ' (' + process.env.PKG_NAME + ')'
      }));
    ")"
  fi

  RELEASE_JSON="$(api POST "https://api.github.com/repos/${OWNER}/${REPO}/releases" "$CREATE_PAYLOAD")"
  echo "==> Release ${TAG} создан"
fi

RELEASE_ID="$(node -e "const j=JSON.parse(process.argv[1]); process.stdout.write(String(j.id));" "$RELEASE_JSON")"
UPLOAD_URL="$(node -e "const j=JSON.parse(process.argv[1]); process.stdout.write(String(j.upload_url || '').replace(/\{.*$/, ''));" "$RELEASE_JSON")"

if [[ -z "$RELEASE_ID" || -z "$UPLOAD_URL" ]]; then
  echo "ERROR: не удалось получить release id/upload_url" >&2
  exit 1
fi

delete_asset_if_exists() {
  local name="$1"
  local assets_json
  assets_json="$(api GET "https://api.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets")"
  node -e "
    const arr = JSON.parse(process.argv[1]);
    const want = process.argv[2];
    for (const a of arr) {
      if (a.name === want) console.log(a.id);
    }
  " "$assets_json" "$name" | while IFS= read -r asset_id; do
    [[ -n "$asset_id" ]] || continue
    api DELETE "https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${asset_id}" >/dev/null
  done
}

upload_asset() {
  local file_path="$1"
  local name="$2"
  echo "  ↑ ${name}"
  delete_asset_if_exists "$name"
  curl --fail --silent --show-error \
    -X POST \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/octet-stream" \
    "${UPLOAD_URL}?name=${name}" \
    --data-binary "@${file_path}" >/dev/null
}

upload_asset "$TGZ" "transline-geoworker-${VERSION}.tgz"

SHARED_AAR="$ROOT/app/shared/build/outputs/aar/shared.aar"
CORE_AAR="$ROOT/core/build/outputs/aar/core.aar"
XCF_DIR="$ROOT/app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework"
if [[ ! -d "$XCF_DIR" ]]; then
  XCF_DIR="$ROOT/app/shared/build/XCFrameworks/SharedLocationTracker.xcframework"
fi

if [[ -f "$SHARED_AAR" ]]; then
  upload_asset "$SHARED_AAR" "geoworker-shared.aar"
fi
if [[ -f "$CORE_AAR" ]]; then
  upload_asset "$CORE_AAR" "geoworker-core.aar"
fi
if [[ -d "$XCF_DIR" ]]; then
  XCF_ZIP="$ROOT/dist/SharedLocationTracker.xcframework.zip"
  rm -f "$XCF_ZIP"
  (
    cd "$(dirname "$XCF_DIR")"
    zip -qry "$XCF_ZIP" "$(basename "$XCF_DIR")"
  )
  upload_asset "$XCF_ZIP" "SharedLocationTracker.xcframework.zip"
fi

echo ""
echo "$(tput setaf 2 2>/dev/null || true)✓ Опубликовано$(tput sgr0 2>/dev/null || true)"
echo "  Release: https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
echo ""
