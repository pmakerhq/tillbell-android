plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

group = "dev.pmaker"
// Set by the release workflow from the tag (v0.1.0 publishes 0.1.0).
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-SNAPSHOT")

android {
    namespace = "com.pmaker.tillbell"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Unit tests create Play Billing objects, which touch a few Android stubs (TextUtils).
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // "A newer version is available" depends on the day the build runs, not on the code: it
        // turns CI red whenever Google announces a release (even before it is downloadable).
        // Dependabot proposes the upgrades instead.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    // A library: every public declaration must state its visibility and type on purpose.
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Part of the public API: TillbellComponent is a Hotwire BridgeComponent for HotwireDestination.
    api(libs.hotwire.core)
    api(libs.hotwire.navigation.fragments)

    implementation(libs.play.billing)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.json.schema.validator)

    androidTestImplementation(libs.androidx.test.runner)
}

// Publishes the release variant with a sources jar and a javadoc jar, signed, to Maven Central.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(artifactId = "tillbell")
    pom {
        name = "Tillbell"
        description = "Hotwire Native bridge component for Google Play Billing"
        url = "https://github.com/pmakerhq/tillbell-android"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }
        developers {
            developer {
                id = "pmakerhq"
                name = "pmakerhq"
                url = "https://github.com/pmakerhq"
            }
        }
        scm {
            url = "https://github.com/pmakerhq/tillbell-android"
            connection = "scm:git:https://github.com/pmakerhq/tillbell-android.git"
            developerConnection = "scm:git:ssh://git@github.com/pmakerhq/tillbell-android.git"
        }
    }
}
