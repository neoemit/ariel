import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.github.triplet.play")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun escapedLocalProperty(key: String): String {
    return (localProperties.getProperty(key) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

fun envOrLocalProperty(envKey: String, localKey: String): String? {
    val envValue = System.getenv(envKey)?.trim()
    if (!envValue.isNullOrEmpty()) {
        return envValue
    }

    return localProperties.getProperty(localKey)?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseKeystorePath = envOrLocalProperty("ANDROID_KEYSTORE_PATH", "release.storeFile")
val releaseKeystorePassword = envOrLocalProperty("ANDROID_KEYSTORE_PASSWORD", "release.storePassword")
val releaseKeyAlias = envOrLocalProperty("ANDROID_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = envOrLocalProperty("ANDROID_KEY_PASSWORD", "release.keyPassword")
val hasReleaseSigningConfig = releaseKeystorePath != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    File(releaseKeystorePath).exists()

val playServiceAccountCredentialsPath = envOrLocalProperty(
    "PLAY_SERVICE_ACCOUNT_JSON_PATH",
    "play.serviceAccountCredentials"
)

android {
    namespace = "com.thomaslamendola.ariel"
    compileSdk = 35

    val appVersionMinor = 44

    defaultConfig {
        applicationId = "com.thomaslamendola.ariel"
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionMinor
        versionName = "1.$appVersionMinor"
        buildConfigField("String", "FIREBASE_API_KEY", "\"${escapedLocalProperty("firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${escapedLocalProperty("firebase.appId")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${escapedLocalProperty("firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${escapedLocalProperty("firebase.senderId")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("releaseUpload") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("releaseUpload")
            }
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

play {
    track.set("internal")
    defaultToAppBundles.set(true)
    if (!playServiceAccountCredentialsPath.isNullOrBlank()) {
        serviceAccountCredentials.set(file(playServiceAccountCredentialsPath))
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    // Firebase Cloud Messaging (internet relay push)
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-messaging")
    
    // Nearby Connections
    implementation("com.google.android.gms:play-services-nearby:19.0.0")
    
    // QR Code Generation
    implementation("com.google.zxing:core:3.5.3")
    
    // CameraX for Scanning
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    
    // Glance for Widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coil for images
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
