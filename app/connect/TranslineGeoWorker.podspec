# frozen_string_literal: true

Pod::Spec.new do |s|
  s.name             = 'TranslineGeoWorker'
  s.version          = '0.1.0'
  s.summary          = 'KMP location tracker + React Native bridge for Transline'
  s.homepage         = 'https://gitlab.example.com/transline/TranslineGeoWorker'
  s.license          = { :type => 'UNLICENSED' }
  s.author           = { 'Transline' => 'dev@transline.local' }
  s.platforms        = { :ios => '14.0' }
  s.source           = { :path => '.' }

  # Путь относительно app/connect → iosApp + XCFramework
  s.source_files = [
    '../iosApp/iosApp/LocationTrackerModule.swift',
    '../iosApp/iosApp/LocationTrackerModule.m',
    '../iosApp/iosApp/IOSNetworkChecker.swift',
    '../iosApp/iosApp/IOSNotificationHelper.swift',
  ]

  s.vendored_frameworks = '../shared/build/XCFrameworks/release/SharedLocationTracker.xcframework'

  s.dependency 'React-Core'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule',
  }

  s.frameworks = 'CoreLocation'
end
