import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
            sinceBuild = "232"
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

val octProjectPath = project.property("org.typefox.oct-project-path")

tasks.register<Copy>("copyExecutableToResources") {
    dependsOn("createServiceProcessExecutable")
    var executableName = "oct-service-process"
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        executableName = "$executableName.exe"
    }
    from("${octProjectPath}/packages/open-collaboration-service-process/bin/$executableName")
    into("$projectDir/src/main/resources/bin")
}

tasks.register<Exec>("createServiceProcessExecutable") {
    workingDir = file("${octProjectPath}/packages/open-collaboration-service-process")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npm", "run", "create:executable")
    } else {
        commandLine("bash", "-il", "-c", "npm run create:executable")
    }
}

