/**
 * patch-package friendly snapshot:
 * copy needed .patch files into the host app patches/ folder if desired.
 *
 * Prefer applying via scripts/apply-geoworker-patches.js (or make apply-host-patches).
 *
 * Host package.json example:
 *   "geoworker:patch": "node ../TranslineGeoWorker/scripts/apply-geoworker-patches.js --root=../TranslineGeoWorker"
 *
 * Or: make apply-host-patches HOST=../YourRnApp
 * Or follow templates/android|ios/INTEGRATION.md for manual edits.
 */

module.exports = {
  android: [
    'patches/android/01-settings.gradle.patch',
    'patches/android/02-app-build.gradle.patch',
    'patches/android/03-MainApplication.kt.patch',
    'patches/android/04-AndroidManifest.xml.patch',
  ],
  ios: [
    'patches/ios/01-Podfile.patch',
    'patches/ios/02-Info.plist.patch',
    'patches/ios/03-AppDelegate.mm.patch',
  ],
};
