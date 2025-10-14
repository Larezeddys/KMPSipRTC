package com.eddyslarez.kmpsiprtc.platform

import platform.Foundation.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidFinishLaunchingNotification
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.UIKit.UIApplicationProtectedDataWillBecomeUnavailable


actual class PlatformRegistration{
    actual fun setupNotificationObservers(listener: AppLifecycleListener) {
        val nc = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue

        nc.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) {
            listener.onEvent(AppLifecycleEvent.EnterBackground)
        }
        nc.addObserverForName(UIApplicationDidFinishLaunchingNotification, null, queue) {
            listener.onEvent(AppLifecycleEvent.FinishedLaunching)
        }
        nc.addObserverForName(UIApplicationDidBecomeActiveNotification, null, queue) {
            listener.onEvent(AppLifecycleEvent.EnterForeground)
        }
        nc.addObserverForName(UIApplicationWillTerminateNotification, null, queue) {
            listener.onEvent(AppLifecycleEvent.WillTerminate)
        }
        nc.addObserverForName(UIApplicationProtectedDataDidBecomeAvailable, null, queue) {
            listener.onEvent(AppLifecycleEvent.ProtectedDataAvailable)
        }
        nc.addObserverForName(UIApplicationProtectedDataWillBecomeUnavailable, null, queue) {
            listener.onEvent(AppLifecycleEvent.ProtectedDataWillBecomeUnavailable)
        }
    }
}
