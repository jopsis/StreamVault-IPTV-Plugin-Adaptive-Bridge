plugins {
    alias(libs.plugins.android.application)
}

val releaseStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.orNull.isNullOrBlank() }

android {
    namespace = "com.streamvault.plugin.adaptivebridge"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                storeType = "JKS"
            }
        }
    }

    defaultConfig {
        applicationId = "com.streamvault.plugin.adaptivebridge"
        minSdk = 27
        targetSdk = 36
        versionCode = 27
        versionName = "1.1.22"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName)
    }
}

tasks.register("printVersionCode") {
    doLast {
        println(android.defaultConfig.versionCode)
    }
}
