package com.eddyslarez.kmpsiprtc.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.eddyslarez.kmpsiprtc.platform.AndroidContext


actual class PlatformRegistration {
    private val app: Application by lazy {
        AndroidContext.getApplication()
    }

    actual fun setupNotificationObservers(listener: AppLifecycleListener) {
        try {
            // FinishedLaunching
            listener.onEvent(AppLifecycleEvent.FinishedLaunching)

            // Foreground / Background
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = listener.onEvent(AppLifecycleEvent.EnterForeground)
                override fun onStop(owner: LifecycleOwner)  = listener.onEvent(AppLifecycleEvent.EnterBackground)
            })

            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var activityCount = 0

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    activityCount++
                }

                override fun onActivityDestroyed(activity: Activity) {
                    activityCount--
                    if (activityCount == 0) {
                        listener.onEvent(AppLifecycleEvent.WillTerminate)
                    }
                }

                override fun onActivityStarted(a: Activity){}
                override fun onActivityResumed(a: Activity){}
                override fun onActivityPaused(a: Activity){}
                override fun onActivityStopped(a: Activity){}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle){}
            })
        } catch (e: Exception) {
            // Log error si AndroidContext no está inicializado
            e.printStackTrace()
        }
    }
}