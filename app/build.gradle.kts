plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ks = System.getenv("A1_KEYSTORE")

android {
    namespace = "app.svc.core"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.svc.core"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
    }

    signingConfigs {
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("A1_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("A1_KEY_ALIAS")
                keyPassword = System.getenv("A1_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ks != null) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/**/*.kotlin_builtins", "kotlin/**", "DebugProbesKt.bin")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
