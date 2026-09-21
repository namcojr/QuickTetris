import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Packages <root>/roms/atetris.zip as assets/roms/atetris.zip. ROMs stay out of src/. */
abstract class PackageRomsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val romDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val src = romDir.file("atetris.zip").get().asFile
        if (!src.isFile) throw GradleException("Missing ROM: ${src.absolutePath}")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        src.copyTo(out.resolve("roms/atetris.zip"))
    }
}

val packageRoms = tasks.register<PackageRomsTask>("packageRoms") {
    romDir.set(rootProject.layout.projectDirectory.dir("roms"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(packageRoms, PackageRomsTask::outputDir)
    }
}

/** Release signing from <root>/keystore.properties (gitignored); absent -> release is built unsigned. */
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.namco.quicktetris"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.namco.quicktetris"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    androidResources {
        // MAME reads the zip directly; keep it stored so the asset copy is a plain stream.
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
