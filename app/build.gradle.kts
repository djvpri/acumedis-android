plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.acumedis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.acumedis.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 21
        versionName = "1.11.2"

        val baseUrl = (project.findProperty("ACUMEDIS_BASE_URL") as String?) ?: ""
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = File(rootProject.rootDir, "keystore/acumedis-release.jks")
            val pwFile = File(rootProject.rootDir, "keystore/password.txt")
            if (ksFile.exists() && pwFile.exists()) {
                storeFile = ksFile
                storePassword = pwFile.readText().trim()
                keyAlias = "acumedis"
                keyPassword = pwFile.readText().trim()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
