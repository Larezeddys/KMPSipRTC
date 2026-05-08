import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21"
    id("com.android.library") version "8.11.2"
    id("org.jetbrains.compose") version "1.9.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    id("com.github.gmazzo.buildconfig") version "5.7.0"
    id("org.jetbrains.kotlin.native.cocoapods") version "2.2.21"
    id("org.jetbrains.kotlinx.atomicfu") version "0.29.0"

    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
    id("androidx.room") version "2.8.2"
    id("maven-publish")
}

group = "com.github.larezeddys"
version = "1.0.3"

// Versión de Trixnity
val trixnityVersion = "4.22.7" // Última versión disponible

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    // iOS targets
    val iosX64Target = iosX64()
    val iosArm64Target = iosArm64()
    val iosSimulatorArm64Target = iosSimulatorArm64()

    // Custom cinterop para MCNAudioBridge (bridge ObjC para inyeccion de audio)
    val xcframeworkBase = project.file("build/cocoapods/synthetic/ios/Pods/WebRTC-SDK/WebRTC.xcframework")
    val libsBase = project.file("src/nativeInterop/libs")
    listOf(iosX64Target, iosArm64Target, iosSimulatorArm64Target).forEach { target ->
        val frameworkDir = when (target.name) {
            "iosArm64" -> xcframeworkBase.resolve("ios-arm64")
            else -> xcframeworkBase.resolve("ios-arm64_x86_64-simulator")
        }
        val libDir = when (target.name) {
            "iosArm64" -> libsBase.resolve("ios-arm64")
            "iosX64" -> libsBase.resolve("ios-simulator-x86_64")
            else -> libsBase.resolve("ios-simulator-arm64")
        }
        target.compilations.getByName("main") {
            cinterops {
                create("MCNAudioBridge") {
                    defFile = project.file("src/nativeInterop/cinterop/MCNAudioBridge.def")
                    val headerDir = project.file("src/nativeInterop/cinterop")
                    compilerOpts("-I${headerDir}")
                    extraOpts("-libraryPath", libDir.absolutePath)
                }
            }
        }
    }

    // Desktop target
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    // Cocoapods config
    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "16.0"
        framework {
            baseName = "shared"
            isStatic = true
        }
        pod("WebRTC-SDK") {
            version = "125.6422.05"
            moduleName = "WebRTC"
        }
        pod("LiveKitClient") {
            version = "2.0.18"
            moduleName = "LiveKit"
        }
    }

    sourceSets {
        // Common
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.29.0")
                implementation("io.ktor:ktor-client-core:3.3.1")
                implementation("io.ktor:ktor-client-websockets:3.3.1")
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation("com.squareup.okio:okio:3.9.0")

                // Room
                implementation("androidx.room:room-runtime:2.8.2")
                implementation("androidx.sqlite:sqlite-bundled:2.6.1")

                // Trixnity - Usando el groupId correcto: net.folivo
                implementation("net.folivo:trixnity-client:$trixnityVersion")
                // Agrega otros módulos según necesites:
                 implementation("net.folivo:trixnity-clientserverapi-client:$trixnityVersion")
                 implementation("net.folivo:trixnity-olm:$trixnityVersion")
                 implementation("net.folivo:trixnity-crypto-core:$trixnityVersion")

                implementation("net.folivo:trixnity-client-media-okio:${trixnityVersion}")
                implementation("net.folivo:trixnity-client-repository-room:${trixnityVersion}")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        // Android
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.lifecycle.process)
                implementation("io.github.webrtc-sdk:android:137.7151.04")
                implementation("androidx.core:core-ktx:1.17.0")
                implementation("io.insert-koin:koin-android:4.1.1")
                implementation("io.ktor:ktor-client-okhttp:3.3.1")
                implementation("androidx.room:room-sqlite-wrapper:2.8.2")

                // LiveKit Android SDK para conferencias
                implementation("io.livekit:livekit-android:2.24.1")
                implementation("io.livekit:livekit-android-camerax:2.24.1")
            }
        }

        // iOS
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting

        val iosMain by creating {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.3.1")
                implementation("com.shepeliev:webrtc-kmp:0.125.11")

                // Motor Ktor para iOS
                // Ya lo tienes con ktor-client-darwin
            }
        }

        // Desktop
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
                implementation("io.ktor:ktor-client-okhttp:3.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
                implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
                implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")

                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                when {
                    osName.contains("mac") -> {
                        if (osArch.contains("x86_64") || osArch.contains("arm")) {
                            runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:macos-x86_64")
                        } else {
                            runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:macos-aarch64")
                        }
                    }
                    osName.contains("win") -> runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:windows-x86_64")
                    osName.contains("linux") -> runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:linux-x86_64")
                }

                // Motor Ktor para Desktop
                // Ya lo tienes con ktor-client-okhttp
            }
        }
    }
}

android {
    namespace = "com.eddyslarez.kmpsiprtc"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
    }
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Configuración de Room
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.8.2")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.2")
    add("kspIosX64", "androidx.room:room-compiler:2.8.2")
    add("kspIosArm64", "androidx.room:room-compiler:2.8.2")
    add("kspDesktop", "androidx.room:room-compiler:2.8.2")
}
