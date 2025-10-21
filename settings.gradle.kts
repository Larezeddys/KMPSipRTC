enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
//        maven { url =uri("https://raw.githubusercontent.com/alexgreench/google-webrtc/master" )}

    }
}

rootProject.name = "KMPSipRTC"
include(":shared")