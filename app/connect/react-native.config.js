/**
 * Опциональный autolinking для RN.
 * Если нативный код лежит внутри node_modules/@transline/geoworker —
 * раскомментируйте и поправьте пути.
 *
 * Пока мост живёт в androidApp / iosApp репозитория KMP —
 * обычно копируете файлы вручную или подключаете через Gradle/CocoaPods.
 */
module.exports = {
  dependency: {
    platforms: {
      android: {
        // sourceDir: '../node_modules/@transline/geoworker/android',
        // packageImportPath: 'import org.transline.geoworker.GeoWorkerPackage;',
        // packageInstance: 'new GeoWorkerPackage()',
      },
      ios: {
        // podspecPath: '../node_modules/@transline/geoworker/TranslineGeoWorker.podspec',
      },
    },
  },
};
