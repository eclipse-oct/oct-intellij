import org.apache.tools.ant.taskdefs.condition.Os
import java.util.Base64

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.9.9")
    implementation("org.msgpack:msgpack-core:0.9.9")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
}

group = "org.typefox"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2.6")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf(/* Plugin Dependencies */))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("242.*")
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
    workingDir = file( "${octProjectPath}/packages/open-collaboration-service-process")
    if (Os.isFamily(Os.FAMILY_WINDOWS)) {
        commandLine("npm", "run", "create:executable")
    } else {
        commandLine("bash", "-il", "-c", "npm run create:executable")
    }
}

