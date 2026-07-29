# frozen_string_literal: true

require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

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
    'LocationTrackerModule.swift',
    'LocationTrackerModule.m',
    'NotifyAppModule.swift',
    'NotifyAppModule.m',
    'IOSNetworkChecker.swift',
    'IOSNotificationHelper.swift',
  ]

  s.vendored_frameworks = 'Frameworks/SharedLocationTracker.xcframework'

  s.dependency 'React-Core'
  s.frameworks = 'CoreLocation', 'UserNotifications'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule',
  }
end
