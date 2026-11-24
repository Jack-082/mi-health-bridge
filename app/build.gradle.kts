plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 构建时收集全部依赖（含间接依赖）的许可证，供 App 内“开源许可”页面显示
    id("com.mikepenz.aboutlibraries.plugin")
}

// ---------------- 版本号：由 Git 自动生成 ----------------
// versionCode = 提交总数（每次提交 +1，保证新版本总能覆盖旧版本）
// versionName = 最近的 v* 标签：正好在标签上 → "0.1.0"；
//               标签之后又有 3 个提交 → "0.1.0-3-gabc1234"；还没有标签 → "0.0.0-12-gabc1234"
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

val gitCommitCount: Int = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val gitVersionName: String = git("describe", "--tags", "--match", "v[0-9]*", "--dirty")?.removePrefix("v")
    ?: "0.0.0-$gitCommitCount-g${git("rev-parse", "--short", "HEAD") ?: "unknown"}"

// ---------------- 把仓库根目录的 LICENSE / NOTICE 打包进 APK 的 assets ----------------
abstract class CopyLegalFiles : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.mkdirs()
        sources.files.forEach { it.copyTo(File(out, it.name), overwrite = true) }
    }
}

val copyLegalFiles = tasks.register<CopyLegalFiles>("copyLegalFiles") {
    sources.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalFiles, CopyLegalFiles::outputDir)
    }
}

android {
    namespace = "io.github.mihealthbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mihealthbridge"
        minSdk = 28
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = gitVersionName
    }

    // 可选的固定签名：在 CI 里通过环境变量提供，保证每次构建的 APK 能覆盖安装。
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                val storePw = System.getenv("SIGNING_STORE_PASSWORD")
                storePassword = storePw
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "mihealthbridge"
                // PKCS12 keystore 的密钥密码与 keystore 密码相同；未单独配置时直接沿用
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // 输出文件名带版本号，例如 MiHealthBridge-0.1.0-release.apk
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "MiHealthBridge-${variant.versionName}-${variant.name}.apk"
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.mikepenz:aboutlibraries-core:11.2.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
