package execution

private const val CHECKPOINT_COORDINATOR = "CheckpointCoordinator.java"

/**
 * Backward-compatible entrypoint used by the original Run Test Script action.
 * Keep it focused on the known-good Rename scenario; each other scenario has
 * its own IDE action below.
 */
fun runTestScript(executor: InProcessGuiExecutor) {
    runRenameTest(executor)
}

fun runRenameTest(executor: InProcessGuiExecutor) {
    testStep("Rename")
    executor.openFile(CHECKPOINT_COORDINATOR)
    executor.moveCaretToSymbol("completePendingCheckpoint")
    openRefactorMenu(executor)
    executor.clickMenuItemByLabel("Rename")
    executor.typeInDialog("finalizePendingCheckpoint", clearFirst = true)
    executor.clickRefactorButton()
    testComplete("Rename")
}

/**
 * Changes a private method's visibility. Avoid methods with a `throws` clause:
 * Change Signature re-validates every exception type, and unresolved names
 * such as CheckpointException abort the dialog instead of applying.
 */
fun runChangeSignatureVisibilityTest(executor: InProcessGuiExecutor) {
    testStep("Change Signature · Visibility")
    val method = openChangeSignature(executor, ::pickVisibilityChangeTarget)
    executor.selectDropdownValue("Visibility", "Package-private")
    executor.clickRefactorButton()
    testComplete("Change Signature · Visibility ($method)")
}

fun runChangeSignatureNameTest(executor: InProcessGuiExecutor) {
    testStep("Change Signature · Name")
    val method = openChangeSignature(executor, ::pickVisibilityChangeTarget)
    val newName = "${method}Renamed"
    executor.typeInLabeledField("Name", newName)
    executor.clickRefactorButton()
    testComplete("Change Signature · Name ($method → $newName)")
}

fun runChangeSignatureReturnTypeTest(executor: InProcessGuiExecutor) {
    testStep("Change Signature · Return Type")
    val (method, newReturnType) = openChangeSignatureWithReturnType(executor)
    executor.typeInLabeledField("Return type", newReturnType)
    executor.clickRefactorButton()
    testComplete("Change Signature · Return Type ($method → $newReturnType)")
}

fun runChangeSignatureParametersTest(executor: InProcessGuiExecutor) {
    testStep("Change Signature · Parameters")
    val method = openChangeSignature(executor, ::pickMethodWithParameter)
    executor.setParameterName(0, "renamedParam")
    executor.clickRefactorButton()
    testComplete("Change Signature · Parameters ($method)")
}

private fun openChangeSignature(
    executor: InProcessGuiExecutor,
    pickMethod: (String) -> String,
): String {
    executor.openFile(CHECKPOINT_COORDINATOR)
    executor.waitUntilIndexed()
    val source = executor.getDocumentText() ?: error("No active editor document")
    val method = pickMethod(source)
    println("    Change Signature target method='$method'")
    executor.moveCaretToSymbol(method)
    openRefactorMenu(executor)
    executor.clickMenuItemByLabel("Change Signature")
    return method
}

private fun openChangeSignatureWithReturnType(executor: InProcessGuiExecutor): Pair<String, String> {
    executor.openFile(CHECKPOINT_COORDINATOR)
    executor.waitUntilIndexed()
    val source = executor.getDocumentText() ?: error("No active editor document")
    val (method, newReturnType) = pickReturnTypeChange(source)
    println("    Change Signature target method='$method' returnType='$newReturnType'")
    executor.moveCaretToSymbol(method)
    openRefactorMenu(executor)
    executor.clickMenuItemByLabel("Change Signature")
    return method to newReturnType
}

private fun pickVisibilityChangeTarget(source: String): String {
    val preferred =
        listOf(
            "reportCompletedCheckpoint",
            "onTriggerSuccess",
            "cancelPeriodicTrigger",
        )
    return preferred.firstOrNull { name -> isPrivateMethodWithoutThrows(source, name) }
        ?: findPrivateMethodWithoutThrows(source)
}

