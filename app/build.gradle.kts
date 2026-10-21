import com.android.build.api.variant.FilterConfiguration
import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    id("kotlin-parcelize")
}

fun property(fileName: String, propertyName: String, fallbackEnv: String? = null): String? {
    val propsFile = rootProject.file(fileName)
    if (propsFile.exists()) {
        val props = Properties()
        props.load(FileInputStream(propsFile))
        if (props[propertyName] != null) {
            return props[propertyName] as String?
        } else {
            logger.warn("Property '$propertyName' not found in '$fileName'. Attempting to use environment variable '$fallbackEnv'")
            if (fallbackEnv != null) {
                val env = System.getenv(fallbackEnv)
                if (env != null) {
                    return env
                } else {
                    logger.warn("Environment variable '$fallbackEnv' not found either. Returning null")
                }
            }
            return null
        }
    } else {
        logger.warn("Properties file '$fileName' not found. Attempting to use environment variable '$fallbackEnv'")
        if (fallbackEnv != null) {
            val env = System.getenv(fallbackEnv)
            if (env != null) {
                return env
            } else {
                logger.warn("Environment variable '$fallbackEnv' not found either. Returning null")
            }
        }
        return null
    }
}

// Calls property but with stoatbuild.properties as the first argument
fun buildproperty(propertyName: String, fallbackEnv: String? = null): String? {
    return property("stoatbuild.properties", propertyName, fallbackEnv)
}

// durio: ABI splits for GitHub Releases (plan AN-41, O2). Every APK gets
// versionCode = base * 10 + abiCode (universal = 0), so an update never goes "down" when a device
// moves between the universal and a per-ABI APK. 32-bit x86 is left out: the E2EE core and RaTeX
// ship no x86 library.
val durioVersionCodeBase = Integer.parseInt("001_008_006".replace("_", ""), 10)
val durioAbiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)

// durio: instrumented tests against the minified (R8) release build, -Pdurio.minifiedTests
// (plan AN-34): JNA and UniFFI must survive R8; `connectedReleaseAndroidTest` on a device.
val durioMinifiedTests = providers.gradleProperty("durio.minifiedTests").isPresent

// durio: App Links only for our own server's host, set per build; none by default (plan AN-13, O12)
val durioLinkHost = buildproperty("durio.link_host", "DURIO_LINK_HOST").orEmpty().trim()

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "chat.stoat"

    defaultConfig {
        // durio: our own application id (plan AN-01, decision O1′)
        applicationId = "io.github.dakarxyz.durio"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // durio: universal APK; the per-ABI ones are set in androidComponents below (AN-41)
        versionCode = durioVersionCodeBase * 10
        versionName = "1.8.6"

        // durio: server prefilled on the login screen; empty = the user types the address (plan O13)
        buildConfigField(
            "String",
            "DEFAULT_SERVER",
            "\"${buildproperty("durio.default_server", "DURIO_DEFAULT_SERVER") ?: ""}\""
        )
        // durio: upstream status alerts and changelog, only for accounts on stoat.chat (plan AN-07, Q39)
        buildConfigField("boolean", "FORK_UPSTREAM_HEALTH", "false")
        buildConfigField("boolean", "FORK_UPSTREAM_GAZETTE", "false")
        manifestPlaceholders["durioLinkHost"] = durioLinkHost
        // durio: in-app update check against our GitHub Releases; off for a future Play build (plan AN-18)
        buildConfigField("boolean", "FORK_UPDATE_CHECK", "true")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // durio: release signing from keystore.properties (not in git) or DURIO_KEYSTORE_* env (plan AN-02).
    // Without them assembleRelease produces an unsigned APK, as upstream does.
    val releaseKeystore = property("keystore.properties", "storeFile", "DURIO_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = property("keystore.properties", "storePassword", "DURIO_KEYSTORE_PASSWORD")
                keyAlias = property("keystore.properties", "keyAlias", "DURIO_KEY_ALIAS")
                keyPassword = property("keystore.properties", "keyPassword", "DURIO_KEY_PASSWORD")
            }
        }
    }

    if (durioMinifiedTests) testBuildType = "release"

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (durioMinifiedTests) {
                // the tests call into our E2EE code directly; JNA/UniFFI keep only the AAR's rules
                proguardFiles("proguard-minified-tests.pro")
                // an installable APK without the release key; never published
                if (signingConfig == null) signingConfig = signingConfigs.getByName("debug")
            }
            buildConfigField(
                "String",
                "FLAVOUR_ID",
                "\"${buildproperty("build.flavour_id", "RVX_BUILD_FLAVOUR_ID")}\""
            )
        }

        debug {
            isPseudoLocalesEnabled = true

            applicationIdSuffix = ".debug"
            versionNameSuffix = "+debug"
            resValue(
                "string",
                "app_name",
                buildproperty("build.debug.app_name", "RVX_DEBUG_APP_NAME") ?: "dur.io debug"
            )

            buildConfigField(
                "String",
                "FLAVOUR_ID",
                "\"${buildproperty("build.flavour_id", "RVX_BUILD_FLAVOUR_ID")}\""
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
        resValues = true
    }
    // durio: one APK per ABI plus the universal one (plan AN-41); not abiFilters, they clash
    splits {
        abi {
            isEnable = true
            reset()
            include(*durioAbiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // durio: ABIs we do not ship (AN-41). JNA brings its own jnidispatch for them and LiveKit,
        // CameraX, ML Kit have x86; a device would then pick a lib folder without our E2EE core.
        jniLibs {
            excludes += listOf(
                "lib/armeabi/**",
                "lib/mips/**",
                "lib/mips64/**",
                "lib/x86/**",
                "lib/loongarch64/**",
                "lib/riscv64/**",
                "lib/s390x/**",
                "lib/ppc64le/**",
            )
        }
    }
    androidResources {
        generateLocaleConfig = false
    }
    testOptions {
        // durio: Robolectric needs merged resources and the manifest (plan AN-16)
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        // durio: UI tests run under Robolectric and on a device alike (plan AN-34); the
        // device smoke test of the E2EE core uses the in-memory server of :core:crypto
        getByName("test").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
        getByName("androidTest").kotlin.srcDir("../core/crypto/src/sharedTest/kotlin")
    }
    lint {
        abortOnError = false
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:api"))
    implementation(project(":core:crypto"))
    implementation(project(":core:designsystem"))
    implementation(project(":shared"))
    implementation(libs.compose.multiplatform.resources)

    implementation(libs.android.core.ktx)
    implementation(libs.kotlin.reflect)

    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlin.serialization.cbor)
    implementation(libs.kotlin.datetime)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    testImplementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsizeclass)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.runtime.livedata)
    implementation(libs.reorderable)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(libs.accompanist.permissions)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.glide)
    implementation(libs.glide.compose)
    ksp(libs.glide.ksp)

    implementation(libs.aboutlibraries.core)

    implementation(libs.android.profileinstaller)
    implementation(libs.android.documentfile)
    implementation(libs.android.browser)
    implementation(libs.android.webkit)
    implementation(libs.android.palette)
    implementation(libs.android.core.telecom)
    implementation(libs.android.core.splashscreen)
    implementation(libs.android.constraintlayout.compose)
    implementation(libs.android.appcompat)
    implementation(libs.android.material)
    implementation(libs.android.datastore)
    implementation(libs.android.datastore.preferences)
    implementation(libs.android.work.runtime)

    implementation(libs.hcaptcha)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.muxer)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose.material3)

    implementation(libs.zoomable.image)
    implementation(libs.zoomable.image.glide)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    implementation(libs.zxing.core)
    implementation(libs.quickie.bundled)

    implementation(libs.sqldelight.android.driver)

    implementation(libs.jetbrains.markdown)
    implementation(libs.highlights)

    implementation(libs.livekit.android)
    implementation(libs.livekit.android.camerax)
    implementation(libs.livekit.android.compose)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.shimmer)

    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.noop)

    implementation(libs.square.logcat)
    implementation(libs.kermit)

    implementation(libs.koin)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel.navigation)

    implementation(libs.multiplatform.markdown.android)
    implementation(libs.multiplatform.markdown.m3)
    implementation(libs.multiplatform.markdown.coil3)
    implementation(libs.multiplatform.markdown.code)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.ratex.android)

    androidTestImplementation(libs.android.test.core)
    androidTestImplementation(libs.android.test.rules)
    androidTestImplementation(libs.android.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.android.test.ext.junit)
    androidTestImplementation(libs.ktor.client.mock)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.android.test.core.ktx)
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.android.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
}

