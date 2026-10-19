pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
        maven {
            name = "snapshot"
            url = uri("https://oss.sonatype.org/content/repositories/snapshots")
        }
        maven {
            name = "compose-dev"
            url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        }
        // durio: the E2EE core AAR (plan AN-20, Q36). A local build copied in by
        // scripts/durio-crypto-local.sh wins; otherwise the GitHub Release of durio-crypt.
        // Either way the AAR must match the SHA-256 in gradle/verification-metadata.xml.
        // Exclusive: the repositories above are never asked for it (jitpack answers 401
        // for an unknown module, and Gradle fails on that instead of trying the next one).
        exclusiveContent {
            forRepositories(
                maven {
                    name = "durio-crypto-local"
                    url = uri(".durio-crypto/maven")
                },
                ivy {
                    name = "durio-crypt"
                    url = uri("https://github.com/DakarXYZ/durio-crypt/releases/download")
                    patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
                    metadataSources { artifact() }
                },
            )
            filter { includeModule("io.github.dakarxyz.durio", "stoat-crypto") }
        }
    }
}
rootProject.name = "Stoat"
include(":app")
include(":core:model")
include(":core:api")
include(":core:crypto")
include(":core:designsystem")
include(":shared")
