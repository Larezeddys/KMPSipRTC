# KMPSipRTC

Kotlin Multiplatform SIP/WebRTC library for building voice calling features across Android, Desktop/JVM, and shared KMP code. The public entry point is `KmpSipRtc`, which manages SIP registration, outgoing and incoming calls, audio routing, call logs, health diagnostics, Matrix integration, and optional LiveKit-based call routing.

## Installation with JitPack

This project is prepared for publication through [JitPack](https://jitpack.io). After the GitHub repository is public and a release/tag such as `1.0.0` exists, consumers can add JitPack to their Gradle repositories.

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Because this is a multi-module Gradle project and the library module is named `shared`, use the module coordinate:

```kotlin
dependencies {
    implementation("com.github.larezeddys.KMPSipRTC:shared:1.0.0")
}
```

If the GitHub repository name is different from `KMPSipRTC`, replace `KMPSipRTC` in the dependency coordinate with the exact repository name shown on GitHub.

## Supported Targets

- Android library target.
- Desktop/JVM target.
- iOS KMP sources and CocoaPods configuration are included in the project. JitPack builds on Linux, so verify the exact iOS artifacts produced by the JitPack build log before advertising iOS binary consumption from JitPack.

## Basic Usage

```kotlin
import com.eddyslarez.kmpsiprtc.KmpSipRtc
import com.eddyslarez.kmpsiprtc.data.models.SipConfig

val sip = KmpSipRtc.getInstance()

sip.initialize(
    config = SipConfig(
        defaultDomain = "sip.example.com",
        webSocketUrl = "wss://sip.example.com/ws",
        userAgent = "MyApp/1.0",
        enableLogs = true
    )
) { result ->
    result.onSuccess {
        sip.registerAccount(
            username = "1001",
            password = "secret",
            domain = "sip.example.com"
        )
    }

    result.onFailure { error ->
        println("KmpSipRtc initialization failed: ${error.message}")
    }
}
```

Make an outgoing call after the account is registered:

```kotlin
sip.makeCall(
    phoneNumber = "1002",
    username = "1001",
    domain = "sip.example.com"
)
```

Listen for common events:

```kotlin
sip.addSipEventListener(object : KmpSipRtc.SipEventListener {
    override fun onIncomingCall(callInfo: KmpSipRtc.IncomingCallInfo) {
        sip.acceptCurrentCall()
    }

    override fun onCallFailed(error: String, callInfo: KmpSipRtc.CallInfo?) {
        println("Call failed: $error")
    }
})
```

## Publishing a Release

1. Push this project to a public GitHub repository.
2. Create a Git tag and release, for example `1.0.0`.
3. Open [jitpack.io](https://jitpack.io), paste the GitHub repository URL, and request the `1.0.0` build.
4. Use the exact dependency coordinate that JitPack displays in the successful build page.

The `jitpack.yml` file runs:

```bash
./gradlew :shared:publishToMavenLocal --no-daemon --no-configuration-cache --stacktrace
```

## Local Verification

Before creating a release, run:

```bash
./gradlew :shared:build
./gradlew :shared:publishToMavenLocal --no-configuration-cache
```

Then test the JitPack dependency from a clean consumer project.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
