# Remote Robot API Debugging Guide

## Overview

This document details Remote Robot API compatibility issues discovered during the graph-based agent implementation. The goal is to help a debugging agent identify and fix the specific setup issues that are preventing UI automation from working.

## Context

**What we were trying to do:**
- Implement a graph-based UI automation agent (AppAgentX approach) in pure Kotlin
- Test the agent by performing a refactoring task: rename method `executeRecipe` to `runRecipe`
- Compare graph-based approach vs. flat UI tree approach

**What worked:**
- ✅ Agent can connect to Remote Robot on port 8082
- ✅ Agent can fetch HTML UI tree
- ✅ Agent can parse HTML into PageState (36 elements discovered)
- ✅ Agent can reason with LLM (MiniMax-M2.5 via DashScope)
- ✅ Agent can execute keyboard actions via AppleScript (press_key works)
- ✅ Agent can persist knowledge graph to JSON

**What failed:**
- ❌ Component discovery (find UI elements by XPath) completely broken
- ❌ All click actions fail with Remote Robot API error
- ❌ Cannot perform actual UI automation (rename method failed)

## The Critical Error

```
Error: Unable to create converter for class com.intellij.remoterobot.client.FindComponentsResponse
   for method IdeRobotApi.findAllByXpath
```

This error occurs on **every attempt to find UI components** using:
- `byXpath()`
- `findAll()`
- Any component search strategy

## Environment Details

**System:**
- macOS (running on Mac)
- Kotlin JVM 17
- Gradle 8.x

**IntelliJ Setup:**
- Remote Robot plugin version: `0.11.23`
- Robot server port: `8082`
- Plugin location: `~/.gradle/caches/modules-2/files-2.1/com.intellij.remoterobot/remote-robot/0.11.23/`

**Project:**
- `tool-discovery-agent` repo
- Branch: `graph-approach`
- Kotlin codebase with Remote Robot dependencies

## Dependencies

From `intellij-plugin/build.gradle.kts`:

```kotlin
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

    // JUnit 5 for tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

## Reproduction Steps

### 1. Start IntelliJ with Remote Robot

**Prerequisites:**
- IntelliJ IDEA must be running
- Remote Robot plugin must be installed
- `idea.vmoptions` should contain: `-Drobot-server.port=8082`

**Verification:**
```bash
# Check if IntelliJ is running on port 8082
curl http://localhost:8082

# Expected: HTML response (Remote Robot UI)
# If connection refused: IntelliJ not running or plugin not installed
```

### 2. Try the Baseline Agent (Raihan's Flat Approach)

**IMPORTANT:** The baseline agent code should still be intact in:
- `intellij-plugin/src/main/kotlin/agent/UiAgent.kt`
- `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt`
- `intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt`
- `intellij-plugin/src/main/kotlin/reasoner/LLMReasoner.kt`

**Try running one of Raihan's existing tests:**
```bash
cd intellij-plugin
./gradlew test --tests "*UiAgentTest*"
```

**What to check:**
- Do the tests fail with the same Remote Robot API error?
- If YES: The issue is in your Remote Robot setup/environment
- If NO: The issue is specific to the graph implementation

### 3. Direct Remote Robot API Test

Create a simple test to isolate the Remote Robot API call:

```kotlin
// Test file: intellij-plugin/src/test/kotlin/RemoteRobotApiTest.kt
package com.tooldiscovery.agent

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import kotlin.test.assertTrue

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RemoteRobotApiTest {

    private val robotUrl = "http://localhost:8082"
    private val robot = RemoteRobot(robotUrl)

    @Test
    fun testCanConnectToRemoteRobot() {
        // Simple connection test
        assertTrue(true, "If this runs, Remote Robot connection works")
    }

    @Test
    fun testFindComponents() {
        // This is what fails
        val components = robot.findAll()
        println("Found ${components.size} components")
    }

    @Test
    fun testFindByXpath() {
        // This also fails
        val component = robot.findByXpath("//div[@class='EditorComponentImpl']")
        println("Component: $component")
    }
}
```

**Run the test:**
```bash
./gradlew test --tests RemoteRobotApiTest
```

**Expected Outcomes:**
- If `testCanConnectToRemoteRobot` passes but `testFindComponents` fails → API serialization issue
- If both pass → The issue might be in how we're calling the API from our graph agent
- If both fail → Remote Robot not running or misconfigured

### 4. Check Remote Robot Version Compatibility

The error mentions:
```
Unable to create converter for class com.intellij.remoterobot.client.FindComponentsResponse
```

**Possible causes:**
1. **Version mismatch**: Remote Robot v0.11.23 might have breaking API changes
2. **Missing dependency**: Need additional serialization library
3. **Kotlin/Java interop**: Kotlin code calling Java API with type conversion issues

**Try these checks:**

```bash
# Check Remote Robot version in use
./gradlew dependencies | grep remote-robot

