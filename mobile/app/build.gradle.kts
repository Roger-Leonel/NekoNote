import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val propsLocais = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun propriedade(nome: String, padrao: String = ""): String =
    (propsLocais.getProperty(nome) ?: providers.gradleProperty(nome).orNull ?: padrao)

android {
    namespace = "br.com.korvon.nekonote"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.korvon.nekonote"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        // endereco da API: so o dominio (subdominio + dominio, o mesmo API_DOMAIN do api/.env).
        // A API, o WebSocket, as paginas legais e o login (/auth/v1) saem todos dele.
        val dominio = propriedade("nekonote.dominio", "api.korvon.com.br").trim().trimEnd('/')
        val urlApi = if (dominio.startsWith("http")) dominio else "https://$dominio"
        buildConfigField("String", "URL_API", "\"$urlApi\"")
        buildConfigField("String", "URL_AUTH", "\"$urlApi/auth/v1\"")
        // limite de fotos marcadas por envio (mesmo valor de MAX_IMAGES na API)
        val maxImagens = (propriedade("nekonote.maxImagens", "20").toIntOrNull() ?: 20).coerceAtLeast(1)
        buildConfigField("int", "MAX_IMAGENS", "$maxImagens")
        // ID do cliente OAuth "Web" do Google, o mesmo configurado no Supabase Auth
        buildConfigField("String", "ID_CLIENTE_WEB_GOOGLE", "\"${propriedade("google.idClienteWeb")}\"")
    }

    // assinatura de release: keystore fora do repositorio, dados em local.properties (veja local.properties.example)
    val arquivoKeystore = propriedade("release.keystore")
    signingConfigs {
        if (arquivoKeystore.isNotBlank()) {
            create("release") {
                storeFile = file(arquivoKeystore)
                storePassword = propriedade("release.storePassword")
                keyAlias = propriedade("release.keyAlias")
                keyPassword = propriedade("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // sem keystore configurada o apk de release sai sem assinatura (nao instala), pra nunca publicar um apk assinado com a chave de debug
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
