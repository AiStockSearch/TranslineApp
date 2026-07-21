/**
 * Autolinking — лежит в корне npm-пакета после pack-npm.sh
 *
 * iOS: RN CLI findPodspec() ищет *.podspec только в корне пакета.
 */
module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: './android',
        packageImportPath: 'import org.transline.geoworker.GeoWorkerPackage;',
        packageInstance: 'new GeoWorkerPackage()',
      },
      ios: {},
    },
  },
};