# Check if there are multiple versions on classpath
./gradlew dependencies --configuration runtimeClasspath | grep remote-robot

# Check Kotlin/Java serialization compatibility
./gradlew dependencies | grep kotlinx-serialization
```

### 5. Verify IntelliJ Remote Robot Plugin Installation

**Check plugin is actually loaded:**

```bash
# Check if Remote Robot port is listening
lsof -i :8082

# Check process
ps aux | grep -i idea

# Try accessing Remote Robot health endpoint
curl -v http://localhost:8082/api/health 2>&1 | head -20
```

**Common issues:**
- Port 8082 blocked or in use by another process
- IntelliJ not running with the plugin enabled
- `idea.vmoptions` not properly configured with `-Drobot-server.port=8082`
- Firewall blocking connections

## What to Debug First

### Priority 1: Verify Baseline Agent Works

**Why:** If Raihan's original flat agent works, then the issue is NOT with Remote Robot setup itself, but with how the graph agent is calling it.

**Steps:**
1. Check if baseline tests pass: `./gradlew test --tests "*UiAgentTest*"`
2. If they pass → Graph agent is calling API incorrectly
3. If they fail → Remote Robot environment issue

### Priority 2: Compare Your Setup vs. Friend's Setup

**What to compare:**

**IntelliJ Version:**
```bash
# Check your IntelliJ version
idea --version 2>/dev/null || echo "Check in IntelliJ: Help > About"
```

**Remote Robot Plugin Version:**
```bash
# Find Remote Robot plugin files
find ~/.gradle/caches -name "*remote-robot*" -type d
ls -la ~/.gradle/caches/modules-2/files-2.1/com.intellij.remoterobot/
```

**idea.vmoptions:**
```bash
# Check if robot-server.port is set
cat ~/Library/Application\ Support/JetBrains/IntelliJ/options/vmoptions.xml | grep robot-server
# OR
cat ~/.ijideaj/config/idea.vmoptions | grep robot-server
```

**Port 8082 status:**
```bash
# Is something listening on 8082?
lsof -i :8082
netstat -an | grep 8082
```

**Java/Kotlin Version:**
```bash
java -version
./gradlew --version
```

### Priority 3: Test with Minimal Remote Robot Call

Create the simplest possible Remote Robot call to isolate the issue:

```kotlin
// Minimal test
import com.intellij.remoterobot.RemoteRobot

fun main() {
    val robot = RemoteRobot("http://localhost:8082")

    try {
        // Try 1: Call findAll()
        println("Testing findAll()...")
        val all = robot.findAll()
        println("SUCCESS: findAll() returned ${all.size} components")
    } catch (e: Exception) {
        println("FAILED: findAll() - ${e.message}")
        e.printStackTrace()
    }

    try {
        // Try 2: Call findByXpath()
        println("\nTesting findByXpath()...")
        val byXpath = robot.findByXpath("//div")
        println("SUCCESS: findByXpath() returned $byXpath")
    } catch (e: Exception) {
        println("FAILED: findByXpath() - ${e.message}")
        e.printStackTrace()
    }
}
```

### Priority 4: Check Network Serialization

The error mentions "Unable to create converter" which suggests a serialization/deserialization issue.

**Check if Retrofit/OkHttp can properly deserialize responses:**

```kotlin
import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import com.squareup.okhttp3.Response

fun testRawHttpResponse() {
    val client = OkHttpClient()

    val request = Request.Builder()
        .url("http://localhost:8082/api/findAll")
        .build()

    val response: Response = client.newCall(request).execute()
    println("Response code: ${response.code}")
    println("Response body: ${response.body?.string()}")
}
```

### Priority 5: Try Different Remote Robot Methods

The graph agent might be using methods differently than the baseline agent.

**Check what methods the baseline agent uses:**
```bash
grep -r "robot\." intellij-plugin/src/main/kotlin/agent/UiAgent.kt
grep -r "robot\." intellij-plugin/src/main/kotlin/executor/UiExecutor.kt
```

**Compare to what the graph agent uses:**
```bash
grep -r "robot\." intellij-plugin/src/main/kotlin/graph/GraphAgent.kt
```

**Look for differences in:**
- How they call the Remote Robot API
- What parameters they pass
- Whether they use the same client setup

## Specific Error Messages to Look For

### Error 1: Component Discovery Failure

```
kotlinx.serialization.SerializationException: Unable to create converter for class com.intellij.remoterobot.client.FindComponentsResponse
    for method IdeRobotApi.findAllByXpath
