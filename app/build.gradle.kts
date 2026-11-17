plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.logininc.ereader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.logininc.ereader"
        minSdk = 24
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull()
            ?: 1
        versionName = System.getenv("VERSION_NAME")
            ?: project.findProperty("VERSION_NAME")?.toString()
            ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE") ?: project.findProperty("KEYSTORE_FILE")?.toString()
            val envKeystore = keystorePath?.let { file(it) }
            val repoKeystore = rootProject.file("keystore/ereader-release.jks")
            if (envKeystore != null && envKeystore.exists()) {
                storeFile = envKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: project.findProperty("KEYSTORE_PASSWORD")?.toString()
                keyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS")?.toString()
                keyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD")?.toString()
            } else if (repoKeystore.exists()) {
                storeFile = repoKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "ereaderandroid"
                keyAlias = System.getenv("KEY_ALIAS") ?: "ereader"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "ereaderandroid"
            } else {
                // Fallback to debug keystore so release builds without custom keystore produce an installable APK
                val debugConfig = getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val pdfboxTestResourcesJar = tasks.register<Jar>("pdfboxTestResourcesJar") {
    dependsOn("mergeDebugAssets")
    from(layout.buildDirectory.dir("intermediates/assets/debug/mergeDebugAssets"))
    archiveFileName.set("pdfbox-test-resources.jar")
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.ext.junit)
    implementation(libs.material)
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.inspector)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.documentnode:epub4j-core:4.2.1") {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "net.sf.kxml", module = "kxml2")
    }
    implementation("org.jsoup:jsoup:1.18.3")
}
