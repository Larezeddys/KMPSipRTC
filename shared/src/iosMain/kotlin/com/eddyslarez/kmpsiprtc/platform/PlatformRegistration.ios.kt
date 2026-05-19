package com.eddyslarez.kmpsiprtc.platform

import platform.Foundation.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidFinishLaunchingNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
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
        // WillEnterForegroundNotification es la inversa simétrica de DidEnterBackgroundNotification.
        // NO usar UIApplicationDidBecomeActiveNotification: se dispara también al cerrar Control
        // Center, Notification Center, alerts del sistema, Face ID prompts, CallKit dismiss y
        // retorno del app switcher sin background real — causando REGISTER innecesarios.
        nc.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) {
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
