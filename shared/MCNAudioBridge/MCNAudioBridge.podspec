Pod::Spec.new do |s|
  s.name         = 'MCNAudioBridge'
  s.version      = '1.0.0'
  s.summary      = 'Audio injection bridge for WebRTC L->R translation'
  s.homepage     = 'https://github.com/example'
  s.license      = { :type => 'MIT' }
  s.author       = 'MCN'
  s.source       = { :path => '.' }
  s.ios.deployment_target = '16.0'
  s.source_files = 'Classes/**/*.{h,m}'
  s.dependency 'WebRTC-SDK', '125.6422.05'
  s.frameworks = 'AudioToolbox'
end
