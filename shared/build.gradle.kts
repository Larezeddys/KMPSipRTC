import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import com.android.utils.osArchitecture
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
    id("maven-publish") // <- esto es necesario

    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.gradleBuildConfig)
    // Room plugins
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    // iOS targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Desktop target
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    cocoapods {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = "16.0"
        framework {
            baseName = "shared"
            isStatic = true
            // Requerido si usas NativeSQLiteDriver
            // linkerOpts.add("-lsqlite3")
        }
    }

    sourceSets {
        // Common
        val commonMain by getting {
            dependencies {
                implementation(libs.androidx.lifecycle.runtime.compose)

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.29.0")
                implementation("io.ktor:ktor-client-core:3.3.1")
                implementation("io.ktor:ktor-client-websockets:3.3.1")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation("com.squareup.okio:okio:3.9.0")

                // Room
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
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

                implementation("androidx.core:core-ktx:1.17.0")
                implementation("io.insert-koin:koin-android:4.1.1")
                implementation("io.ktor:ktor-client-okhttp:3.3.1")
                implementation("com.shepeliev:webrtc-kmp:0.125.11")

                // Room SQLite Wrapper (opcional)
                implementation(libs.androidx.room.sqlite.wrapper)
            }
        }

        // iOS
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting

        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)

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

                // Detectar arquitectura automáticamente
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
                    osName.contains("win") -> {
                        runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:windows-x86_64")
                    }
                    osName.contains("linux") -> {
                        runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.10.0:linux-x86_64")
                    }
                }
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.github.Eddyslarez88"
            artifactId = "KMPSipRTC"
            version = "1.0.0"

            afterEvaluate {
                // Publica todos los targets de KMP
                kotlin.targets.forEach { target ->
                    // Solo targets con componentes
                    target.components.find { it.name == "kotlin" }?.let { from(it) }
                }
            }
        }
    }
}

android {
    namespace = "com.eddyslarez.kmpsiprtc"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// Configuración de Room
room {
    schemaDirectory("$projectDir/schemas")
}

// Dependencias de KSP para cada plataforma
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosX64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}