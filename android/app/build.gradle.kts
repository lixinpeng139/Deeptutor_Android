import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from keystore.properties (not committed); without it
// release builds fall back to the debug key so `assembleRelease` always works.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.opendeeptutor.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.opendeeptutor.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 10701
        versionName = "1.7.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    // One APK per ABI: each carries a full Linux userland for that CPU.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "GUEST_ARCH", "\"arm64\"")
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "GUEST_ARCH", "\"x86_64\"")
        }
    }
    sourceSets {
        getByName("x64") {
            assets.srcDirs("src/x86_64/assets")
            jniLibs.srcDirs("src/x86_64/jniLibs")
        }
    }

    buildFeatures { buildConfig = true }

    androidResources {
        // The payload is already gzip-compressed; storing it lets it stream from the APK.
        noCompress += listOf("tgz", "json")
    }

    packaging {
        // proot must exist as a real file in nativeLibraryDir: it is the only
        // app location Android allows to execve() since targetSdk 29.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
