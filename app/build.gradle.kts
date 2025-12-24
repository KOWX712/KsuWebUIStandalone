@file:Suppress("UnstableApiUsage")

import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

abstract class ExternalProcessValueSource : ValueSource<String, ExternalProcessValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val command: ListProperty<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(parameters.command.get())
            standardOutput = output
        }
        return output.toString().trim()
    }
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = if (keystorePropertiesFile.exists() && keystorePropertiesFile.isFile) {
    Properties().apply {
        load(FileInputStream(keystorePropertiesFile))
    }
} else null

val gitDescribeProvider = providers.of(ExternalProcessValueSource::class) {
    parameters.command.set(listOf("git", "describe", "--tags", "--abbrev=0"))
}

val gitCommitCountProvider = providers.of(ExternalProcessValueSource::class) {
    parameters.command.set(listOf("git", "rev-list", "--count", "HEAD"))
}.map { it.toInt() }

android {
    namespace = "io.github.a13e300.ksuwebui"
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.a13e300.ksuwebui"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCountProvider.get()
        versionName = gitDescribeProvider.get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSig = signingConfigs.findByName("release")
            signingConfig = if (releaseSig != null) releaseSig else {
                println("use debug signing config")
                signingConfigs["debug"]
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    androidResources {
        generateLocaleConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
}

base {
    archivesName.set(
        "KsuWebUI-${gitDescribeProvider.get()}-${gitCommitCountProvider.get()}"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.material)

    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    implementation(libs.com.github.topjohnwu.libsu.io)

    implementation(libs.dev.rikka.rikkax.parcelablelist)
}
