plugins {
    id("com.android.application")
}

val nemotronThreshold = providers.gradleProperty("nemotronThreshold").orElse("0.5").get().toFloatOrNull()
    ?.also { require(it in 0f..1f) { "nemotronThreshold должен быть от 0 до 1" } }
    ?: error("nemotronThreshold должен быть числом от 0 до 1")
val nemotronApplicationIdSuffix = providers.gradleProperty("nemotronApplicationIdSuffix")
    .orElse(".mnn.nemotron").get()
val mnnPrototypeApplicationIdSuffix = providers.gradleProperty("mnnPrototypeApplicationIdSuffix")
    .orElse(".mnn").get()
val productionReleaseApplicationIdSuffix = providers.gradleProperty("productionReleaseApplicationIdSuffix")
    .orElse("").get()
val nemotronQuantization = providers.gradleProperty("nemotronQuantization")
    .orElse("dynamic-int8").get()
require(nemotronQuantization in setOf("dynamic-int8", "weight-only-int4")) {
    "nemotronQuantization должен быть dynamic-int8 или weight-only-int4"
}
val speakerEmbeddingDiagnostics = providers.gradleProperty("speakerEmbeddingDiagnostics")
    .orElse("false").get().toBooleanStrictOrNull()
    ?: error("speakerEmbeddingDiagnostics должен быть true или false")
val speakerMatcherThreshold = providers.gradleProperty("speakerMatcherThreshold").orElse("0.55").get().toFloatOrNull()
    ?.also { require(it in 0f..1f) { "speakerMatcherThreshold должен быть от 0 до 1" } }
    ?: error("speakerMatcherThreshold должен быть числом от 0 до 1")
val speakerMatcherAllowManyToOne = providers.gradleProperty("speakerMatcherAllowManyToOne")
    .orElse("false").get().toBooleanStrictOrNull()
    ?: error("speakerMatcherAllowManyToOne должен быть true или false")
val speakerMatcherManyToOneThreshold = providers.gradleProperty("speakerMatcherManyToOneThreshold")
    .orElse("0.75").get().toFloatOrNull()
    ?.also { require(it in 0f..1f) { "speakerMatcherManyToOneThreshold должен быть от 0 до 1" } }
    ?: error("speakerMatcherManyToOneThreshold должен быть числом от 0 до 1")
val speakerEmbeddingModel = providers.gradleProperty("speakerEmbeddingModel").orElse("eres2net").get()
val speakerEmbeddingModelLabel = when (speakerEmbeddingModel) {
    "eres2net" -> "3D-Speaker ERes2Net"
    "wespeaker" -> "WeSpeaker ResNet34-LM"
    else -> error("speakerEmbeddingModel должен быть eres2net или wespeaker")
}
val nemotronAssetsDirectory = providers.gradleProperty("nemotronAssetsDirectory")
    .orElse("src/mnnPrototypeNemotron/assets").get()
val modelAssetsDirectory = providers.gradleProperty("modelAssetsDirectory")
    .orElse("src/modelAssets").get()
require(nemotronApplicationIdSuffix.startsWith(".") && nemotronApplicationIdSuffix.drop(1).isNotEmpty() &&
    nemotronApplicationIdSuffix.drop(1).all { it in 'a'..'z' || it in '0'..'9' || it == '.' }) {
    "nemotronApplicationIdSuffix должен начинаться с точки и содержать только a-z, 0-9 и точки"
}
require(mnnPrototypeApplicationIdSuffix.startsWith(".") && mnnPrototypeApplicationIdSuffix.drop(1).isNotEmpty() &&
    mnnPrototypeApplicationIdSuffix.drop(1).all { it in 'a'..'z' || it in '0'..'9' || it == '.' }) {
    "mnnPrototypeApplicationIdSuffix должен начинаться с точки и содержать только a-z, 0-9 и точки"
}
require(productionReleaseApplicationIdSuffix.isEmpty() ||
    (productionReleaseApplicationIdSuffix.startsWith(".") && productionReleaseApplicationIdSuffix.drop(1).isNotEmpty() &&
        productionReleaseApplicationIdSuffix.drop(1).all { it in 'a'..'z' || it in '0'..'9' || it == '.' })) {
    "productionReleaseApplicationIdSuffix должен быть пустым или начинаться с точки и содержать только a-z, 0-9 и точки"
}

