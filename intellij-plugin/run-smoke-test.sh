#!/bin/bash
# Smoke test runner for GraphAgent
# Ensures IntelliJ is focused before running the test

set -e

echo "=== GraphAgent Smoke Test ==="
echo ""
echo "Prerequisites check:"
echo "  1. IntelliJ running with Remote Robot on port 8082"
echo "  2. ANY project open in IntelliJ"
echo ""

# Check if Remote Robot is responding
echo "Checking Remote Robot connection..."
if ! curl -s http://localhost:8082 > /dev/null 2>&1; then
    echo "❌ ERROR: Remote Robot not responding on http://localhost:8082"
    echo "   Please start IntelliJ with Remote Robot plugin"
    exit 1
fi
echo "✓ Remote Robot is responding"

# Get the current window title
WINDOW_TITLE=$(curl -s http://localhost:8082 | grep -o 'title="[^"]*"' | head -1 | cut -d'"' -f2)
echo "✓ Connected to IntelliJ: $WINDOW_TITLE"
echo ""

# Focus IntelliJ window using AppleScript (macOS)
if [[ "$OSTYPE" == "darwin"* ]]; then
    echo "Bringing IntelliJ to front..."
    osascript -e 'tell application "IntelliJ IDEA" to activate' 2>/dev/null || \
    osascript -e 'tell application "JetBrains IntelliJ IDEA" to activate' 2>/dev/null || \
    echo "⚠️  Could not automatically focus IntelliJ - please click on it manually"

    # Wait a moment for window to focus
    sleep 1
    echo "✓ IntelliJ should be focused now"
    echo ""
fi

echo "Running smoke test..."
echo ""
./gradlew test --tests "GraphAgentSmokeTest*" "$@"

echo ""
echo "=== Test Complete ==="
echo "Check the output above for results."
echo "Graph data saved to: data/knowledge_graph_smoke_test.json"
