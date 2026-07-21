#!/usr/bin/env bash
# Локальная публикация в GitLab: Generic Package Registry + Release.
#
# Требуемые переменные:
#   GITLAB_TOKEN       — Personal/Project Access Token (api, write_package_registry, write_repository)
#   GITLAB_PROJECT_ID  — числовой ID проекта (Settings → General)
#
# Опционально:
#   GITLAB_HOST        — хост без схемы (default: gitlab.com)
#   GITLAB_API         — полный API URL (default: https://$GITLAB_HOST/api/v4)
#   GITLAB_REF         — ветка/коммит для создания тега, если тега ещё нет (default: HEAD)
#   PACKAGE_REGISTRY_NAME — имя generic-пакета (default: geoworker)
#   SKIP_EXTRA_ASSETS  — если 1, не грузить отдельно AAR / XCFramework.zip
#
# Usage:
#   ./scripts/publish-gitlab-release.sh [VERSION]
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

TOKEN="${GITLAB_TOKEN:-${PRIVATE_TOKEN:-}}"
PROJECT_ID="${GITLAB_PROJECT_ID:-${CI_PROJECT_ID:-}}"
HOST="${GITLAB_HOST:-${CI_SERVER_HOST:-gitlab.com}}"
API="${GITLAB_API:-${CI_API_V4_URL:-https://${HOST}/api/v4}}"
REF="${GITLAB_REF:-}"
PKG_NAME="${PACKAGE_REGISTRY_NAME:-geoworker}"

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: задайте GITLAB_TOKEN (или PRIVATE_TOKEN)" >&2
  exit 1
fi
if [[ -z "$PROJECT_ID" ]]; then
  echo "ERROR: задайте GITLAB_PROJECT_ID" >&2
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

auth_header() {
  echo "PRIVATE-TOKEN: ${TOKEN}"
}

upload_generic() {
  local file_path="$1"
  local remote_name="$2"
  local url="${API}/projects/${PROJECT_ID}/packages/generic/${PKG_NAME}/${VERSION}/${remote_name}"
  echo "  ↑ ${remote_name}"
  curl --fail --silent --show-error \
    --header "$(auth_header)" \
    --upload-file "${file_path}" \
    "${url}" >/dev/null
  echo "${url}"
}

echo "==> GitLab publish @transline/geoworker@${VERSION}"
echo "    project: ${PROJECT_ID} @ ${HOST}"
echo "    tag:     ${TAG} (ref: ${REF})"

PACKAGE_URL="$(upload_generic "$TGZ" "transline-geoworker-${VERSION}.tgz")"

LINKS_JSON="[{\"name\":\"transline-geoworker-${VERSION}.tgz\",\"url\":\"${PACKAGE_URL}\",\"link_type\":\"package\"}"

if [[ "${SKIP_EXTRA_ASSETS:-0}" != "1" ]]; then
  SHARED_AAR="$ROOT/app/shared/build/outputs/aar/shared.aar"
  CORE_AAR="$ROOT/core/build/outputs/aar/core.aar"
  XCF_DIR="$ROOT/app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework"
  if [[ ! -d "$XCF_DIR" ]]; then
    XCF_DIR="$ROOT/app/shared/build/XCFrameworks/SharedLocationTracker.xcframework"
  fi

  if [[ -f "$SHARED_AAR" ]]; then
    U="$(upload_generic "$SHARED_AAR" "geoworker-shared.aar")"
    LINKS_JSON+=",{\"name\":\"geoworker-shared.aar\",\"url\":\"${U}\",\"link_type\":\"package\"}"
  fi
  if [[ -f "$CORE_AAR" ]]; then
    U="$(upload_generic "$CORE_AAR" "geoworker-core.aar")"
    LINKS_JSON+=",{\"name\":\"geoworker-core.aar\",\"url\":\"${U}\",\"link_type\":\"package\"}"
  fi
  if [[ -d "$XCF_DIR" ]]; then
    XCF_ZIP="$ROOT/dist/SharedLocationTracker.xcframework.zip"
    rm -f "$XCF_ZIP"
    (
      cd "$(dirname "$XCF_DIR")"
      zip -qry "$XCF_ZIP" "$(basename "$XCF_DIR")"
    )
    U="$(upload_generic "$XCF_ZIP" "SharedLocationTracker.xcframework.zip")"
    LINKS_JSON+=",{\"name\":\"SharedLocationTracker.xcframework.zip\",\"url\":\"${U}\",\"link_type\":\"package\"}"
  fi
fi

LINKS_JSON+="]"

DESC="npm package @transline/geoworker ${VERSION} (JS + AAR + XCFramework). Install: npm i ${PACKAGE_URL}"

# Уже есть Release? — обновим links через delete+create неудобно; пробуем create, иначе update.
HTTP_CODE="$(curl --silent --output /tmp/gl-release-create.json --write-out '%{http_code}' \
  --header "$(auth_header)" \
  --header "Content-Type: application/json" \
  --data "$(node -e "
    const links = ${LINKS_JSON};
    console.log(JSON.stringify({
      name: 'TranslineGeoWorker ${TAG}',
      tag_name: '${TAG}',
      ref: '${REF}',
      description: process.env.DESC,
      assets: { links }
    }));
  " DESC="$DESC")" \
  "${API}/projects/${PROJECT_ID}/releases" || true)"

if [[ "$HTTP_CODE" == "201" ]]; then
  echo "==> Release ${TAG} создан"
elif [[ "$HTTP_CODE" == "409" ]] || grep -qi 'already been taken\|already exists' /tmp/gl-release-create.json 2>/dev/null; then
  echo "==> Release ${TAG} уже есть — обновляю описание и assets links"
  # GitLab: PUT /releases/:tag_name не всегда принимает links; добавим links отдельно
  curl --fail --silent --show-error \
    --header "$(auth_header)" \
    --header "Content-Type: application/json" \
    --request PUT \
    --data "$(node -e "console.log(JSON.stringify({ description: process.env.DESC, name: 'TranslineGeoWorker ${TAG}' }))" DESC="$DESC")" \
    "${API}/projects/${PROJECT_ID}/releases/${TAG}" >/dev/null || true

  node -e "
    const links = ${LINKS_JSON};
    for (const link of links) {
      console.log(JSON.stringify(link));
    }
  " | while IFS= read -r link; do
    curl --fail --silent --show-error \
      --header "$(auth_header)" \
      --header "Content-Type: application/json" \
      --data "$link" \
      "${API}/projects/${PROJECT_ID}/releases/${TAG}/assets/links" >/dev/null \
      || echo "    (link уже есть или не добавлен: $link)"
  done
  echo "==> Release ${TAG} обновлён"
else
  echo "ERROR: не удалось создать Release (HTTP ${HTTP_CODE})" >&2
  cat /tmp/gl-release-create.json >&2 || true
  exit 1
fi

echo ""
echo "$(tput setaf 2 2>/dev/null || true)✓ Опубликовано$(tput sgr0 2>/dev/null || true)"
echo "  Package: ${PACKAGE_URL}"
echo "  Release: https://${HOST}/-/releases/${TAG}  (откройте Releases в проекте)"
echo ""
echo "  npm i ${PACKAGE_URL}"
