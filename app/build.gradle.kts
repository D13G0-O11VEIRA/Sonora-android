plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val youtubedlAndroid = "0.18.1"

android {
    namespace = "app.sonora"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.sonora"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        // exigido pelo youtubedl-android: o Python/ffmpeg embutidos precisam ser extraídos no aparelho
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE",
                "META-INF/NOTICE.txt", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/INDEX.LIST",
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
