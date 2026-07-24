package execution

import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.accessibility.Accessible
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JRootPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.text.JTextComponent

/**
 * Utility for traversing the Swing component hierarchy to locate UI elements.
 *
 * Replaces Remote Robot's XPath-based component search with in-process tree
 * walking. All operations are pure Swing API calls — no HTTP, no JS bridge.
 *
 * EDT note: callers must be on the EDT when invoking these methods. If you are
 * not on the EDT, dispatch via [InProcessGuiExecutor.runOnEdt] first.
 */
object ComponentTreeWalker {
    /**
     * Find the first component in the tree rooted at [root] that is an instance
     * of [type]. Performs a depth-first search. Returns null if not found.
     */
    inline fun <reified T : Component> findComponentByType(root: Component?): T? {
        if (root == null) return null
        return findComponentByType(root, T::class.java)
    }

    /**
     * Java-friendly variant of [findComponentByType] that takes a Class argument.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Component> findComponentByType(
        root: Component?,
        type: Class<T>,
    ): T? {
        if (root == null) return null
        if (type.isInstance(root)) return root as T
        if (root is Container) {
            for (child in root.components) {
                val found = findComponentByType(child, type)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Find all components in the tree rooted at [root] that are instances of
     * [type]. Returns an empty list if none found.
     */
    inline fun <reified T : Component> findAllComponentsByType(root: Component?): List<T> {
        return findAllComponentsByType(root, T::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> findAllComponentsByType(
        root: Component?,
        type: Class<T>,
    ): List<T> {
        val result = mutableListOf<T>()
        collectByType(root, type, result)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Component> collectByType(
        node: Component?,
        type: Class<T>,
        out: MutableList<T>,
    ) {
        if (node == null) return
        if (type.isInstance(node)) out.add(node as T)
        if (node is Container) {
            for (child in node.components) {
                collectByType(child, type, out)
            }
        }
    }

    /**
     * Find a component whose [Accessible.getAccessibleContext] `accessibleName`
     * contains [name] (case-insensitive). Returns the first match or null.
     */
    fun findComponentByAccessibleName(
        root: Component?,
        name: String,
    ): Component? {
        if (root == null) return null
        if (root is Accessible) {
            val accessibleName = root.accessibleContext?.accessibleName
            if (accessibleName != null && accessibleName.contains(name, ignoreCase = true)) {
                return root
            }
        }
        if (root is Container) {
            for (child in root.components) {
                val found = findComponentByAccessibleName(child, name)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Find a button-like component (`JButton`, `JCheckBox`, `JMenuItem`, etc.)
     * whose text contains [label] (case-insensitive). Returns the first match
     * or null.
     */
    fun findComponentByLabelText(
        root: Component?,
        label: String,
    ): AbstractButton? {
        if (root == null) return null
        if (root is AbstractButton) {
            val text = root.text
            if (text != null && text.contains(label, ignoreCase = true)) {
                return root
            }
        }
        if (root is Container) {
            for (child in root.components) {
                val found = findComponentByLabelText(child, label)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Find a `JLabel` whose text contains [labelText] (case-insensitive).
     * Returns the first match or null.
     */
    fun findLabelByText(
        root: Component?,
        labelText: String,
    ): JLabel? {
        if (root == null) return null
        if (root is JLabel) {
            val text = root.text
            if (text != null && text.contains(labelText, ignoreCase = true)) {
                return root
            }
        }
        if (root is Container) {
            for (child in root.components) {
                val found = findLabelByText(child, labelText)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Find the topmost visible, focusable window of [windowType]. Used for
     * dialog discovery — returns the most recently opened dialog.
     */
    fun getTopmostDialog(): JDialog? {
        val windows: Array<Window> = Window.getWindows()
        // Iterate in reverse to find the most recently shown dialog
        for (i in windows.indices.reversed()) {
            val w = windows[i]
            if (w is JDialog && w.isVisible && w.isFocused) {
                return w
            }
        }
        // Fallback: any visible JDialog
        for (i in windows.indices.reversed()) {
            val w = windows[i]
            if (w is JDialog && w.isVisible) {
                return w
            }
        }
        return null
    }

    /**
     * Find the root pane of a window. Returns null if the window has no root pane.
     */
    fun getRootPane(window: Window?): JRootPane? {
        if (window == null) return null
        if (window is JDialog) return window.rootPane
        if (window is javax.swing.JFrame) return window.rootPane
        return null
    }

    /**
     * Find a `JButton` matching [label] inside [root]. Convenience wrapper.
     */
    fun findButton(
        root: Component?,
        label: String,
    ): JButton? {
        if (root == null) return null
        val candidates = findAllComponentsByType<JButton>(root)
        return candidates.firstOrNull { btn ->
            btn.text?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Find a `JTextField` matching [label] inside [root] — either by the
     * field's own accessible name or by a sibling `JLabel`.
     */
    fun findTextField(
        root: Component?,
        label: String,
    ): JTextField? {
        if (root == null) return null
        // 1. Direct accessible name match
        val byName =
            findAllComponentsByType<JTextField>(root).firstOrNull { tf ->
                tf.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true
            }
        if (byName != null) return byName
        // 2. Sibling-label match
        if (root is Container) {
            for (child in root.components) {
                if (child is JLabel && child.text?.contains(label, ignoreCase = true) == true) {
                    val tf = findComponentByType<JTextField>(child.parent)
                    if (tf != null) return tf
                }
                val nested = findTextField(child, label)
                if (nested != null) return nested
            }
        }
        return null
    }

    /**
     * Find a `JCheckBox` matching [label] inside [root] — either by text
     * or by a sibling `JLabel`.
     */
    fun findCheckBox(
        root: Component?,
        label: String,
    ): JCheckBox? {
        if (root == null) return null
        val byText =
            findAllComponentsByType<JCheckBox>(root).firstOrNull { cb ->
                cb.text?.contains(label, ignoreCase = true) == true
            }
        if (byText != null) return byText
        if (root is Container) {
            for (child in root.components) {
                if (child is JLabel && child.text?.contains(label, ignoreCase = true) == true) {
                    val cb = findComponentByType<JCheckBox>(child.parent)
                    if (cb != null) return cb
                }
                val nested = findCheckBox(child, label)
                if (nested != null) return nested
            }
        }
        return null
    }

    /**
     * Find a `JComboBox` matching [label] inside [root].
     */
    fun findComboBox(
        root: Component?,
        label: String,
    ): JComboBox<*>? {
        if (root == null) return null
        if (root is JComboBox<*>) {
            if (label.isEmpty()) return root
        }
        if (root is Container) {
            for (child in root.components) {
                if (child is JLabel && child.text?.contains(label, ignoreCase = true) == true) {
                    val cb = findComponentByType<JComboBox<*>>(child.parent)
                    if (cb != null) return cb
                }
                val nested = findComboBox(child, label)
                if (nested != null) return nested
            }
        }
        return null
    }

    /**
     * Find a `JMenu` by its text inside a menu bar.
     */
    fun findMenuByText(
        menuBar: JMenuBar?,
        text: String,
    ): JMenu? {
        if (menuBar == null) return null
        for (i in 0 until menuBar.menuCount) {
            val menu = menuBar.getMenu(i)
            if (menu != null && menu.text.equals(text, ignoreCase = true)) {
                return menu
            }
        }
        return null
    }

    /**
     * Find a `JMenuItem` by its text inside a `JMenu`'s sub-elements. Recursively
     * searches sub-menus so callers can pass either the top menu or a submenu.
     */
    fun findMenuItemByText(
        menu: JMenu?,
        text: String,
    ): JMenuItem? {
        if (menu == null) return null
        for (i in 0 until menu.itemCount) {
            val item = menu.getItem(i)
            if (item == null) continue
            if (item is JMenu) {
                if (item.text.equals(text, ignoreCase = true)) return item
                val nested = findMenuItemByText(item, text)
                if (nested != null) return nested
            } else {
                if (item.text.equals(text, ignoreCase = true) ||
                    item.text?.contains(text, ignoreCase = true) == true
                ) {
                    return item
                }
            }
        }
        return null
    }

    /**
     * Find a `JMenuItem` by its text inside a `JPopupMenu`. Used for context menus.
     *
     * Walks the entire component tree under the popup, not just its direct
     * children — IntelliJ's popups often wrap menu items in intermediate
     * `JPanel`s, and `JMenu` submenus hold their children in a lazily-built
     * `JPopupMenu` accessible only via `getMenuComponents`. The recursive
     * search picks up items in both layouts, including inside unexpanded
     * submenus (so callers don't need to manually expand "Refactor" to
     * find "Rename...").
     */
    fun findMenuItemInPopup(
        popup: JPopupMenu?,
        text: String,
    ): JMenuItem? {
        if (popup == null) return null
        // Use the recursive finder so we pick up items inside wrapping
        // containers and unexpanded submenus.
        return findAllComponentsByType<JMenuItem>(popup).firstOrNull { item ->
            item.text.equals(text, ignoreCase = true) ||
                item.text?.contains(text, ignoreCase = true) == true
        }
    }

    /**
     * Walk to find the focused editor component. The IntelliJ platform uses
     * `EditorComponentImpl` internally, but the actual class is package-private
     * — we search by ancestor type instead.
     */
    fun findEditorComponent(root: Component?): JComponent? {
        if (root == null) return null
        if (root is JEditorPane) return root
        if (root is JTextArea) return root
        if (root is Container) {
            for (child in root.components) {
                val found = findEditorComponent(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Returns the currently focused `Window`, or null if no window has focus.
     */
    fun getFocusedWindow(): Window? = Window.getWindows().firstOrNull { it.isFocused }

    /**
     * Returns the topmost visible `Window` (any type — frame, dialog, etc.).
     */
    fun getTopmostWindow(): Window? {
        val windows = Window.getWindows()
        for (i in windows.indices.reversed()) {
            val w = windows[i]
            if (w.isVisible) return w
        }
        return null
    }

    /**
     * Find a `JList` by its accessible name or label within [root].
     */
    fun findList(
        root: Component?,
        label: String,
    ): JList<*>? {
        if (root == null) return null
        val all = findAllComponentsByType<JList<*>>(root)
        if (label.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { list ->
            list.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Find a `JTree` by its accessible name or label within [root].
     */
    fun findTree(
        root: Component?,
        label: String,
    ): JTree? {
        if (root == null) return null
        val all = findAllComponentsByType<JTree>(root)
        if (label.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { tree ->
            tree.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Find a `JTable` by its accessible name or label within [root].
     */
    fun findTable(
        root: Component?,
        label: String,
    ): JTable? {
        if (root == null) return null
        val all = findAllComponentsByType<JTable>(root)
        if (label.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { table ->
            table.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Find a `JTextComponent` (any text-editing component) by label.
     */
    fun findTextComponent(
        root: Component?,
        label: String,
    ): JTextComponent? {
        if (root == null) return null
        val all = findAllComponentsByType<JTextComponent>(root)
        if (label.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { tc ->
            tc.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true
        }
    }

    /**
     * Diagnostic helper: print the component tree rooted at [root] up to
     * [maxDepth] levels deep. Useful for debugging dialog structure.
     */
    fun describeTree(
        root: Component?,
        maxDepth: Int = 5,
        currentDepth: Int = 0,
    ): String {
        if (root == null || currentDepth > maxDepth) return ""
        val sb = StringBuilder()
        repeat(currentDepth) { sb.append("  ") }
        val text =
            (root as? AbstractButton)?.text
                ?: (root as? JLabel)?.text
                ?: (root as? JTextComponent)?.text
        sb.append(root.javaClass.simpleName)
        if (!text.isNullOrBlank()) sb.append(" \"$text\"")
        sb.appendLine()
        if (root is Container && currentDepth < maxDepth) {
            for (child in root.components) {
                sb.append(describeTree(child, maxDepth, currentDepth + 1))
            }
        }
        return sb.toString()
    }
}
