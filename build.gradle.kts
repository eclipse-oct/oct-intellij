import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Base64

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "org.typefox"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.9.12")
    implementation("org.msgpack:msgpack-core:0.9.9")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")

    intellijPlatform {
        intellijIdea("2026.1.4")
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.28539.54"
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN")?.let {
            String(Base64.getDecoder().decode(it))
        })
        privateKey.set(System.getenv("PRIVATE_KEY")?.let {
            String(Base64.getDecoder().decode(it))
        })
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    prepareSandbox {
        dependsOn("copyExecutableToResources")
    }

    processResources {
        dependsOn("copyExecutableToResources")
    }
}

val octProjectPath = (project.findProperty("org.typefox.oct-project-path") as String?)?.takeIf { it.isNotBlank() }

// oct-service-process can't be built per-OS by a JetBrains plugin release, so when no local
// open-collaboration-tools checkout is configured, download the prebuilt executable for every
// supported OS from the open-collaboration-tools GitHub release instead.
val octServiceProcessVersion = "0.3.0"
val octServiceProcessReleaseUrl =
    "https://github.com/eclipse-oct/open-collaboration-tools/releases/download/service-process-v$octServiceProcessVersion"

data class ServiceProcessTarget(val os: String, val assetSuffix: String, val executableSuffix: String)

val serviceProcessTargets = listOf(
    ServiceProcessTarget("win", "windows-x64.exe", ".exe"),
    ServiceProcessTarget("linux", "linux-x64", ""),
    ServiceProcessTarget("mac", "macos-x64", ""),
)

tasks.register("downloadServiceProcessExecutables") {
    val outputDir = file("$projectDir/src/main/resources/bin")
    val downloads = serviceProcessTargets.map { target ->
        val assetName = "oct-service-process-$octServiceProcessVersion-${target.assetSuffix}"
        val url = "$octServiceProcessReleaseUrl/$assetName"
        val destFile = File(outputDir, "${target.os}/oct-service-process${target.executableSuffix}")
        url to destFile
    }
    doLast {
        downloads.forEach { (url, destFile) ->
            destFile.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.setExecutable(true)
        }
    }
}

tasks.register<Copy>("copyLocalExecutableToResources") {
    dependsOn("createServiceProcessExecutable")
    var executableName = "oct-service-process"
    val currentOs = when {
        Os.isFamily(Os.FAMILY_WINDOWS) -> "win"
        Os.isFamily(Os.FAMILY_MAC) -> "mac"
        else -> "linux"
    }
    if (currentOs == "win") {
        executableName = "$executableName.exe"
    }
    from("${octProjectPath}/packages/open-collaboration-service-process/bin/$executableName")
    into("$projectDir/src/main/resources/bin/$currentOs")
}

tasks.register("copyExecutableToResources") {
    if (octProjectPath == null) {
        dependsOn("downloadServiceProcessExecutables")
    } else {
        dependsOn("copyLocalExecutableToResources")
    }
}

tasks.register<Exec>("createServiceProcessExecutable") {
    workingDir = file("${octProjectPath}/packages/open-collaboration-service-process")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npm", "run", "create:executable")
    } else {
        commandLine("bash", "-il", "-c", "npm run create:executable")
    }
}