aboutLibraries {
    license {
        strictMode = StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0",
            "ASDKL",
            "BSD-2-Clause",
            "BSD-3-Clause", "The 3-Clause BSD License",
            "BSD License",
            "EPL-1.0",
            // durio: JNA for the E2EE core is "LGPL-2.1-or-later OR Apache-2.0" (plan AN-20, AN-41)
            "LGPL-2.1-or-later",
            "MIT",
            "ML Kit Terms of Service",
            "OFL",
            "Public Domain"
        )
        additionalLicenses.addAll("ofl")
    }

    collect {
        includePlatform = true
        configPath = file("../compliance")
    }
}

abstract class GenerateLocaleConfig : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val composeResources: DirectoryProperty

    @get:Input
    abstract val defaultLocale: Property<String>

    @get:Input
    abstract val includePseudoLocales: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val qualified = composeResources.get().asFile
            .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
            .orEmpty()
            .filter { it.resolve("strings.xml").exists() }
            .map { it.name.removePrefix("values-").toLanguageTag() }
            .sorted()
        val pseudo = if (includePseudoLocales.get()) listOf("en-XA", "ar-XB") else emptyList()
        val locales = listOf(defaultLocale.get()) + qualified + pseudo

        val xml = outputDirectory.get().file("xml/locales_config.xml").asFile
        xml.parentFile.mkdirs()
        xml.writeText(
            buildString {
                appendLine("<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">")
                locales.forEach { appendLine("    <locale android:name=\"$it\"/>") }
                appendLine("</locale-config>")
            }
        )
    }

    private fun String.toLanguageTag(): String = when {
        startsWith("b+") -> removePrefix("b+").replace('+', '-')
        contains("-r") -> replace("-r", "-")
        else -> this
    }
}

androidComponents {
    onVariants { variant ->
        // durio: per-ABI versionCode (AN-41)
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            if (abi != null) output.versionCode.set(durioVersionCodeBase * 10 + durioAbiCodes.getValue(abi))
        }

        if (durioLinkHost.isNotEmpty()) {
            variant.sources.manifests.addStaticManifestFile("src/links/AndroidManifest.xml")
        }

        val task = tasks.register<GenerateLocaleConfig>(
            "generate${variant.name.replaceFirstChar(Char::uppercase)}StoatLocaleConfig"
        ) {
            composeResources.set(rootProject.layout.projectDirectory.dir("shared/src/commonMain/composeResources"))
            defaultLocale.set(
                providers.fileContents(layout.projectDirectory.file("src/main/res/resources.properties"))
                    .asText
                    .map { text ->
                        Properties().apply { load(text.reader()) }
                            .getProperty("unqualifiedResLocale")
                    }
            )
            includePseudoLocales.set(variant.pseudoLocalesEnabled)
        }
        variant.sources.res?.addGeneratedSourceDirectory(
            task,
            GenerateLocaleConfig::outputDirectory
        )
    }
}
