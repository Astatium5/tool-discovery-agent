import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.13.1"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("co.uzzu.dotenv.gradle") version "2.1.0"
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

// Use Java 25 toolchain, targeting 17 for IntelliJ Platform compatibility
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
    implementation("com.intellij.remoterobot:remote-robot:0.11.23")
    implementation("com.intellij.remoterobot:remote-fixtures:0.11.23")

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Jsoup — HTML parsing for UiTreeParser
    implementation("org.jsoup:jsoup:1.17.2")

    // Kotlinx Serialization — for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // JSON serialization (keep Gson for compatibility)
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // LangChain4j — LLM integration
    implementation("dev.langchain4j:langchain4j:1.12.2")
    implementation("dev.langchain4j:langchain4j-open-ai:1.12.2") // OpenAI-compatible API (DashScope, etc.)

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

// Test configuration
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
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
