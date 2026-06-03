plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.freeturn.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.freeturn.app"
        // WireGuard GoBackend (com.wireguard.android:tunnel) требует minSdk 24.
        minSdk = 24
        targetSdk = 37
        versionCode = 26
        versionName = "3.0.0-alpha5"
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // WireGuard tunnel-либа тянет java.time/desugar-зависимый код — нужно desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.jsch)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.wireguard.tunnel)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.nav.suite)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.koin.androidx.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

val singBoxSourceDir = layout.projectDirectory.dir("sing-box")
val singBoxBinary = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsing-box.so")
val singBoxAndroidDir = layout.projectDirectory.dir("sing-box-for-android/app")
val singBoxLibboxMain = layout.projectDirectory.file("sing-box-for-android/app/libs/libbox.aar")
val singBoxLibboxLegacy = layout.projectDirectory.file("sing-box-for-android/app/libs/libbox-legacy.aar")

tasks.register<Exec>("buildSingBox") {
    group = "build"
    description = "Builds the sing-box native binary into jniLibs when source is present."
    onlyIf { singBoxSourceDir.asFile.exists() }
    workingDir = singBoxSourceDir.asFile
    commandLine("go", "build", "-o", singBoxBinary.asFile.absolutePath, ".")
}

tasks.register<Exec>("buildSingBoxLibbox") {
    group = "build"
    description = "Builds libbox.aar for sing-box Android VPN service when source is present."
    onlyIf { singBoxSourceDir.asFile.exists() && singBoxAndroidDir.asFile.exists() }
    workingDir = singBoxSourceDir.asFile
    commandLine("go", "run", "./cmd/internal/build_libbox", "-target", "android")
}

tasks.register("syncSingBoxAndroidDeps") {
    group = "build"
    description = "Verifies sing-box Android artifacts are available for the app build."
    dependsOn("buildSingBox", "buildSingBoxLibbox")
    doLast {
        val hasBinary = singBoxBinary.asFile.exists()
        val hasLibbox = singBoxLibboxMain.asFile.exists() || singBoxLibboxLegacy.asFile.exists()
        if (!hasBinary && !hasLibbox) {
            val msg = """
                No sing-box artifact found.
                Either:
                  - Place libsing-box.so in src/main/jniLibs/arm64-v8a/ (pre-built binary)
                  - Or clone sing-box source to app/sing-box/ and run this task
                  - Or clone sing-box-for-android source to app/sing-box-for-android/ and run this task
            """.trimIndent()
            throw GradleException(msg)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("syncSingBoxAndroidDeps")
}
