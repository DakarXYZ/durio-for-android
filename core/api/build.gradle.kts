import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "chat.stoat.core.api"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }

        // durio: JVM unit tests for commonTest + androidHostTest (plan AN-16)
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(libs.kotlin.serialization.json)
            implementation(libs.kotlin.serialization.cbor)
            implementation(libs.kotlin.datetime)
            implementation(libs.compose.multiplatform.runtime)
            implementation(libs.kermit)
            implementation(libs.android.datastore.preferences.core)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.contentnegotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)

            implementation(libs.lifecycle.process)
            implementation(libs.android.datastore.preferences)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        // The Android host test compilation does not pick up commonTest on its own
        getByName("androidHostTest").dependsOn(commonTest.get())
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("chat.stoat.persistence")
        }
    }
}
