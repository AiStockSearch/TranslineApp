# frozen_string_literal: true

# Root podspec for RN CLI autolinking (findPodspec scans package root only).
# After pack-npm this file lives next to package.json; in repo it lives under rn/.
require 'json'

pkg_path = File.join(__dir__, 'package.json')
pkg_path = File.join(__dir__, '..', 'package.json') unless File.exist?(pkg_path)
package = JSON.parse(File.read(pkg_path))

Pod::Spec.new do |s|
  s.name             = 'TranslineGeoWorker'
  s.version          = package['version']
  s.summary          = 'KMP geo tracker + Notify Manager + React Native bridge for Transline'
  s.homepage         = 'https://gitlab.example.com/transline/TranslineGeoWorker'
  s.license          = { :type => 'UNLICENSED' }
  s.author           = { 'Transline' => 'dev@transline.local' }
  s.platforms        = { :ios => '14.0' }
  s.source           = { :path => '.' }

  s.source_files = [
    'ios/LocationTrackerModule.swift',
    'ios/LocationTrackerModule.m',
    'ios/NotifyAppModule.swift',
    'ios/NotifyAppModule.m',
    'ios/IOSNetworkChecker.swift',
    'ios/IOSNotificationHelper.swift',
  ]

  s.vendored_frameworks = 'ios/Frameworks/SharedLocationTracker.xcframework'

  s.dependency 'React-Core'
  s.frameworks = 'CoreLocation', 'UserNotifications'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule',
  }
end