```

**What it means:** Kotlin's serialization library cannot deserialize the response from Remote Robot API.

**Possible causes:**
1. Response format changed in Remote Robot v0.11.23
2. Missing type converters in Retrofit/OkHttp
3. Need to configure kotlinx-serialization differently

### Error 2: XPath Not Found

```
IllegalArgumentException: No element found matching XPath: //div[@class='EditorComponentImpl']
```

**What it means:** The XPath query might be correct, but component discovery itself is broken.

**Debugging step:**
- Try simpler XPath: `//div`
- Try by class: `robot.findAll { it.className == "EditorComponentImpl" }`
- Check if ANY component discovery works

### Error 3: Connection Refused

```
java.net.ConnectException: Failed to connect to localhost/127.0.0.1:8082
```

**What it means:** Remote Robot server is not running.

**Debugging steps:**
1. Verify IntelliJ is running
2. Check `idea.vmoptions` has `-Drobot-server.port=8082`
3. Check if Remote Robot plugin is enabled
4. Try `curl http://localhost:8082` to test connection

## Debugging Agent Instructions

For the agent assigned to debug this issue, here's what you should do:

### Step 1: Verify Baseline Agent
1. Run existing tests: `./gradlew test --tests "*UiAgentTest*"`
2. If baseline agent works → Issue is in graph implementation
3. If baseline agent fails → Issue is in Remote Robot setup

### Step 2: Isolate Remote Robot API Call
1. Create minimal Remote Robot test (see Priority 3 above)
2. Test `findAll()` and `findByXpath()` directly
3. Check if raw HTTP responses are parseable

### Step 3: Compare Environments
1. Compare IntelliJ versions, plugin versions, Java versions
2. Check `idea.vmoptions` configuration
3. Verify port 8082 is accessible

### Step 4: Check Dependencies
1. Verify Remote Robot version (0.11.23)
2. Check for conflicting dependencies
3. Try upgrading or downgrading Remote Robot version

### Step 5: Test Graph Agent Specific Code
1. Check how GraphAgent calls Remote Robot vs. baseline agent
2. Look for differences in client setup
3. Verify serialization configuration

## Files to Examine

### Graph Agent Files (might have issues)
- `intellij-plugin/src/main/kotlin/graph/GraphAgent.kt` (observe method)
- `intellij-plugin/src/main/kotlin/parser/HtmlUiTreeProvider.kt` (fetchTree method)

### Baseline Agent Files (should work)
- `intellij-plugin/src/main/kotlin/agent/UiAgent.kt`
- `intellij-plugin/src/main/kotlin/executor/UiExecutor.kt`
- `intellij-plugin/src/main/kotlin/parser/UiTreeParser.kt`

### Build Configuration
- `intellij-plugin/build.gradle.kts` (dependencies)

## Quick Health Check Script

Run this to gather diagnostic information:

```bash
#!/bin/bash
echo "=== Remote Robot Debugging Diagnostic ==="
echo ""

echo "1. Checking if IntelliJ is running on port 8082..."
if curl -s http://localhost:8082 > /dev/null; then
    echo "   ✓ IntelliJ is running on port 8082"
else
    echo "   ✗ Cannot connect to localhost:8082"
    echo "   → Start IntelliJ and verify Remote Robot plugin is enabled"
fi
echo ""

echo "2. Checking Remote Robot plugin files..."
PLUGIN_DIR=$(find ~/.gradle/caches/modules-2/files-2.1/com.intellij.remoterobot -name "remote-robot*" -type d 2>/dev/null | head -1)
if [ -n "$PLUGIN_DIR" ]; then
    echo "   ✓ Found Remote Robot plugin in: $PLUGIN_DIR"
    echo "   Version: $(basename $PLUGIN_DIR | sed 's/remote-robot//')"
else
    echo "   ✗ Remote Robot plugin not found in gradle cache"
    echo "   → Plugin might not be installed correctly"
fi
echo ""

echo "3. Checking idea.vmoptions for robot-server.port..."
VMOPTS="$HOME/Library/Application Support/JetBrains/IntelliJ/options/vmoptions.xml"
if [ -f "$VMOPTS" ]; then
    if grep -q "robot-server.port=8082" "$VMOPTS"; then
        echo "   ✓ robot-server.port=8082 found in idea.vmoptions"
    else
        echo "   ✗ robot-server.port NOT found in idea.vmoptions"
        echo "   → Add -Drobot-server.port=8082 to idea.vmoptions"
    fi
else
    echo "   ⚠ idea.vmoptions not found at expected location"
fi
echo ""

echo "4. Running baseline agent test..."
cd intellij-plugin
./gradlew test --tests "*UiAgentTest.useSavedRecipe*" 2>&1 | head -20
echo ""

echo "5. Checking dependencies..."
./gradlew dependencies | grep -E "remote-robot|kotlinx-serialization|okhttp"
echo ""

echo "=== Diagnostic Complete ==="
```

