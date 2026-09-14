@file:Suppress("UnstableApiUsage")

// Imported rather than fully qualified: in the Kotlin DSL a bare `java` resolves to
// the Java plugin extension, so `java.util.Properties` does not compile.
import java.util.Properties

val pkg: String = providers.gradleProperty("wireguardPackageName").get()

// Portway fork: the config-import deep link scheme, shared by the manifest
// intent-filter and BuildConfig so the two can never drift apart.
val importScheme: String = providers.gradleProperty("portwayImportScheme").get()

// Baked-in panel URL; overridable per-build with -PportwayPanelUrl=…
val panelUrl: String = providers.gradleProperty("portwayPanelUrl").get()
val fallbackDns = providers.gradleProperty("portwayFallbackDns").getOrElse("1.1.1.1")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
}

// Portway fork: release signing, opt-in via a git-ignored keystore.properties.
// Absent that file the build still works and simply produces an unsigned APK,
// so cloning the repo on another machine is not blocked by missing secrets.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    compileSdk = 36
    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
    }
    // MyVPN fork: namespace (where R/BuildConfig are generated) stays on the upstream
    // package so the ~31 sources importing com.wireguard.android.R keep compiling;
    // only applicationId is rebranded, which is what governs install identity.
    namespace = "com.wireguard.android"
    defaultConfig {
        applicationId = pkg
        minSdk = 24
        versionCode = providers.gradleProperty("wireguardVersionCode").get().toInt()
        versionName = providers.gradleProperty("wireguardVersionName").get()
        buildConfigField("int", "MIN_SDK_VERSION", minSdk.toString())
        buildConfigField("String", "IMPORT_SCHEME", "\"$importScheme\"")
        buildConfigField("String", "PANEL_URL", "\"$panelUrl\"")
        buildConfigField("String", "FALLBACK_DNS", "\"$fallbackDns\"")
        manifestPlaceholders["importScheme"] = importScheme
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("portway") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2/v3 give fast verification on modern Android; v1 keeps API 24 installable.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("portway")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-android-optimize.txt")
            packaging {
                resources {
                    excludes += "DebugProbesKt.bin"
                    excludes += "kotlin-tooling-metadata.json"
                    excludes += "META-INF/*.version"
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("googleplay") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "LongLogTag"
        warning += "MissingTranslation"
        warning += "ImpliedQuantity"
    }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.google.material)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugarJdkLibs)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:unchecked")
    options.isDeprecation = true
}
