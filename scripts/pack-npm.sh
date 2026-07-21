#!/usr/bin/env bash
# Упаковка @transline/geoworker: JS + Android мост + AAR + iOS мост + XCFramework → dist/*.tgz
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  if [[ -n "${GITHUB_REF_NAME:-}" && "$GITHUB_REF_NAME" == v* ]]; then
    VERSION="${GITHUB_REF_NAME#v}"
  else
    VERSION="$(node -p "require('./package.json').version")"
  fi
fi

echo "==> Packing @transline/geoworker@$VERSION"

STAGING="$ROOT/build/npm-package"
DIST="$ROOT/dist"
rm -rf "$STAGING"
mkdir -p "$STAGING" "$DIST"

# --- JS ---
mkdir -p "$STAGING/src"
cp -R "$ROOT/src/native" "$STAGING/src/native"

# --- Android library skeleton ---
mkdir -p "$STAGING/android/libs" "$STAGING/android/src/main/java"
cp "$ROOT/rn/android/build.gradle" "$STAGING/android/build.gradle"
cp "$ROOT/rn/android/consumer-rules.pro" "$STAGING/android/consumer-rules.pro"
cp "$ROOT/rn/android/src/main/AndroidManifest.xml" "$STAGING/android/src/main/AndroidManifest.xml"

BRIDGE_SRC="$ROOT/app/androidApp/src/main/kotlin/org/transline/geoworker"
BRIDGE_DST="$STAGING/android/src/main/java/org/transline/geoworker"
mkdir -p "$BRIDGE_DST"
for f in \
  GeoWorkerPackage.kt \
  GeoWorkerRuntime.kt \
  LocationTrackerModule.kt \
  SystemBarsModule.kt \
  NotifyAppModule.kt \
  AndroidLocationProvider.kt \
  AndroidNetworkChecker.kt \
  LocationForegroundService.kt \
  BootReceiver.kt \
  LocationServiceController.kt \
  GeoNotificationHelper.kt
do
  if [[ -f "$BRIDGE_SRC/$f" ]]; then
    cp "$BRIDGE_SRC/$f" "$BRIDGE_DST/$f"
  else
    echo "WARN: missing bridge file $f" >&2
  fi
done

# --- AARs ---
echo "==> Locating AARs..."
SHARED_AAR="$ROOT/app/shared/build/outputs/aar/shared.aar"
CORE_AAR="$ROOT/core/build/outputs/aar/core.aar"

if [[ ! -f "$SHARED_AAR" ]]; then
  SHARED_AAR="$(find "$ROOT/app/shared/build/outputs" -name '*.aar' 2>/dev/null | head -n 1 || true)"
fi
if [[ ! -f "$CORE_AAR" ]]; then
  CORE_AAR="$(find "$ROOT/core/build/outputs" -name '*.aar' 2>/dev/null | head -n 1 || true)"
fi

if [[ ! -f "$SHARED_AAR" ]]; then
  echo "ERROR: shared AAR not found. Run: ./gradlew :app:shared:assembleAndroidMain" >&2
  exit 1
fi
if [[ ! -f "$CORE_AAR" ]]; then
  echo "ERROR: core AAR not found. Run: ./gradlew :core:assembleAndroidMain" >&2
  exit 1
fi

cp "$SHARED_AAR" "$STAGING/android/libs/geoworker-shared.aar"
cp "$CORE_AAR" "$STAGING/android/libs/geoworker-core.aar"
echo "    shared: $SHARED_AAR"
echo "    core:   $CORE_AAR"

# --- iOS ---
mkdir -p "$STAGING/ios/Frameworks"
# Podspec must live at package ROOT — RN CLI findPodspec() only scans *.podspec there.
cp "$ROOT/rn/TranslineGeoWorker.podspec" "$STAGING/TranslineGeoWorker.podspec"
# Keep a copy under ios/ for manual :path => '.../ios' installs
cp "$ROOT/rn/ios/TranslineGeoWorker.podspec" "$STAGING/ios/TranslineGeoWorker.podspec"
for f in \
  LocationTrackerModule.swift \
  LocationTrackerModule.m \
  NotifyAppModule.swift \
  NotifyAppModule.m \
  IOSNetworkChecker.swift \
  IOSNotificationHelper.swift
