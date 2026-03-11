import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.12.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "com.tooldiscovery"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Use Java 17 toolchain (required for IntelliJ Platform)
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Configure Gradle IntelliJ Plugin
intellijPlatform {
    projectName = "tool-discovery-agent"

    pluginConfiguration {
        id = "com.tooldiscovery.agent"
        name = "Tool Discovery Agent"
        version = "0.1.0"
    }
}

dependencies {
    // IntelliJ Platform
    intellijPlatform {
        intellijIdeaCommunity("2023.2")
        pluginVerifier()
    }

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("241.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
