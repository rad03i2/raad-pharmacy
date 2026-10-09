plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.radwan.raadpharmacy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.radwan.raadpharmacy"
        minSdk = 26
        targetSdk = 37
        versionCode = 45
        versionName = "3.3.14"
        buildConfigField("String", "SUPABASE_URL", "\"https://gsyrjhqkbfomxqacexle.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_dEvMmAVvoihNljWjLAutRg_lkAD934q\"")
        manifestPlaceholders["debtVoicePermission"] =
            "android.permission." + "RECORD_AUDIO"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    val signingValues = listOf("RAAD_KEYSTORE_PATH", "RAAD_KEYSTORE_PASSWORD", "RAAD_KEY_ALIAS", "RAAD_KEY_PASSWORD")
        .map { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
    check(signingValues.all { it == null } || signingValues.all { it != null }) {
        "Production signing requires all four RAAD signing environment variables."
    }
    if (signingValues.all { it != null }) {
        signingConfigs.create("production") {
            storeFile = file(signingValues[0]!!)
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
        }
    }

    buildTypes {
        release {
            // Use a private, stable signing key when configured. The fallback APK is for testing only.
            signingConfig = signingConfigs.findByName("production") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    val firebaseBom = platform("com.google.firebase:firebase-bom:34.19.0")
    val supabaseBom = platform("io.github.jan-tennert.supabase:bom:3.8.0")

    implementation(composeBom)
    implementation(firebaseBom)
    implementation(supabaseBom)
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-cio:3.5.1")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val roomVersion = "3.0.3"
    implementation("androidx.room3:room3-runtime:$roomVersion")
    implementation("androidx.sqlite:sqlite-framework:2.7.1")
    ksp("androidx.room3:room3-compiler:$roomVersion")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
