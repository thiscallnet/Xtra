import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.apollo)
}

val defaultVersionCode = 121
val applicationVersionName = "2.58.6"
val applicationVersionCode = providers.gradleProperty("ciVersionCode")
    .orNull
    ?.toInt()
    ?: defaultVersionCode
/**
 * This is Xtra's public Helix client ID, also used by the app's API defaults.
 * Keep it in sync with the Twitch web client identity used by C. Debug builds use it automatically;
 * release builds must provide their own value.
 */
val localDevelopmentTwitchPublicClientId = "ilfexgv3nnljz3isbm257gzwrzr7bi"
val configuredTwitchPublicClientId = providers.gradleProperty("twitchPublicClientId")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    val requestedTask = taskName.substringAfterLast(':')
    requestedTask.equals("release", ignoreCase = true) ||
        requestedTask.equals("assembleRelease", ignoreCase = true) ||
        requestedTask.equals("bundleRelease", ignoreCase = true) ||
        requestedTask.equals("publishRelease", ignoreCase = true) ||
        requestedTask.equals("perf", ignoreCase = true) ||
        requestedTask.equals("assemblePerf", ignoreCase = true) ||
        requestedTask.equals("bundlePerf", ignoreCase = true) ||
        requestedTask in setOf("assemble", "build", "bundle")
}
val releaseAbiSplitsRequested = providers.gradleProperty("xtraReleaseAbiSplits")
    .map(String::toBoolean)
    .orElse(
        gradle.startParameter.taskNames.any { taskName ->
            taskName.substringAfterLast(':').equals("assembleRelease", ignoreCase = true)
        },
    )
    .get()

if (releaseTaskRequested) {
    require(configuredTwitchPublicClientId != null) {
        "twitchPublicClientId is required for release builds"
    }
}

val twitchPublicClientId = configuredTwitchPublicClientId ?: localDevelopmentTwitchPublicClientId
val customGeckoViewAar = providers.gradleProperty("xtraGeckoViewAar")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { file(it).absoluteFile }

if (customGeckoViewAar != null) {
    require(customGeckoViewAar.isFile) {
        "xtraGeckoViewAar must point to an existing AAR: $customGeckoViewAar"
    }
}

require(applicationVersionCode in 1..2_100_000_000) {
    "versionCode must be between 1 and 2,100,000,000"
}

kotlin {
    jvmToolchain(21)
}

android {
    signingConfigs {
        getByName("debug") {
            keyAlias = "debug"
            keyPassword = "123456"
            storeFile = file("debug-keystore.jks")
            storePassword = "123456"
        }
    }
    namespace = "com.github.andreyasadchy.xtra"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.github.andreyasadchy.xtra"
        // GeckoView 150's AAR manifest requires API 26.
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = applicationVersionCode
        versionName = applicationVersionName
        buildConfigField("int", "CI_VERSION_CODE_BASE", defaultVersionCode.toString())
        buildConfigField("boolean", "PERF_DIAGNOSTICS", "false")
        manifestPlaceholders["profileableByShell"] = "false"
        buildConfigField(
            "String",
            "TWITCH_PUBLIC_CLIENT_ID",
            "\"${twitchPublicClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PERF_DIAGNOSTICS", "true")
        }
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-PERF"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PERF_DIAGNOSTICS", "true")
            manifestPlaceholders["profileableByShell"] = "true"
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    splits {
        abi {
            // Release distribution uses standalone ABI APKs; keep the diagnostic perf APK universal.
            isEnable = releaseAbiSplitsRequested
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    lint {
        disable += "ContentDescription"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes.addAll(listOf(
                "lib/x86/libtranslate_jni.so",
                "lib/x86/liblanguage_id_l2c_jni.so",
                "lib/x86_64/libtranslate_jni.so",
                "lib/x86_64/liblanguage_id_l2c_jni.so",
                "lib/armeabi-v7a/libtranslate_jni.so",
                "lib/armeabi-v7a/liblanguage_id_l2c_jni.so",
            ))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
    arg("room.incremental", "true")
}

val printVersionName = applicationVersionName
val printVersionCode = defaultVersionCode
abstract class PrintVersionInfoTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun printVersionInfo() {
        println("${versionName.get()} ${versionCode.get()}")
    }
}

tasks.register<PrintVersionInfoTask>("printVersionInfo") {
    versionName.set(printVersionName)
    versionCode.set(printVersionCode)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    compileOnly("com.google.j2objc:j2objc-annotations:3.0.0") // OkHttpDataSource SettableFuture
    implementation("com.google.android.gms:play-services-cronet:18.1.0")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    implementation(libs.material)
    implementation(libs.markwon.core)
    implementation(libs.markwon.linkify)

    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.coordinatorlayout)
    implementation(libs.core)
    implementation(libs.fragment.ktx)
    if (customGeckoViewAar != null) {
        implementation(files(customGeckoViewAar))
        // A local AAR has no Maven metadata, so declare GeckoView's runtime
        // dependencies explicitly. The stock Maven dependency supplies these
        // transitively when no custom AAR is selected.
        implementation("androidx.collection:collection:1.6.0")
        implementation("androidx.lifecycle:lifecycle-process:2.11.0")
        implementation("com.google.android.gms:play-services-fido:21.2.0")
        implementation("org.yaml:snakeyaml:2.2")
    } else {
        implementation(libs.geckoview)
    }
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.paging.runtime)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.paging)
    implementation(libs.swiperefreshlayout)
    implementation(libs.viewpager2)
    implementation(libs.work.runtime)

    implementation(libs.cronet.api)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.conscrypt)
    implementation(libs.serialization.json)
    implementation(libs.apollo.api)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.transformer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)

    implementation(libs.glide)
    ksp(libs.glide.ksp)
    implementation(libs.glide.okhttp)
    implementation(libs.glide.webpdecoder)

    implementation(libs.coroutines)
}

apollo {
    @Suppress("ApolloEndpointNotConfigured")
    service("service") {
        packageName.set("com.github.andreyasadchy.xtra.graphql")
    }
}
