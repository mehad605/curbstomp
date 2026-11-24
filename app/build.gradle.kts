import java.util.Locale
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mhm.curbstomp"
    compileSdk = 34

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = true
    }

    defaultConfig {
        applicationId = "com.mhm.curbstomp"
        minSdk = 26
        targetSdk = 34
        
        val propVersionCode = (project.findProperty("VERSION_CODE") as? String)?.toIntOrNull() ?: 101001
        val propVersionName = (project.findProperty("VERSION_NAME") as? String) ?: "1.1.0"

        versionCode = propVersionCode
        versionName = propVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en")
    }


    splits {
        abi {
            isEnable = false

        }
    }

    val keystorePath = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val requireReleaseSigning = (project.findProperty("REQUIRE_RELEASE_SIGNING") as? String)
        ?.equals("true", ignoreCase = true) == true

    if (requireReleaseSigning && keystorePath == null) {
        throw GradleException("REQUIRE_RELEASE_SIGNING=true but KEYSTORE_PATH is unset or blank.")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                val storePass = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PATH is set but KEYSTORE_PASSWORD is missing.")
                val alias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PATH is set but KEY_ALIAS is missing.")
                val keyPass = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PATH is set but KEY_PASSWORD is missing.")

                storeFile = file(keystorePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo {
                include = false
            }
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "Curbstomp-${variant.name}.apk"
            }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/androidx/**"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) {
        // Curbstomp does not use TabLayoutMediator / ViewPager2.
        exclude(group = "androidx.viewpager2")
    }
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

abstract class InstallAndGrantAccessibilityTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Input
    abstract val adbPath: Property<String>

    @TaskAction
    fun run() {
        val adb = adbPath.get()
        val appId = "com.mhm.curbstomp"
        Thread.sleep(2000)
        execOperations.exec {
            val combinedServices = "$appId/$appId.services.CurbstompService"
            commandLine(adb, "shell", "settings", "put", "secure", "enabled_accessibility_services", combinedServices)
        }
        execOperations.exec {
            commandLine(adb, "shell", "am", "start", "-n", "$appId/$appId.ui.activity.MainActivity")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        tasks.register<InstallAndGrantAccessibilityTask>("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs the app, grants Accessibility permission, and launches it"
            dependsOn("install$variantName")
            adbPath.set(sdkComponents.adb.map { it.asFile.absolutePath })
        }
    }
}
