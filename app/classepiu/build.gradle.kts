plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La versione installata dall'utente è 1.0.141 (versionCode 1000092).
// Manteniamo ogni nuova build strettamente superiore a quel codice.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "it.cellini.classepiu"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.cellini.classepiu"
        minSdk = 26
        targetSdk = 34
        // Base fissata sopra 1000092: anche la prossima build si installa come aggiornamento.
        versionCode = 1000100 + buildNumber
        versionName = "1.0.${141 + buildNumber}"
    }

    signingConfigs {
        create("classepiu") {
            storeFile = file("classepiu.jks")
            storePassword = "classepiu2026"
            keyAlias = "classepiu"
            keyPassword = "classepiu2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("classepiu")
        }
        debug {
            signingConfig = signingConfigs.getByName("classepiu")
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
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/NOTICE.txt",
                "META-INF/LICENSE.txt", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0") // accesso con Google per inviare con Gmail
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
