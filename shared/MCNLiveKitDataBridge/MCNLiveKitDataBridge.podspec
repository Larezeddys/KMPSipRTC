Pod::Spec.new do |s|
  s.name         = 'MCNLiveKitDataBridge'
  s.version      = '1.0.0'
  s.summary      = 'Swift bridge to publish reliable LiveKit data channel messages from Kotlin/Native'
  s.homepage     = 'https://github.com/example'
  s.license      = { :type => 'MIT' }
  s.author       = 'MCN'
  s.source       = { :path => '.' }
  s.ios.deployment_target = '16.0'
  s.source_files = 'Classes/**/*.swift'
  s.dependency 'LiveKitClient', '2.0.18'
  s.swift_version = '5.0'
end