private fun pickMethodWithParameter(source: String): String {
    val preferred =
        listOf(
            "reportCompletedCheckpoint",
            "rememberRecentExpiredCheckpointId",
            "dropSubsumedCheckpoints",
        )
    return preferred.firstOrNull { name ->
        isPrivateMethodWithoutThrows(source, name) && methodHasParameters(source, name)
    } ?: findPrivateMethodWithoutThrows(source) { name, signatureStart ->
        methodHasParametersAt(source, signatureStart)
    }
}

private fun pickReturnTypeChange(source: String): Pair<String, String> {
    val candidates =
        listOf(
            "maybeCompleteCheckpoint" to ("boolean" to "Boolean"),
            "getCurrentCheckpointInterval" to ("long" to "Long"),
            "getNumberOfRegisteredMasterHooks" to ("int" to "Integer"),
        )
    for ((name, types) in candidates) {
        val (current, replacement) = types
        if (isPrivateMethodWithReturnType(source, name, current)) {
            return name to replacement
        }
    }
    val booleanMethod = findPrivateMethodWithReturnType(source, "boolean")
    if (booleanMethod != null) return booleanMethod to "Boolean"
    val longMethod = findPrivateMethodWithReturnType(source, "long")
    if (longMethod != null) return longMethod to "Long"
    error("No private primitive-return method without a throws clause in $CHECKPOINT_COORDINATOR")
}

private fun isPrivateMethodWithoutThrows(
    source: String,
    name: String,
): Boolean {
    val match = privateMethodRegex(Regex.escape(name)).find(source) ?: return false
    return signatureHasNoThrows(source, match.range.first)
}

private fun isPrivateMethodWithReturnType(
    source: String,
    name: String,
    returnType: String,
): Boolean {
    val match =
        Regex(
            """\bprivate\s+(?:static\s+)?(?:synchronized\s+)?${Regex.escape(returnType)}\s+${Regex.escape(name)}\s*\(""",
        ).find(source) ?: return false
    return signatureHasNoThrows(source, match.range.first)
}

private fun findPrivateMethodWithReturnType(
    source: String,
    returnType: String,
): String? {
    val pattern =
        Regex(
            """\bprivate\s+(?:static\s+)?(?:synchronized\s+)?${Regex.escape(returnType)}\s+(\w+)\s*\(""",
        )
    for (match in pattern.findAll(source)) {
        val name = match.groupValues[1]
        if (name.isEmpty() || name[0].isUpperCase()) continue
        if (signatureHasNoThrows(source, match.range.first)) return name
    }
    return null
}

private fun findPrivateMethodWithoutThrows(
    source: String,
    extra: (String, Int) -> Boolean = { _, _ -> true },
): String {
    val pattern = privateMethodRegex("(\\w+)")
    for (match in pattern.findAll(source)) {
        val name = match.groupValues[1]
        if (name.isEmpty() || name[0].isUpperCase()) continue
        if (!signatureHasNoThrows(source, match.range.first)) continue
        if (extra(name, match.range.first)) return name
    }
    error("No matching private method without a throws clause in $CHECKPOINT_COORDINATOR")
}

private fun methodHasParameters(
    source: String,
    name: String,
): Boolean {
    val match = privateMethodRegex(Regex.escape(name)).find(source) ?: return false
    return methodHasParametersAt(source, match.range.first)
}

private fun methodHasParametersAt(
    source: String,
    signatureStart: Int,
): Boolean {
    val open = source.indexOf('(', signatureStart)
    val close = source.indexOf(')', open)
    if (open < 0 || close < 0) return false
    return source.substring(open + 1, close).any { it.isLetterOrDigit() }
}

private fun privateMethodRegex(namePattern: String): Regex =
    Regex(
        """\bprivate\s+(?:static\s+)?(?:synchronized\s+)?[\w.<>,\s\[\]]+\s+$namePattern\s*\(""",
    )

private fun signatureHasNoThrows(
    source: String,
    signatureStart: Int,
): Boolean {
    val brace = source.indexOf('{', signatureStart)
    if (brace < 0) return false
    return "throws" !in source.substring(signatureStart, brace)
}

private fun openRefactorMenu(executor: InProcessGuiExecutor) {
    check(executor.openContextMenu()) { "Could not open the editor context menu" }
    executor.clickMenuItemByLabel("Refactor")
}

private fun testStep(name: String) {
    println("=== Test Script: $name ===")
}

private fun testComplete(name: String) {
    println("=== Test Script Complete: $name ===")
}
