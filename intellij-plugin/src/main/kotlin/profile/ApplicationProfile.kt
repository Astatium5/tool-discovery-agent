package profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent mapping from application-specific UI class names to semantic roles.
 *
 * Built once per application by [UIProfiler] and persisted to JSON.
 * Every layer that needs to interpret the UI tree (parser, snapshot builder,
 * discovery agent) queries this profile instead of checking hardcoded class names.
 *
 * The profile is mutable at runtime to support progressive enrichment:
 * when the agent encounters a class it has never seen, [UIProfiler] classifies
 * it on the fly and merges the result back into the profile.
 */
@Serializable
data class ApplicationProfile(
    val appName: String,
    val profileVersion: String = "1.0",
    val createdAt: Long = System.currentTimeMillis(),
    val classRoles: MutableMap<String, ComponentRole> = mutableMapOf(),
) {
    // ── Role queries ────────────────────────────────────────────────────────

    fun roleOf(cls: String): ComponentRole = classRoles[cls] ?: ComponentRole.UNKNOWN

    fun classesFor(vararg roles: ComponentRole): Set<String> = classRoles.filterValues { it in roles }.keys

    // ── Convenience sets (replace hardcoded constants) ───────────────────────

    fun popupClasses(): Set<String> = classesFor(ComponentRole.POPUP_WINDOW)

    fun dialogClasses(): Set<String> = classesFor(ComponentRole.DIALOG)

    fun menuItemClasses(): Set<String> = classesFor(ComponentRole.MENU_ITEM, ComponentRole.MENU_CONTAINER)

    fun buttonClasses(): Set<String> = classesFor(ComponentRole.BUTTON)

    fun textInputClasses(): Set<String> =
        classesFor(
            ComponentRole.TEXT_FIELD,
            ComponentRole.TEXT_AREA,
            ComponentRole.EDITOR,
        )

    fun dialogInteractiveClasses(): Set<String> =
        classesFor(
            ComponentRole.MENU_ITEM, ComponentRole.MENU_CONTAINER,
            ComponentRole.BUTTON, ComponentRole.TEXT_FIELD, ComponentRole.TEXT_AREA,
            ComponentRole.EDITOR, ComponentRole.CHECKBOX, ComponentRole.DROPDOWN,
            ComponentRole.TABLE, ComponentRole.LIST,
        )

    // ── Predicate helpers (replace `cls in HARDCODED_SET` checks) ────────────

    fun isPopupWindow(cls: String) = roleOf(cls) == ComponentRole.POPUP_WINDOW

    fun isDialog(cls: String) = roleOf(cls) == ComponentRole.DIALOG

    fun isMenuItem(cls: String) = ComponentRole.isMenu(roleOf(cls))

    fun isButton(cls: String) = roleOf(cls) == ComponentRole.BUTTON

    fun isTextInput(cls: String) = ComponentRole.isTextInput(roleOf(cls))

    fun isEditor(cls: String) = roleOf(cls) == ComponentRole.EDITOR

    fun isList(cls: String) = roleOf(cls) == ComponentRole.LIST

    fun isTable(cls: String) = roleOf(cls) == ComponentRole.TABLE

    fun isTree(cls: String) = roleOf(cls) == ComponentRole.TREE

    fun isCheckbox(cls: String) = roleOf(cls) == ComponentRole.CHECKBOX

    fun isDropdown(cls: String) = roleOf(cls) == ComponentRole.DROPDOWN

    /** True for classes that are pure layout containers (collapse in tree). */
    fun isLayoutContainer(cls: String) = ComponentRole.isLayout(roleOf(cls))

    /** True for classes that carry semantic meaning (keep in parsed tree). */
    fun isSignificantClass(cls: String): Boolean {
        val role = roleOf(cls)
        return role != ComponentRole.UNKNOWN && !ComponentRole.isLayout(role)
    }

    /** True for classes that indicate a submenu (children are menu items). */
    fun hasSubmenuIndicator(cls: String) = roleOf(cls) == ComponentRole.MENU_CONTAINER

    // ── Mutation (progressive enrichment) ───────────────────────────────────

    fun merge(newMappings: Map<String, ComponentRole>) {
        classRoles.putAll(newMappings)
    }

    fun unknownClasses(observed: Set<String>): Set<String> = observed.filter { roleOf(it) == ComponentRole.UNKNOWN }.toSet()

    // ── Persistence ─────────────────────────────────────────────────────────

    fun saveToFile(path: String) {
        val dir = File(path).parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        File(path).writeText(json.encodeToString(this))
        println("ApplicationProfile: saved ${classRoles.size} class mappings to $path")
    }

    companion object {
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        fun loadFromFile(path: String): ApplicationProfile? {
            val file = File(path)
            if (!file.exists()) return null
            return try {
                val profile = json.decodeFromString<ApplicationProfile>(file.readText())
                println("ApplicationProfile: loaded ${profile.classRoles.size} class mappings from $path")
                profile
            } catch (e: Exception) {
                println("ApplicationProfile: failed to load from $path: ${e.message}")
                null
            }
        }
    }
}