android {
    namespace = "ru.neurostenographer"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.neurostenographer"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("float", "NEMOTRON_THRESHOLD", "0.5f")
        buildConfigField("String", "NEMOTRON_QUANTIZATION", "\"dynamic-int8\"")
        buildConfigField("boolean", "SPEAKER_EMBEDDING_DIAGNOSTICS", "false")
        buildConfigField("float", "SPEAKER_MATCHER_THRESHOLD", "${speakerMatcherThreshold}f")
        buildConfigField("boolean", "SPEAKER_MATCHER_ALLOW_MANY_TO_ONE", "$speakerMatcherAllowManyToOne")
        buildConfigField("float", "SPEAKER_MATCHER_MANY_TO_ONE_THRESHOLD", "${speakerMatcherManyToOneThreshold}f")
        buildConfigField("String", "SPEAKER_EMBEDDING_MODEL_LABEL", "\"${speakerEmbeddingModelLabel}\"")
        testInstrumentationRunner = "ru.neurostenographer.LocalPipelineInstrumentation"
        // The contest target and tested device are Android phones with ARM64.
        // sherpa-onnx bundles large native runtimes for each ABI, so avoid
        // shipping four copies of those libraries in the single submission APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            applicationIdSuffix = productionReleaseApplicationIdSuffix
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += "mode"
    productFlavors {
        create("bench") {
            dimension = "mode"
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            buildConfigField("String", "APP_MODE", "\"bench\"")
        }
        create("production") {
            dimension = "mode"
            buildConfigField("String", "APP_MODE", "\"production\"")
        }
        create("mnnPrototype") {
            dimension = "mode"
            applicationIdSuffix = mnnPrototypeApplicationIdSuffix
            versionNameSuffix = "-mnn"
            buildConfigField("String", "APP_MODE", "\"bench\"")
        }
        create("mnnPrototypeNemotron") {
            dimension = "mode"
            applicationIdSuffix = nemotronApplicationIdSuffix
            versionNameSuffix = "-mnn-nemotron"
            buildConfigField("String", "APP_MODE", "\"bench\"")
            buildConfigField("float", "NEMOTRON_THRESHOLD", "${nemotronThreshold}f")
            buildConfigField("String", "NEMOTRON_QUANTIZATION", "\"${nemotronQuantization}\"")
            buildConfigField("boolean", "SPEAKER_EMBEDDING_DIAGNOSTICS", "$speakerEmbeddingDiagnostics")
        }
    }

    sourceSets {
        getByName("main") {
            assets.setSrcDirs(listOf(modelAssetsDirectory))
        }
        getByName("production") {
            // Ship the frozen Nemotron INT8 model in the normal app flavor; APP_MODE stays production.
            assets.setSrcDirs(listOf(nemotronAssetsDirectory))
        }
        getByName("mnnPrototype") {
            manifest.srcFile("src/bench/AndroidManifest.xml")
        }
        getByName("mnnPrototypeNemotron") {
            manifest.srcFile("src/bench/AndroidManifest.xml")
            assets.setSrcDirs(listOf(nemotronAssetsDirectory))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    "productionImplementation"(files("libs/sherpa-onnx-nemotron.aar"))
    "mnnPrototypeImplementation"(files("libs/sherpa-onnx-2.0.1.aar"))
    "mnnPrototypeNemotronImplementation"(files("libs/sherpa-onnx-nemotron.aar"))
    val nnapiExperiment = providers.gradleProperty("nnapiExperiment").orNull == "true"
    "benchImplementation"(files(if (nnapiExperiment) "libs/sherpa-onnx-2.0.1-nnapi-api30.aar" else "libs/sherpa-onnx-2.0.1.aar"))
}
