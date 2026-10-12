import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val djiApiKey: String = localProperties.getProperty("DJI_API_KEY")
    ?: System.getenv("DJI_API_KEY")
    ?: error("Falta DJI_API_KEY: agrégala a local.properties o defínela como variable de entorno")
// Token público de Mapbox (pk.…) para el mapa satelital y la descarga offline. Cuenta gratuita en mapbox.com.
val mapboxAccessToken: String = localProperties.getProperty("MAPBOX_ACCESS_TOKEN")
    ?: System.getenv("MAPBOX_ACCESS_TOKEN")
    ?: ""

android {
    namespace = "com.jesus.dronplataneras"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jesus.dronplataneras"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["djiApiKey"] = djiApiKey
        manifestPlaceholders["mapboxAccessToken"] = mapboxAccessToken
        // El SDK de DJI v5 solo funciona en arm64-v8a; las demás ABIs solo inflan el APK
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // Los esquemas exportados por Room permiten probar las migraciones en un dispositivo
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    packaging {
        // DJI y Mapbox traen su propia copia de la librería C++: usar una sola
        jniLibs.pickFirsts += "lib/**/libc++_shared.so"
    }
}

// Esquemas de Room versionados en git (necesarios para escribir migraciones)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.androidx.activity.compose)
    //sdk dji
    implementation("com.dji:dji-sdk-v5-aircraft:5.13.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.13.0")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:5.13.0")

    // AgroScan: base de datos local, mapas offline
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.mapbox.maps)
    implementation(libs.mapbox.maps.compose)
    // org.json real para pruebas unitarias en JVM (el de Android es un stub)
    testImplementation(libs.org.json)
}