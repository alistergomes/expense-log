import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.expenselog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.expenselog.app"
        minSdk = 26
        targetSdk = 34
        // The watchdog rebuilds an old release under a new number with -PvcOverride / -PvnOverride.
        versionCode = (project.findProperty("vcOverride") as String?)?.toInt() ?: 38
        versionName = (project.findProperty("vnOverride") as String?) ?: "4.3"
        // Personal defaults (Microsoft app ID, Excel file) come from local.properties, which is not uploaded.
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
        }
        fun personal(key: String) = "\"" + (local.getProperty("expenselog.$key") ?: "").replace("\"", "") + "\""
        buildConfigField("String", "CLIENT_ID", personal("clientId"))
        buildConfigField("String", "ITEM_ID", personal("itemId"))
        buildConfigField("String", "FILE_NAME", personal("fileName"))
        buildConfigField("String", "SHEET", personal("sheet"))
        // -PcrashTest=true builds a version that crashes at startup, to test the rollback.
        // Only the processor type the OnePlus 8T (and nearly every current phone) uses: keeps updates small.
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "CRASH_TEST", if (project.findProperty("crashTest") == "true") "true" else "false")
    }

    buildFeatures {
        buildConfig = true
    }

    // Every version must be signed with the same key or Android refuses to update the installed app.
    // The key is kept in D:\AndroidDev\keys (a copy of the original debug key) so it survives
    // a cleaned-out user profile; back that folder up.
    val appKey = file("D:/AndroidDev/keys/debug.keystore")
    if (appKey.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = appKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    // #76: the permanent release key (created by make-release-key.ps1). build.ps1 and watchdog.ps1 read its
    // password from the encrypted file and pass it in as RELEASE_STORE_PASSWORD; it is never written to disk plainly.
    val releaseKey = file("D:/AndroidDev/keys/release.keystore")
    val releasePass = System.getenv("RELEASE_STORE_PASSWORD")
    if (releaseKey.exists() && !releasePass.isNullOrEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKey
                storePassword = releasePass
                keyAlias = "expenselog"
                keyPassword = releasePass
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false // keeps crash reports readable and avoids stripping anything the app needs
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Reads text from receipt photos on the phone itself (bundled model: no network, no Google account, free).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.core:core:1.13.1") // FileProvider, for camera photos of receipts
    testImplementation("junit:junit:4.13.2")
}
