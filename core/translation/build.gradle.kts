plugins { alias(libs.plugins.lmreader.android.library) }

// GPU backends default on; CPU-only builds set both lmreader.asr.vulkan/opencl=false.
// A host toolchain for vulkan-shaders-gen can be supplied with
// -Plmreader.asr.hostToolchain=<path-to-host-toolchain.cmake>.
val asrVulkan = providers.gradleProperty("lmreader.asr.vulkan")
    .map { it.trim().lowercase() != "false" }
    .getOrElse(true)
val asrHostToolchain = providers.gradleProperty("lmreader.asr.hostToolchain").orNull
val asrOpenCl = providers.gradleProperty("lmreader.asr.opencl").map { it.trim().lowercase() != "false" }.getOrElse(true)

android {
    ndkVersion = "28.2.13676358"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DLMREADER_ASR_VULKAN=${if (asrVulkan) "ON" else "OFF"}",
                    "-DLMREADER_ASR_OPENCL=${if (asrOpenCl) "ON" else "OFF"}",
                )
                asrHostToolchain?.takeIf { it.isNotBlank() }?.let {
                    arguments += "-DLMREADER_ASR_HOST_TOOLCHAIN=$it"
                }
                targets += "lmreader_translation"
                targets += "lmreader_asr"
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