do
  if [[ -f "$ROOT/app/iosApp/iosApp/$f" ]]; then
    cp "$ROOT/app/iosApp/iosApp/$f" "$STAGING/ios/$f"
  else
    echo "WARN: missing iOS bridge file $f" >&2
  fi
done

XCF="$ROOT/app/shared/build/XCFrameworks/release/SharedLocationTracker.xcframework"
if [[ ! -d "$XCF" ]]; then
  XCF="$ROOT/app/shared/build/XCFrameworks/SharedLocationTracker.xcframework"
fi
if [[ ! -d "$XCF" ]]; then
  echo "ERROR: XCFramework not found. Run: make build-xcframework" >&2
  exit 1
fi
cp -R "$XCF" "$STAGING/ios/Frameworks/SharedLocationTracker.xcframework"
echo "    xcframework: $XCF"

# --- package.json / configs ---
cp "$ROOT/rn/react-native.config.js" "$STAGING/react-native.config.js"

node <<EOF
const fs = require('fs');
const path = require('path');
const rootPkg = require('$ROOT/package.json');
const pkg = {
  name: rootPkg.name || '@transline/geoworker',
  version: '$VERSION',
  description: rootPkg.description || 'KMP geo tracker + Notify Manager for React Native (Transline)',
  main: 'src/native/index.ts',
  'react-native': 'src/native/index.ts',
  files: [
    'src/native',
    'android',
    'ios',
    'TranslineGeoWorker.podspec',
    'react-native.config.js',
    'README.md'
  ],
  peerDependencies: rootPkg.peerDependencies,
  peerDependenciesMeta: rootPkg.peerDependenciesMeta,
  codegenConfig: {
    name: 'TranslineGeoWorkerSpec',
    type: 'modules',
    jsSrcsDir: './src/native',
    android: { javaPackageName: 'org.transline.geoworker' }
  },
  repository: rootPkg.repository || undefined,
  license: rootPkg.license || 'UNLICENSED'
};
fs.writeFileSync(
  path.join('$STAGING', 'package.json'),
  JSON.stringify(pkg, null, 2) + '\n'
);
EOF

PKG_HOST="${CI_SERVER_HOST:-gitlab.example.com}"
PKG_API="${CI_API_V4_URL:-https://${PKG_HOST}/api/v4}"
PKG_PROJECT="${CI_PROJECT_ID:-PROJECT_ID}"
PKG_URL="${PKG_API}/projects/${PKG_PROJECT}/packages/generic/geoworker/${VERSION}/transline-geoworker-${VERSION}.tgz"
DOCS_URL="${CI_PROJECT_URL:-https://${PKG_HOST}/GROUP/TranslineGeoWorker}/-/blob/main/docs/14-gitlab-releases.md"

cat > "$STAGING/README.md" <<EOF
# @transline/geoworker

Geo (LocationTracking / FGS) + Notify Manager (\`NotifyApp\`) in one npm package. Autolinking registers \`GeoWorkerPackage\`.

Install from GitLab Package Registry:

\`\`\`bash
npm i ${PKG_URL}
\`\`\`

See docs: ${DOCS_URL}
EOF

# --- npm pack ---
echo "==> npm pack"
(
  cd "$STAGING"
  npm pack --pack-destination "$DIST"
)

# Normalize tarball name for CI
TGZ="$(ls -1 "$DIST"/*"${VERSION}".tgz 2>/dev/null | head -n 1 || true)"
if [[ -z "$TGZ" ]]; then
  TGZ="$(ls -1t "$DIST"/*.tgz | head -n 1)"
fi
CANON="$DIST/transline-geoworker-${VERSION}.tgz"
if [[ "$TGZ" != "$CANON" ]]; then
  cp "$TGZ" "$CANON"
fi

echo "==> Done: $CANON"
ls -lh "$CANON"