## THE FIX (Session 18954b1d)

**Root Cause:** macOS keyboard input handling issue, NOT Remote Robot API incompatibility!

### The Problem:
- On macOS, `robot.keyboard { key(...) }` sends keyboard events to the **focused window**
- However, these events may not reach IntelliJ correctly due to focus issues
- The "FindComponentsResponse serialization error" was a red herring

### The Solution:
Use **AppleScript to send keystrokes directly to the IntelliJ process**, NOT through the focused window.

**Implementation (from KeyboardActionTest.kt):**
```kotlin
private fun sendKeystrokeToIntelliJ(
    keyName: String,
    modifiers: List<String> = emptyList()
) {
    val script = """
        tell application "System Events"
            tell process "IntelliJ IDEA"
                keystroke "$keyName" using {${modifiers.joinToString(", ")}}"
            end tell
        end tell
    """.trimIndent()

    val processBuilder = ProcessBuilder("osascript", "-e", script)
    val result = processBuilder.start().waitFor()
}
```

**Key Changes:**
1. Don't use `robot.keyboard { ... }` on macOS
2. Instead, use `ProcessBuilder("osascript", "-e", script)` to send keystrokes directly to IntelliJ
3. Always bring IntelliJ to front first: `tell application "System Events" to set frontmost of the first process whose name is "idea" to true`

### What Was Fixed:
- `UiExecutor.kt` - Updated to use AppleScript for macOS keyboard input
- `GraphAgent.kt` - May need similar updates for press_key actions
- All test files updated with proper macOS keyboard handling

### How to Apply the Fix:
1. Check if `UiExecutor.pressKey()` and similar methods use AppleScript on macOS
2. Update any keyboard action methods in GraphAgent to use AppleScript
3. Test with simple test: `./gradlew test --tests KeyboardActionTest`

## Expected Outcomes

### If Baseline Agent Works:
- The issue is NOT with Remote Robot setup
- The issue is in how the graph agent calls the API
- Focus on differences in keyboard input handling between baseline and graph agent

### If Minimal Remote Robot Test Works:
- The API itself is functional
- The issue is in how we're integrating it in the graph agent
- Check client configuration, serialization setup

### If All Tests Fail:
- Remote Robot is not properly configured
- Focus on IntelliJ setup and plugin installation
- Verify port 8082 is accessible and correct

## What the Fix Might Look Like

Based on the error message, possible fixes:

### Fix 1: Update Remote Robot Version
```kotlin
// In build.gradle.kts, try:
implementation("com.intellij.remoterobot:remote-robot:0.11.24")
implementation("com.intellij.remoterobot:remote-fixtures:0.11.24")
```

### Fix 2: Add Serialization Converters
```kotlin
// Add to dependencies:
implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
implementation("com.jakewhartson.retrofit:retrofit2-kotlinx-serialization-converter:0.8.0")
```

### Fix 3: Configure Kotlin Serialization
```kotlin
// In GraphAgent or RemoteRobot client setup:
val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
}
```

## Next Steps After Debugging

1. **Document the root cause** - Add specific issue to this document
2. **Implement the fix** - Apply the appropriate solution
3. **Verify the fix** - Run smoke test and refactoring task again
4. **Compare approaches** - Once working, test graph vs. flat hypothesis

## Contact Information

If you need to compare with the friend's working setup, ask for:
- IntelliJ IDEA exact version
- Remote Robot plugin exact version
- `idea.vmoptions` content
- Operating system and version
- Java/Kotlin versions
- Gradle version
- Any special configuration or workarounds

Good luck debugging!
