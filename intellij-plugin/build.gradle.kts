import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.12.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

// Load .env file if it exists
val envProps = mutableMapOf<String, String>()
File(rootDir, ".env").takeIf { it.exists() }?.forEachLine { line ->
    val trimmed = line.trim()
    if (trimmed.isNotEmpty() && !trimmed.startsWith("test123#")) {
        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            envProps[parts[0].trim()] = parts[1].trim()
        }
    }
}

group = "com.tooldiscovery"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
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

    // Remote Robot — for UI automation
    // NOTE: Must exclude kotlinx-serialization-converter from Retrofit to avoid conflict
    implementation("com.intellij.remoterobot:remote-robot:0.11.23") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-converter")
        exclude(group = "com.jakewharton.retrofit", module = "retrofit2-kotlinx-serialization-converter")
    }
    implementation("com.intellij.remoterobot:remote-fixtures:0.11.23") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-converter")
        exclude(group = "com.jakewharton.retrofit", module = "retrofit2-kotlinx-serialization-converter")
    }

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Jsoup — HTML parsing for UiTreeParser
    implementation("org.jsoup:jsoup:1.17.2")

    // Kotlinx Serialization — for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // JSON serialization (keep Gson for compatibility)
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // JUnit 5 for tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

// Agent Bridge Server — standalone HTTP server for Python agent integration
tasks.register<JavaExec>("runBridgeServer") {
    group = "application"
    description = "Run the Agent Bridge Server (Python agent calls this to control IntelliJ)"
    dependsOn(tasks.compileKotlin)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("server.AgentBridgeServerKt")
    // Override defaults via env: ROBOT_URL, BRIDGE_PORT
}

// Graph-Enhanced UI Agent — direct Kotlin replacement for Python graph agent
tasks.register<JavaExec>("runGraphAgent") {
    group = "application"
    description = "Run the Graph-Enhanced UI Agent"
    dependsOn(tasks.compileKotlin)
    // Use test classpath to ensure Remote Robot serialization works
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("main.MainKt")
    // Pass task as argument: ./gradlew runGraphAgent --args="your task here"
    // Environment variables are read from .env file via dotdash plugin
}

// Test configuration
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Pass environment variables from .env to tests (fallback to system env)
    environment("ROBOT_URL", providers.environmentVariable("ROBOT_URL").orElse(envProps["ROBOT_URL"] ?: "http://localhost:8082"))
    environment("LLM_BASE_URL", providers.environmentVariable("LLM_BASE_URL").orElse(envProps["LLM_BASE_URL"] ?: "https://coding-intl.dashscope.aliyuncs.com/v1"))
    environment("LLM_MODEL", providers.environmentVariable("LLM_MODEL").orElse(envProps["LLM_MODEL"] ?: "MiniMax-M2.5"))
    environment("LLM_API_KEY", providers.environmentVariable("LLM_API_KEY").orElse(envProps["LLM_API_KEY"] ?: ""))
}

// Remote Robot version for UI testing
val remoteRobotVersion = "0.11.23"

// runIdeForUiTests — starts IDE with robot server for UI testing
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            // Disable split mode — we want a single IDE process
            splitMode.set(false)

            task {
                systemProperty("robot-server.port", "8082")
                systemProperty("ide.show.tips.on.startup.default.value", "false")
                systemProperty("idea.splash", "false")
                systemProperty("ide.check.new.builds.on.startup", "false")
            }

            plugins {
                // Robot server plugin for UI testing
                robotServerPlugin(remoteRobotVersion)
            }
        }
    }
}
