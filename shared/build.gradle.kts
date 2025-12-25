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
version = "1.0.0"

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
            }
        }

        // Desktop
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
                implementation("io.ktor:ktor-client-okhttp:3.3.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

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

// ✅ CAMBIO: De "kspDesktop" a "kspJvm" estándar
dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.8.2")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.2")
    add("kspIosX64", "androidx.room:room-compiler:2.8.2")
    add("kspIosArm64", "androidx.room:room-compiler:2.8.2")
    add("kspDesktop", "androidx.room:room-compiler:2.8.2")
}
// CONFIGURACIÓN PARA JITPACK
// JitPack automáticamente publica todos los targets de KMP
// No necesitas configurar publishing manualmente