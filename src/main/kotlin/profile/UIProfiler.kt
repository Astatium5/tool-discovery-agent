package profile

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import execution.UiExecutor
import perception.parser.UiTreeProvider

/**
 * Multi-phase profiling agent that discovers and classifies UI component classes.
 *
 * Phase 1 (idle snapshot):     Fetch the idle UI tree and classify all visible classes.
 * Phase 2 (interactive probe): Open a context menu and a dialog to reveal classes that
 *                               only appear in popups/menus/dialogs (e.g., HeavyWeightWindow,
 *                               ActionMenuItem, JButton). Requires [executor].
 * Phase 3 (gap inference):     If any critical roles are still unrepresented, ask the LLM
 *                               to infer likely class names based on the toolkit it identified.
 * Phase 4 (runtime enrichment): Already in IntentDrivenDiscoveryAgent.enrichProfileIfNeeded().
 *                               Classifies unknown classes on-the-fly during discovery.
 *
 * @param treeProvider  abstraction over how the UI tree is fetched and parsed
 * @param llm           LLM client for classification prompts
 * @param profilePath   where to persist the profile JSON
 * @param executor      optional — needed for Phase 2 interactive probing.
 *                      When null, Phase 2 is skipped.
 */
class UIProfiler(
    private val treeProvider: UiTreeProvider,
    private val llm: ChatModel,
    private val profilePath: String = "build/reports/app-profile.json",
    private val executor: UiExecutor? = null,
) {
    companion object {
        private const val MAX_CLASSES_PER_BATCH = 60

        private val CRITICAL_ROLES =
            setOf(
                ComponentRole.POPUP_WINDOW,
                ComponentRole.DIALOG,
                ComponentRole.MENU_ITEM,
                ComponentRole.MENU_CONTAINER,
                ComponentRole.BUTTON,
                ComponentRole.TEXT_FIELD,
                ComponentRole.CHECKBOX,
                ComponentRole.DROPDOWN,
                ComponentRole.LIST,
                ComponentRole.TABLE,
            )

        private const val SYSTEM_PROMPT = """You are a UI toolkit analyst. Given a list of UI component class names with structural context from a desktop application's accessibility / component tree, classify each into exactly one semantic role.

Return ONLY a JSON array. Each entry has "class", "role", and "is_layout" (true for pure layout containers that should be collapsed when summarizing the tree).

Valid roles:
POPUP_WINDOW, DIALOG, MENU_ITEM, MENU_CONTAINER, BUTTON, TEXT_FIELD, TEXT_AREA, EDITOR, CHECKBOX, DROPDOWN, LIST, TABLE, TREE, LABEL, SEPARATOR, TOOLBAR, TAB, PANEL, SCROLL_PANE, STATUS_BAR, FRAME, UNKNOWN"""

        private const val CLASSIFICATION_PROMPT_TEMPLATE = """Classify each component class below into a semantic role.

For each class you receive:
- "class": the toolkit-specific class name
- "count": how many instances appeared in the tree
- "parents": typical parent class names
- "children": typical child class names
- "has_text": whether instances typically carry visible text
- "has_accessible_name": whether instances have an accessibility label

Rules:
- POPUP_WINDOW = floating overlay that appears on top of the main window (menus, popups)
- DIALOG = modal or modeless dialog container
- MENU_ITEM = clickable leaf item inside a menu
- MENU_CONTAINER = a menu header that can open a sub-menu (has children that are MENU_ITEMs)
- BUTTON = a pushable button
- TEXT_FIELD = single-line text input
- TEXT_AREA = multi-line text input
- EDITOR = a code/text editor area (usually large, contains document text)
- CHECKBOX = a toggle checkbox
- DROPDOWN = combo box / drop-down selector
- LIST = a scrollable list of selectable items
- TABLE = a data grid / table
- TREE = a hierarchical tree view
- LABEL = static text or icon
- SEPARATOR = visual divider between groups
- TOOLBAR = a row/column of action buttons
- TAB = a tab header in a tab strip
- PANEL = generic layout container (set is_layout=true)
- SCROLL_PANE = scrollable wrapper (set is_layout=true)
- STATUS_BAR = a status bar at the bottom of a window
- FRAME = the top-level application frame
- UNKNOWN = cannot determine

Classes to classify:
{{CLASSES}}

Return JSON array only, no markdown fences:
[{"class": "...", "role": "...", "is_layout": true/false}, ...]"""

        private const val GAP_INFERENCE_PROMPT_TEMPLATE = """You just classified component classes from a desktop application's UI tree.
The classes you saw came from this toolkit/framework: {{TOOLKIT_HINT}}

The following semantic roles had NO classes assigned to them:
{{MISSING_ROLES}}

These roles typically only appear when menus, dialogs, or popups are open — states that were not captured during profiling.

Based on the toolkit you identified, predict the class names that would fill each missing role.
Only predict classes that are standard/common in that toolkit. Do NOT invent names.

Return JSON array only, no markdown fences:
[{"class": "ClassName", "role": "ROLE_NAME", "is_layout": false}, ...]"""
    }

    /**
     * Structural context collected for each unique class name.
     * Format-agnostic — populated by [UiTreeProvider.fetchClassContexts].
     */
    data class ClassContext(
        val className: String,
        var count: Int = 0,
        val parents: MutableSet<String> = mutableSetOf(),
        val children: MutableSet<String> = mutableSetOf(),
        var hasText: Boolean = false,
        var hasAccessibleName: Boolean = false,
        var hasTooltip: Boolean = false,
    )

    // ── Public API ──────────────────────────────────────────────────────────

    fun loadOrBuild(appName: String = "Unknown Application"): ApplicationProfile {
        ApplicationProfile.loadFromFile(profilePath)?.let { return it }
        println("UIProfiler: no cached profile at $profilePath — running first-run profiling")
        return buildProfile(appName)
    }

    /**
     * Full multi-phase profiling pass.
     */
    fun buildProfile(appName: String = "Unknown Application"): ApplicationProfile {
        println("\n=== UI PROFILER: Building Application Profile ===")

        // ── Phase 1: Idle snapshot ──────────────────────────────────────────
        println("  Phase 1: Idle UI tree snapshot")
        val allContexts = treeProvider.fetchClassContexts().toMutableMap()
        println("    Discovered ${allContexts.size} unique classes from idle state")

        // ── Phase 2: Interactive probing ────────────────────────────────────
        if (executor != null) {
            println("  Phase 2: Interactive UI probing")
            val interactiveContexts = probeInteractiveStates()
            val newClasses = interactiveContexts.keys - allContexts.keys
            println("    Discovered ${newClasses.size} new classes from interactive states: ${newClasses.take(10)}")
            mergeContexts(allContexts, interactiveContexts)
            println("    Total unique classes: ${allContexts.size}")
        } else {
            println("  Phase 2: Skipped (no UiExecutor provided)")
        }

        // Classify everything discovered so far
        val classifications = classifyAll(allContexts.values.toList()).toMutableMap()
        println("  LLM classified ${classifications.size} classes")

        // ── Phase 3: Gap inference ──────────────────────────────────────────
        val foundRoles = classifications.values.toSet()
        val missingRoles = CRITICAL_ROLES - foundRoles
        if (missingRoles.isNotEmpty()) {
            println("  Phase 3: Inferring classes for ${missingRoles.size} missing roles: ${missingRoles.map { it.name }}")
            val toolkitHint = inferToolkit(allContexts.keys)
            val inferred = inferMissingClasses(missingRoles, toolkitHint)
            println("    LLM inferred ${inferred.size} additional class mappings")
            classifications.putAll(inferred)
        } else {
            println("  Phase 3: All critical roles covered — no inference needed")
        }

        val profile =
            ApplicationProfile(
                appName = appName,
                classRoles = classifications.toMutableMap(),
            )
        profile.saveToFile(profilePath)

        println("\n  === Profile Summary ===")
        for (role in ComponentRole.entries) {
            val classes = profile.classesFor(role)
            if (classes.isNotEmpty()) {
                println("    ${role.name}: $classes")
            }
        }
        println("=== UI PROFILER: Profile complete (${profile.classRoles.size} mappings) ===\n")
        return profile
    }

    /**
     * Incrementally classify classes not yet in the profile (Phase 4: runtime).
     */
    fun classifyNewClasses(
        profile: ApplicationProfile,
        unknowns: Set<String>,
        contexts: Map<String, ClassContext>? = null,
    ): Map<String, ComponentRole> {
        if (unknowns.isEmpty()) return emptyMap()

        println("  UIProfiler: classifying ${unknowns.size} new classes on-the-fly")

        val contextList =
            if (contexts != null) {
                unknowns.mapNotNull { contexts[it] }
            } else {
                unknowns.map { ClassContext(className = it, count = 1) }
            }

        val newMappings = classifyAll(contextList)
        profile.merge(newMappings)
        profile.saveToFile(profilePath)
        return newMappings
    }

    // ── Phase 2: Interactive probing ────────────────────────────────────────

    private fun probeInteractiveStates(): Map<String, ClassContext> {
        val exec = executor ?: return emptyMap()
        val allNewContexts = mutableMapOf<String, ClassContext>()

        // Probe 1: Context menu → reveals HeavyWeightWindow, ActionMenuItem, ActionMenu
        try {
            println("    Probe: Opening context menu...")
            exec.focusEditor()
            Thread.sleep(300)
            exec.openContextMenu()
            Thread.sleep(800)

            val menuContexts = treeProvider.fetchClassContexts()
            mergeContexts(allNewContexts, menuContexts)
            println("      Context menu snapshot: ${menuContexts.size} classes")

            exec.dismissPopups()
            Thread.sleep(300)
        } catch (e: Exception) {
            println("      Context menu probe failed: ${e.message}")
            try {
                exec.dismissPopups()
            } catch (_: Exception) {
            }
        }

        // Probe 2: Search dialog → reveals DialogRootPane, JTextField, JButton, JBList
        try {
            println("    Probe: Opening search dialog (Cmd+Shift+O)...")
            exec.pressShortcut("Meta+Shift+O")
            Thread.sleep(1000)

            val dialogContexts = treeProvider.fetchClassContexts()
            mergeContexts(allNewContexts, dialogContexts)
            println("      Dialog snapshot: ${dialogContexts.size} classes")

            exec.pressEscape()
            Thread.sleep(300)
        } catch (e: Exception) {
            println("      Dialog probe failed: ${e.message}")
            try {
                exec.dismissPopups()
            } catch (_: Exception) {
            }
        }

        return allNewContexts
    }

    private fun mergeContexts(
        target: MutableMap<String, ClassContext>,
        source: Map<String, ClassContext>,
    ) {
        for ((cls, srcCtx) in source) {
            val existing = target[cls]
            if (existing != null) {
                existing.count += srcCtx.count
                existing.parents.addAll(srcCtx.parents)
                existing.children.addAll(srcCtx.children)
                existing.hasText = existing.hasText || srcCtx.hasText
                existing.hasAccessibleName = existing.hasAccessibleName || srcCtx.hasAccessibleName
                existing.hasTooltip = existing.hasTooltip || srcCtx.hasTooltip
            } else {
                target[cls] =
                    ClassContext(
                        className = srcCtx.className,
                        count = srcCtx.count,
                        parents = srcCtx.parents.toMutableSet(),
                        children = srcCtx.children.toMutableSet(),
                        hasText = srcCtx.hasText,
                        hasAccessibleName = srcCtx.hasAccessibleName,
                        hasTooltip = srcCtx.hasTooltip,
                    )
            }
        }
    }

    // ── Phase 3: Gap inference ──────────────────────────────────────────────

    private fun inferToolkit(classNames: Set<String>): String {
        val hasJB = classNames.any { it.startsWith("JB") || it.startsWith("J") }
        val hasAction = classNames.any { it.startsWith("Action") }
        val hasIntelliJ = classNames.any { it.contains("Ide") || it.contains("IntelliJ") }

        return when {
            hasIntelliJ && hasAction -> "Java Swing + IntelliJ Platform (JetBrains IDE)"
            hasJB -> "Java Swing + JetBrains custom components"
            classNames.any { it.contains("gtk", ignoreCase = true) } -> "GTK"
            classNames.any { it.contains("electron", ignoreCase = true) } -> "Electron"
            else -> "Unknown desktop UI toolkit (class names seen: ${classNames.take(10).joinToString(", ")})"
        }
    }

    private fun inferMissingClasses(
        missingRoles: Set<ComponentRole>,
        toolkitHint: String,
    ): Map<String, ComponentRole> {
        val rolesDescription =
            missingRoles.joinToString("\n") { role ->
                "- ${role.name}: ${describeRole(role)}"
            }

        val prompt =
            GAP_INFERENCE_PROMPT_TEMPLATE
                .replace("{{TOOLKIT_HINT}}", toolkitHint)
                .replace("{{MISSING_ROLES}}", rolesDescription)

        return try {
            val response = llm.chat(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(prompt),
            )
            val parsed = parseClassifications(response.aiMessage().text())
            println("      Inferred: ${parsed.entries.joinToString(", ") { "${it.key} -> ${it.value}" }}")
            parsed
        } catch (e: Exception) {
            println("      Gap inference failed: ${e.message}")
            emptyMap()
        }
    }

    private fun describeRole(role: ComponentRole): String =
        when (role) {
            ComponentRole.POPUP_WINDOW -> "floating overlay window for menus/popups"
            ComponentRole.DIALOG -> "modal or modeless dialog container"
            ComponentRole.MENU_ITEM -> "clickable leaf item in a menu"
            ComponentRole.MENU_CONTAINER -> "menu header that opens a submenu"
            ComponentRole.BUTTON -> "pushable button in dialogs/toolbars"
            ComponentRole.TEXT_FIELD -> "single-line text input field"
            ComponentRole.CHECKBOX -> "toggle checkbox"
            ComponentRole.DROPDOWN -> "combo box / drop-down selector"
            ComponentRole.LIST -> "scrollable list of selectable items"
            ComponentRole.TABLE -> "data grid / table with rows and columns"
            else -> role.name.lowercase().replace("_", " ")
        }

    // ── LLM classification ──────────────────────────────────────────────────

    private fun classifyAll(contexts: List<ClassContext>): Map<String, ComponentRole> {
        val result = mutableMapOf<String, ComponentRole>()

        for (batch in contexts.chunked(MAX_CLASSES_PER_BATCH)) {
            val batchResult = classifyBatch(batch)
            result.putAll(batchResult)
        }

        return result
    }

    private fun classifyBatch(batch: List<ClassContext>): Map<String, ComponentRole> {
        val classesBlock =
            batch.mapIndexed { i, ctx ->
                buildString {
                    append("${i + 1}. \"${ctx.className}\" (${ctx.count} instances)")
                    append("\n   Parents: [${ctx.parents.take(5).joinToString(", ")}]")
                    append("  Children: [${ctx.children.take(8).joinToString(", ")}]")
                    append("\n   has_text: ${ctx.hasText}")
                    append(", has_accessible_name: ${ctx.hasAccessibleName}")
                }
            }.joinToString("\n\n")

        val prompt = CLASSIFICATION_PROMPT_TEMPLATE.replace("{{CLASSES}}", classesBlock)

        return try {
            val response = llm.chat(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(prompt),
            )
            parseClassifications(response.aiMessage().text())
        } catch (e: Exception) {
            println("  UIProfiler: LLM classification failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseClassifications(response: String): Map<String, ComponentRole> {
        val result = mutableMapOf<String, ComponentRole>()

        val cleaned =
            response
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

        val entryPattern =
            Regex(
                """\{\s*"class"\s*:\s*"([^"]+)"\s*,\s*"role"\s*:\s*"([^"]+)"\s*,\s*"is_layout"\s*:\s*(true|false)\s*\}""",
            )

        for (match in entryPattern.findAll(cleaned)) {
            val className = match.groupValues[1]
            val roleName = match.groupValues[2]
            val isLayout = match.groupValues[3].toBoolean()

            val role =
                if (isLayout && roleName !in listOf("PANEL", "SCROLL_PANE")) {
                    ComponentRole.PANEL
                } else {
                    ComponentRole.fromString(roleName)
                }

            result[className] = role
        }

        return result
    }
}
