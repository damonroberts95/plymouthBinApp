import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(key: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: ""

android {
    namespace = "com.plymouthbins.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.plymouthbins.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("APP_VERSION_CODE") as String).toInt()
        versionName = project.findProperty("APP_VERSION_NAME") as String
    }

    signingConfigs {
        create("release") {
            // Credentials read from env vars OR local.properties (which is gitignored).
            // No defaults baked into source or APK.
            val ksPath = System.getenv("PLYMOUTH_KEYSTORE")
                ?: rootProject.file("release.keystore").takeIf { it.exists() }?.absolutePath
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = localOrEnv("PLYMOUTH_KS_PASS")
                keyAlias = localOrEnv("PLYMOUTH_KEY_ALIAS")
                keyPassword = localOrEnv("PLYMOUTH_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "PlymouthBins-${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material:1.6.4")  // for pullRefresh modifier
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.datastore:datastore-preferences:1.0.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.glance:glance-appwidget:1.0.0")
    implementation("androidx.glance:glance-material3:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
}
