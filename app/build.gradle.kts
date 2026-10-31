import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.swipper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swipper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junitJupiter.get()}"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * Usage personnel, hors réseau : échoue si le manifeste fusionné (dépendances comprises)
 * demande une permission interdite. Branché sur `check` et sur chaque assemble.
 */
abstract class CheckForbiddenPermissions : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val forbidden: ListProperty<String>

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val found = forbidden.get().filter { Regex("""android:name="${Regex.escape(it)}"""").containsMatchIn(text) }
        check(found.isEmpty()) { "Permissions interdites dans le manifeste fusionné : $found" }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<CheckForbiddenPermissions>("check${name}ForbiddenPermissions") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            forbidden.set(listOf("android.permission.INTERNET", "android.permission.SYSTEM_ALERT_WINDOW"))
        }
        tasks.named("check") { dependsOn(task) }
        afterEvaluate { tasks.named("assemble$name") { dependsOn(task) } }
    }
}
