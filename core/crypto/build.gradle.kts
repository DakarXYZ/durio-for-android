import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// durio: the E2EE core (plan AN-20). Wraps the stoat-crypto AAR (UniFFI bindings over
// libstoat_crypto_ffi.so) behind `chat.stoat.api.fork.e2ee.E2eeCore`; nothing outside this
// module sees UniFFI or JNA types.
plugins {
    alias(libs.plugins.android.library)
}

// Host build of the library (linux-x86-64) for JVM tests against the real core; copied by
// scripts/durio-crypto-local.sh. Without it those tests are skipped.
val hostLibDir = rootProject.layout.projectDirectory.dir(".durio-crypto/host").asFile

android {
    namespace = "chat.stoat.core.crypto"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.systemProperty("jna.library.path", hostLibDir.path)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core:api"))

    // The release AAR comes without metadata (GitHub Releases, ivy): `@aar` keeps the local
    // maven build the same, and JNA + coroutines are declared here (stoat-crypto README).
    // R8 rules ship inside the AAR (consumer-rules.pro).
    implementation("io.github.dakarxyz.durio:stoat-crypto:${libs.versions.stoat.crypto.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.kotlinx.coroutines.core)

    // JVM tests: the JAR carries the desktop jnidispatch for the host library
    testImplementation(project(":core:model"))
    testImplementation(libs.compose.multiplatform.runtime)
    testImplementation(libs.jna)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.serialization.json)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
}
