package execution

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractButton
import javax.swing.Action
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JWindow
import javax.swing.ListCellRenderer
import javax.swing.text.JTextComponent

/** Handles in-process interaction with IntelliJ DialogWrapper instances. */
class DialogController(
    private val edtDispatcher: EdtDispatcher,
    private val keyboardDispatcher: KeyboardDispatcher,
) {
    private val acceptingExtraDialogs = AtomicBoolean(false)

    fun findTopmostDialog(): JDialog? = edtDispatcher.findTopmostDialog()

    fun clickDialogButton(label: String) {
        if (isConfirmButtonLabel(label)) {
            clickRefactorButton(preferredLabels = listOf(label, "Refactor", "Rename"))
            return
        }
        withTopmostDialog { dialog ->
            val button =
                ComponentTreeWalker.findButton(dialogContentRoot(dialog), label)
                    ?: throw IllegalStateException("Button '$label' not found in dialog")
            button.doClick()
        }
    }

    fun clickDefaultButton() {
        clickRefactorButton(preferredLabels = listOf("Refactor", "Rename"))
    }

    /**
     * Click the dialog's Refactor/Rename confirm button and wait for the dialog
     * to close. Enter is not reliable on Change Signature; the Refactor action
     * is the same control a user would press.
     */
    fun clickRefactorButton(
        preferredLabels: List<String> = listOf("Refactor", "Rename"),
    ) {
        val dialog = waitForDialog() ?: throw IllegalStateException("No topmost dialog found")
        trace(
            "clickRefactorButton dialog='${dialog.title}' preferred=${preferredLabels.joinToString()}",
        )
        confirmByButton(dialog, preferredLabels)
    }

    fun typeInTextField(
        field: JTextField,
        value: String,
    ) {
        edtDispatcher.runOnEdtAndWait { field.text = value }
    }

    fun typeInTextFieldByLabel(
        label: String,
        value: String,
    ) {
        typeInLabeledField(label, value)
    }

    /**
     * Replace the text of a labeled dialog field. Change Signature uses both
     * `JTextField` (Name) and `EditorTextField` (Return type).
     */
    fun typeInLabeledField(
        label: String,
        value: String,
    ) {
        waitForDialog()
        withTopmostDialog { dialog ->
            val root =
                dialogContentRoot(dialog)
                    ?: throw IllegalStateException("Dialog '${safeDialogTitle(dialog)}' has no content pane")
            val field =
                findLabeledEditableField(root, label)
                    ?: throw IllegalStateException("Field '$label' not found in dialog '${safeDialogTitle(dialog)}'")
            setEditableText(field, value)
            trace("typeInLabeledField label='$label' value='$value' component=${field.javaClass.simpleName}")
        }
    }

    /**
     * Set the Name cell of a parameter row in the Change Signature table.
     * [row] is 0-based and refers to an existing parameter, not the dummy add row.
     */
    fun setParameterName(
        row: Int,
        name: String,
    ) {
        waitForDialog()
        withTopmostDialog { dialog ->
            val root =
                dialogContentRoot(dialog)
                    ?: throw IllegalStateException("Dialog '${safeDialogTitle(dialog)}' has no content pane")
            val table =
                findParametersTable(root)
                    ?: throw IllegalStateException("Parameters table not found in dialog '${safeDialogTitle(dialog)}'")
            val nameColumn =
                (0 until table.columnCount).firstOrNull { index ->
                    table.getColumnName(index).contains("Name", ignoreCase = true)
                } ?: 1
            check(row in 0 until table.rowCount) {
                "Parameter row $row is out of range (rows=${table.rowCount})"
            }
            check(table.editCellAt(row, nameColumn)) {
                "Could not start editing parameters table row=$row column=$nameColumn"
            }
            val editor = table.editorComponent
            if (editor != null) {
                setEditableText(editor, name)
            } else {
                table.setValueAt(name, row, nameColumn)
            }
            table.cellEditor?.stopCellEditing()
            trace("setParameterName row=$row column=$nameColumn value='$name'")
        }
    }

    fun setCheckboxState(
        label: String,
        checked: Boolean,
    ) {
        withTopmostDialog { dialog ->
            val checkbox =
                ComponentTreeWalker.findCheckBox(dialogContentRoot(dialog), label)
                    ?: throw IllegalStateException("Checkbox '$label' not found in dialog")
            checkbox.isSelected = checked
        }
    }

    fun closeTopmostDialog() {
        val dialog = findTopmostDialog() ?: return
        withDialog(dialog) { closeDialog(dialog) }
    }

    fun closeAllDialogs(maxAttempts: Int = 5) {
        repeat(maxAttempts) {
            val dialog = findTopmostDialog() ?: return
            withDialog(dialog) { closeDialog(dialog) }
            edtDispatcher.runOnEdtAndWait { }
        }
    }

    fun selectDropdownValue(
        label: String,
        value: String,
    ) {
        trace("selectDropdownValue start label='$label' value='$value' thread=${Thread.currentThread().name}")
        val found =
            waitForLabeledCombo(label)
                ?: throw IllegalStateException("Dropdown '$label' not found in any visible window")
        edtDispatcher.runOnEdtAndWaitWithModality(edtDispatcher.modalityStateFor(found.window)) {
            val root = windowContentRoot(found.window)
            val combo =
                ComponentTreeWalker.findComboBox(root, label)
                    ?: found.combo
            combo.requestFocusInWindow()
            applyDropdownSelection(combo, label, value)
        }
    }

    fun selectDropdownField(
        label: String,
        value: String,
    ) {
        focusFieldByLabel(label)
        Thread.sleep(200)
        selectDropdownValue(label, value)
    }

    fun setCheckbox(
        label: String,
        checked: Boolean,
    ) = setCheckboxState(label, checked)

    fun focusFieldByLabel(label: String) {
        withTopmostDialog { dialog ->
            val field =
                ComponentTreeWalker.findTextField(dialogContentRoot(dialog), label)
                    ?: throw IllegalStateException("Field for label '$label' not found")
            field.requestFocusInWindow()
        }
    }

    fun focusNextField() {
        edtDispatcher.runOnEdtAndWait {
            val focusOwner = keyboardFocusManagerFocusOwner() ?: return@runOnEdtAndWait
            val container = focusOwner.parent ?: return@runOnEdtAndWait
            container.focusTraversalPolicy?.getComponentAfter(container, focusOwner)?.requestFocusInWindow()
        }
    }

    fun typeInDialog(
        value: String,
        clearFirst: Boolean = true,
    ) {
        trace("typeInDialog start valueLength=${value.length} thread=${Thread.currentThread().name}")
        waitForDialog(timeoutMs = 1000)
        trace("typeInDialog waitForDialog returned")

        val fieldDeadline = System.currentTimeMillis() + 2000
        var attempts = 0
        while (System.currentTimeMillis() < fieldDeadline) {
            attempts++
            if (attempts == 1) trace("typeInDialog scanning for editable field")
            if (setTopmostDialogText(value, clearFirst)) return
            Thread.sleep(50)
        }
        val dialog = findTopmostDialog()
        if (dialog != null) {
            throw IllegalStateException("No editable text field found in dialog ${dialog.title}")
        }

        val focusOwner = keyboardFocusManagerFocusOwner()
        var inTemplateMode = false
        if (focusOwner != null) {
            val isEditorComponent = focusOwner.javaClass.simpleName.contains("Editor", ignoreCase = true)
            if (clearFirst && !isEditorComponent && focusOwner is JTextField) {
                focusOwner.text = ""
            } else if (isEditorComponent) {
                inTemplateMode = true
            }
            val robot = java.awt.Robot()
            for (character in value) {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(java.awt.datatransfer.StringSelection(character.toString()), null)
                val modifier =
                    if (System.getProperty("os.name").lowercase().contains("mac")) {
                        java.awt.event.KeyEvent.VK_META
                    } else {
                        java.awt.event.KeyEvent.VK_CONTROL
                    }
                robot.keyPress(modifier)
                robot.keyPress(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(java.awt.event.KeyEvent.VK_V)
                robot.keyRelease(modifier)
            }
        }

        if (!inTemplateMode) dismissAutocompletePopup()
    }

    private fun setTopmostDialogText(
        value: String,
        clearFirst: Boolean,
    ): Boolean {
        val dialog = findTopmostDialog() ?: return false
        val modalityState = edtDispatcher.modalityStateFor(dialog)
        return edtDispatcher.runOnEdtAndWaitWithModality(modalityState) {
            trace("setTopmostDialogText EDT entered")
            val root = dialogContentRoot(dialog) ?: return@runOnEdtAndWaitWithModality false
            val editorTextField =
                ComponentTreeWalker.findComponentByType<com.intellij.ui.EditorTextField>(root)
            if (editorTextField != null) {
                trace("setTopmostDialogText found ${editorTextField.javaClass.name}")
                if (clearFirst) editorTextField.selectAll()
                editorTextField.text = value
                trace("setTopmostDialogText EditorTextField updated")
                return@runOnEdtAndWaitWithModality true
            }

            val comboEditor =
                ComponentTreeWalker
                    .findAllComponentsByType<JComboBox<*>>(root)
                    .asSequence()
                    .mapNotNull { it.editor?.editorComponent }
                    .firstOrNull { it is com.intellij.ui.EditorTextField || it is JTextField }
            if (comboEditor is com.intellij.ui.EditorTextField) {
                if (clearFirst) comboEditor.selectAll()
                comboEditor.text = value
                return@runOnEdtAndWaitWithModality true
            }
            if (comboEditor is JTextField) {
                if (clearFirst) comboEditor.selectAll()
                comboEditor.text = value
                return@runOnEdtAndWaitWithModality true
            }

            val textField = ComponentTreeWalker.findTextComponent(root, "")
            if (textField != null) {
                if (clearFirst) textField.selectAll()
                textField.text = value
                return@runOnEdtAndWaitWithModality true
            }
            false
        }
    }

    private fun dismissAutocompletePopup() {
        val lookupVisible =
            edtDispatcher.runOnEdtAndWait {
                Window.getWindows().any { window ->
                    window.isVisible &&
                        ComponentTreeWalker.findAllComponentsByType<java.awt.Component>(window).any {
                            it.javaClass.simpleName.contains("Lookup", ignoreCase = true)
                        }
                }
            }
        if (lookupVisible) keyboardDispatcher.pressKey("Escape")
    }

    private fun withTopmostDialog(block: (JDialog) -> Unit) {
        val dialog = waitForDialog() ?: throw IllegalStateException("No topmost dialog found")
        withDialog(dialog, block)
    }

    private fun isConfirmButtonLabel(label: String): Boolean =
        label.equals("Refactor", ignoreCase = true) ||
            label.equals("Rename", ignoreCase = true) ||
            label.equals("OK", ignoreCase = true)

    private fun confirmByButton(
        dialog: JDialog,
        preferredLabels: List<String>,
    ) {
        val error = arrayOfNulls<Throwable>(1)
        val clickFinished = AtomicBoolean(false)
        val modality = edtDispatcher.modalityStateFor(dialog)
        edtDispatcher.runOnEdtLaterWithModality(modality) {
            try {
                invokeConfirmButton(dialog, preferredLabels)
                trace("confirmByButton action returned showing=${isDialogShowing(dialog)}")
            } catch (t: Throwable) {
                error[0] = t
                t.printStackTrace()
            } finally {
                clickFinished.set(true)
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        var lastLogAt = 0L
        while (System.currentTimeMillis() < deadline) {
            error[0]?.let { throw it }
            if (!isDialogShowing(dialog)) {
                trace(
                    "confirmByButton closed title='${safeDialogTitle(dialog)}' " +
                        "clickFinished=${clickFinished.get()}",
                )
                return
            }
            acceptExtraDialogs(dialog)
            val now = System.currentTimeMillis()
            if (now - lastLogAt >= 2000) {
                lastLogAt = now
                trace(
                    "confirmByButton waiting title='${safeDialogTitle(dialog)}' " +
                        "showing=${isDialogShowing(dialog)} clickFinished=${clickFinished.get()} " +
                        "windows=${describeVisibleWindowTitles()}",
                )
            }
            Thread.sleep(50)
        }
        error[0]?.let { throw it }
        throw IllegalStateException(
            "Dialog '${safeDialogTitle(dialog)}' did not close after clicking Refactor " +
                "(clickFinished=${clickFinished.get()} windows=${describeVisibleWindowTitles()})",
        )
    }

    private fun invokeConfirmButton(
        dialog: JDialog,
        preferredLabels: List<String>,
    ) {
        val wrapper = dialogWrapperOf(dialog)
        val guard = TransactionGuard.getInstance()
        val preview = wrapper?.let { invokeNoArg(it, "isPreviewUsages") }
        val visibility =
            wrapper?.let { runCatching { invokeNoArg(it, "getVisibility") }.getOrNull() }
        trace(
            "invokeConfirmButton dialog='${safeDialogTitle(dialog)}' wrapper=${wrapper?.javaClass?.name} " +
                "writingAllowed=${guard.isWritingAllowed} preview=$preview visibility=$visibility " +
                "buttons=[${describeButtons(dialog)}]",
        )
        if (wrapper != null) {
            invokeDeclared(wrapper, "setPreviewResults", false)
            edtDispatcher.runAsUserActivity {
                invokeNoArg(wrapper, "doRefactorAction")
            }
            if (!isDialogShowing(dialog)) return
            val message =
                runCatching { invokeNoArg(wrapper, "validateAndCommitData") as? String }
                    .onFailure { error ->
                        trace("validateAndCommitData threw ${error.message}")
                    }
                    .getOrNull()
            if (!message.isNullOrEmpty()) {
                throw IllegalStateException(
                    "Refactor was invoked but the dialog rejected it: $message",
                )
            }
            trace(
                "doRefactorAction left dialog open validateAndCommitData=${formatValidation(message)} " +
                    "preview=${invokeNoArg(wrapper, "isPreviewUsages")}",
            )
            val processor = invokeNoArg(wrapper, "createRefactoringProcessor")
            trace("retry invokeRefactoring processor=${processor?.javaClass?.name}")
            if (processor != null) {
                edtDispatcher.runAsUserActivity {
                    invokeDeclared(wrapper, "invokeRefactoring", processor)
                }
            }
            return
        }
        val searchRoot: Component = dialog.rootPane ?: dialog
        val button =
            preferredLabels.firstNotNullOfOrNull { label ->
                ComponentTreeWalker.findButton(searchRoot, label)
            } ?: dialog.rootPane?.defaultButton
        val action = button?.action
        trace(
            "invokeConfirmButton fallback button='${button?.text}' enabled=${button?.isEnabled} " +
                "action=${action?.javaClass?.name}",
        )
        if (action != null && button?.isEnabled == true) {
            edtDispatcher.runAsUserActivity {
                action.actionPerformed(
                    ActionEvent(button, ActionEvent.ACTION_PERFORMED, "refactor"),
                )
            }
            return
        }
        throw IllegalStateException(
            "No enabled Refactor/Rename action in dialog '${safeDialogTitle(dialog)}'. " +
                "Buttons: [${describeButtons(dialog)}]",
        )
    }

    private fun formatValidation(message: String?): String =
        when (message) {
            null -> "null"
            "" -> "EXIT_SILENTLY"
            else -> "'$message'"
        }

    private fun acceptExtraDialogs(original: JDialog) {
        if (extraVisibleDialogs(original).isEmpty()) return
        if (!acceptingExtraDialogs.compareAndSet(false, true)) return
        edtDispatcher.runOnEdtLaterWithModality(ModalityState.any()) {
            try {
                for (window in extraVisibleDialogs(original)) {
                    val root = window.rootPane ?: continue
                    val button =
                        ComponentTreeWalker.findAllComponentsByType<AbstractButton>(root)
                            .firstOrNull { candidate -> candidate.isEnabled && isOkOrYes(candidate) }
                    if (button == null) continue
                    trace(
                        "acceptExtraDialog title='${safeDialogTitle(window)}' " +
                            "class=${window.javaClass.name} button='${button.text}'",
                    )
                    button.doClick()
                }
            } finally {
                acceptingExtraDialogs.set(false)
            }
        }
    }

    private fun isOkOrYes(button: AbstractButton): Boolean {
        val letters = button.text.orEmpty().filter { it.isLetter() }
        return letters.equals("OK", ignoreCase = true) || letters.equals("Yes", ignoreCase = true)
    }

    private fun extraVisibleDialogs(original: JDialog): List<JDialog> =
        Window.getWindows().mapNotNull { window ->
            if (window !== original && window is JDialog && isDialogShowing(window)) window else null
        }

    private fun isDialogShowing(dialog: JDialog): Boolean {
        return try {
            dialog.isDisplayable && dialog.isVisible && dialog.rootPane != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun safeDialogTitle(dialog: JDialog): String {
        return try {
            dialog.title.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun dialogWrapperOf(dialog: JDialog): Any? {
        return try {
            val method =
                dialog.javaClass.methods.firstOrNull { it.name == "getDialogWrapper" && it.parameterCount == 0 }
                    ?: return null
            method.isAccessible = true
            method.invoke(dialog)
        } catch (t: Throwable) {
            trace("dialogWrapperOf failed: ${t.message}")
            null
        }
    }

    private fun invokeNoArg(
        instance: Any,
        methodName: String,
    ): Any? = invokeDeclared(instance, methodName)

    private fun invokeDeclared(
        instance: Any,
        methodName: String,
        vararg args: Any?,
    ): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            val method =
                clazz.declaredMethods.firstOrNull { declared ->
                    declared.name == methodName &&
                        declared.parameterCount == args.size &&
                        args.indices.all { index -> argumentMatches(declared.parameterTypes[index], args[index]) }
                }
            if (method != null) {
                method.isAccessible = true
                return try {
                    method.invoke(instance, *args)
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    throw error.targetException ?: error
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun argumentMatches(
        type: Class<*>,
        value: Any?,
    ): Boolean {
        if (value == null) return !type.isPrimitive
        if (type.isInstance(value)) return true
        return value is Boolean && type == java.lang.Boolean.TYPE
    }

    private fun describeButtons(dialog: JDialog): String {
        val root: Component = dialog.rootPane ?: return "(no root pane)"
        return ComponentTreeWalker.findAllComponentsByType<AbstractButton>(root).joinToString { button ->
            val actionName = button.action?.getValue(Action.NAME)?.toString().orEmpty()
            "${button.javaClass.simpleName} text='${button.text}' action='$actionName' " +
                "enabled=${button.isEnabled} showing=${button.isShowing}"
        }
    }

    private fun describeVisibleWindowTitles(): String =
        Window.getWindows()
            .filter { it.isVisible }
            .joinToString { window ->
                val title =
                    when (window) {
                        is JDialog -> window.title.orEmpty()
                        is JFrame -> window.title.orEmpty()
                        else -> window.name.orEmpty()
                    }
                "${window.javaClass.simpleName}:'$title'"
            }

    private fun waitForDialog(timeoutMs: Long = 3000): JDialog? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dialog = findTopmostDialog()
            if (dialog != null) {
                trace(
                    "waitForDialog found title='${dialog.title}' " +
                        "class=${dialog.javaClass.name} focused=${dialog.isFocused}",
                )
                return dialog
            }
            Thread.sleep(50)
        }
        edtDispatcher.runOnEdtAndWaitWithModality(ModalityState.any()) {
            trace("waitForDialog timeout windows:\n${describeAllWindows()}")
        }
        return null
    }

    private fun withDialog(
        dialog: JDialog,
        block: (JDialog) -> Unit,
    ) {
        edtDispatcher.runOnEdtAndWaitWithModality(edtDispatcher.modalityStateFor(dialog)) {
            block(dialog)
        }
    }

    private fun closeDialog(dialog: JDialog) {
        try {
            val doCancel = dialog.javaClass.methods.firstOrNull { it.name == "doCancelAction" }
            if (doCancel != null) {
                doCancel.invoke(dialog)
                return
            }
        } catch (_: Throwable) {
            // Fall back to disposing the window below.
        }
        dialog.dispose()
    }

    private fun waitForLabeledCombo(
        label: String,
        timeoutMs: Long = 5000,
    ): FoundCombo? {
        trace("waitForLabeledCombo start label='$label' timeoutMs=$timeoutMs")
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            val found =
                edtDispatcher.runOnEdtAndWaitWithModality(ModalityState.any()) {
                    if (attempts == 1) {
                        trace("waitForLabeledCombo label='$label' windows:\n${describeAllWindows()}")
                    }
                    findLabeledComboOnEdt(label) ?: findSingleComboFallbackOnEdt()
                }
            if (found != null) {
                trace(
                    "waitForLabeledCombo found attempt=$attempts " +
                        "window='${windowTitle(found.window)}' class=${found.window.javaClass.name}",
                )
                return found
            }
            Thread.sleep(50)
        }
        edtDispatcher.runOnEdtAndWaitWithModality(ModalityState.any()) {
            trace("waitForLabeledCombo timeout label='$label' windows:\n${describeAllWindows()}")
        }
        return null
    }

    private fun findLabeledComboOnEdt(label: String): FoundCombo? {
        for (window in Window.getWindows()) {
            if (!window.isVisible) continue
            val root = windowContentRoot(window) ?: continue
            val combo = ComponentTreeWalker.findComboBox(root, label) ?: continue
            return FoundCombo(window, combo)
        }
        return null
    }

    private fun findSingleComboFallbackOnEdt(): FoundCombo? {
        for (window in Window.getWindows().reversed()) {
            if (!window.isVisible || window !is JDialog) continue
            val root = windowContentRoot(window) ?: continue
            val combos = ComponentTreeWalker.findAllComponentsByType<JComboBox<*>>(root)
            if (combos.size == 1) {
                trace(
                    "waitForLabeledCombo falling back to the only combo in " +
                        "'${windowTitle(window)}': ${describeCombo(combos.single())}",
                )
                return FoundCombo(window, combos.single())
            }
        }
        return null
    }

    private fun applyDropdownSelection(
        combo: JComboBox<*>,
        label: String,
        value: String,
    ) {
        val items = describeCombo(combo)
        trace("applyDropdownSelection label='$label' value='$value' $items")
        val itemIndex =
            (0 until combo.itemCount).firstOrNull { index -> dropdownItemMatches(combo, index, value) }
                ?: throw IllegalStateException(
                    "Value '$value' not found in dropdown '$label'. $items",
                )
        combo.selectedIndex = itemIndex
        combo.isPopupVisible = false
        if (combo.selectedIndex != itemIndex) {
            combo.selectedItem = combo.getItemAt(itemIndex)
        }
        combo.actionListeners.forEach { listener ->
            listener.actionPerformed(ActionEvent(combo, ActionEvent.ACTION_PERFORMED, "select"))
        }
        commitVisibilityPanel(combo)
        val selectedRaw = combo.selectedItem?.toString().orEmpty()
        val selectedRendered = renderedComboText(combo, combo.selectedIndex)
        trace(
            "applyDropdownSelection selectedIndex=${combo.selectedIndex} " +
                "raw='$selectedRaw' rendered='$selectedRendered'",
        )
        check(dropdownItemMatches(combo, combo.selectedIndex, value)) {
            "Dropdown '$label' selected raw='$selectedRaw' rendered='$selectedRendered' instead of '$value'"
        }
    }

    private fun commitVisibilityPanel(combo: JComboBox<*>) {
        var parent: Container? = combo.parent
        while (parent != null) {
            val setter =
                parent.javaClass.methods.firstOrNull { method ->
                    method.name == "setVisibility" && method.parameterCount == 1
                }
            if (setter != null) {
                trace(
                    "commitVisibilityPanel ${parent.javaClass.simpleName}" +
                        ".setVisibility('${combo.selectedItem}')",
                )
                setter.invoke(parent, combo.selectedItem)
                return
            }
            parent = parent.parent
        }
    }

    private fun dropdownItemMatches(
        combo: JComboBox<*>,
        index: Int,
        wanted: String,
    ): Boolean {
        val raw = combo.getItemAt(index)?.toString().orEmpty()
        val rendered = renderedComboText(combo, index)
        val wantedNorm = normalizeDropdownText(wanted)
        val candidates = listOf(raw, rendered) + displayAliases(raw)
        if (candidates.any { normalizeDropdownText(it) == wantedNorm }) return true
        return isPackagePrivateLabel(wantedNorm) && isPackageLocalValue(raw, rendered)
    }

    private fun isPackageLocalValue(
        raw: String,
        rendered: String,
    ): Boolean {
        val rawNorm = normalizeDropdownText(raw)
        val renderedNorm = normalizeDropdownText(rendered)
        return rawNorm.isEmpty() ||
            isPackagePrivateLabel(rawNorm) ||
            isPackagePrivateLabel(renderedNorm)
    }

    private fun isPackagePrivateLabel(normalized: String): Boolean =
        normalized in
            setOf(
                "package-private",
                "package private",
                "package-local",
                "package local",
                "packagelocal",
                "package_local",
            )

    private fun displayAliases(raw: String): List<String> =
        when (normalizeDropdownText(raw)) {
            "private" -> listOf("Private")
            "public" -> listOf("Public")
            "protected" -> listOf("Protected")
            "" -> listOf("Package-private", "package-private", "Package private")
            else -> emptyList()
        }

    private fun normalizeDropdownText(value: String?): String {
        return value.orEmpty().replace(Regex("\\s+"), " ").trim().lowercase()
    }

    private fun renderedComboText(
        combo: JComboBox<*>,
        index: Int,
    ): String {
        if (index < 0 || index >= combo.itemCount) return ""
        return try {
            @Suppress("UNCHECKED_CAST")
            val renderer = combo.renderer as ListCellRenderer<Any?>
            val component =
                renderer.getListCellRendererComponent(
                    JList<Any?>(),
                    combo.getItemAt(index),
                    index,
                    false,
                    false,
                )
            labelTextFrom(component)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun labelTextFrom(component: Component): String {
        when (component) {
            is JLabel -> if (!component.text.isNullOrBlank()) return component.text
            is AbstractButton -> if (!component.text.isNullOrBlank()) return component.text
        }
        if (component is Container) {
            for (child in component.components) {
                val text = labelTextFrom(child)
                if (text.isNotBlank()) return text
            }
        }
        return component.accessibleContext?.accessibleName.orEmpty()
    }

    private fun describeAllWindows(): String {
        val windows = Window.getWindows()
        if (windows.isEmpty()) return "(no windows)"
        return windows.mapIndexed { index, window ->
            val root = windowContentRoot(window)
            val combos =
                if (root != null) {
                    ComponentTreeWalker.findAllComponentsByType<JComboBox<*>>(root)
                } else {
                    emptyList()
                }
            val comboSummary =
                if (combos.isEmpty()) {
                    "combos=0"
                } else {
                    combos.joinToString("; ") { describeCombo(it) }
                }
            buildString {
                append("[$index] ${window.javaClass.simpleName}")
                append(" visible=${window.isVisible}")
                append(" focused=${window.isFocused}")
                append(" title='${windowTitle(window)}'")
                if (window is JDialog) append(" modal=${window.isModal}")
                append(" $comboSummary")
            }
        }.joinToString("\n")
    }

    private fun describeCombo(combo: JComboBox<*>): String {
        val name = combo.accessibleContext?.accessibleName.orEmpty().ifBlank { combo.javaClass.simpleName }
        val items =
            (0 until combo.itemCount).joinToString { index ->
                val raw = combo.getItemAt(index)?.toString().orEmpty()
                val rendered = renderedComboText(combo, index)
                "#$index raw='$raw' rendered='$rendered'"
            }
        return "$name=[$items]"
    }

    private fun findLabeledEditableField(
        root: Component,
        label: String,
    ): Component? {
        val byAccessible =
            ComponentTreeWalker.findAllComponentsByType<Component>(root).firstOrNull { component ->
                component.accessibleContext?.accessibleName?.contains(label, ignoreCase = true) == true &&
                    isEditableField(component)
            }
        if (byAccessible != null) return byAccessible
        for (candidate in ComponentTreeWalker.findAllComponentsByType<JLabel>(root)) {
            if (candidate.text?.contains(label, ignoreCase = true) != true) continue
            val parent = candidate.parent ?: continue
            val field =
                ComponentTreeWalker.findComponentByType<com.intellij.ui.EditorTextField>(parent)
                    ?: ComponentTreeWalker.findComponentByType<JTextField>(parent)
                    ?: ComponentTreeWalker.findComponentByType<JTextComponent>(parent)
            if (field != null) return field
        }
        return ComponentTreeWalker.findTextField(root, label)
            ?: ComponentTreeWalker.findTextComponent(root, label)
    }

    private fun isEditableField(component: Component): Boolean =
        component is com.intellij.ui.EditorTextField ||
            component is JTextField ||
            component is JTextComponent

    private fun setEditableText(
        component: Component,
        value: String,
    ) {
        when (component) {
            is com.intellij.ui.EditorTextField -> {
                component.selectAll()
                component.text = value
            }
            is JTextComponent -> {
                component.selectAll()
                component.text = value
            }
            else -> {
                val nested =
                    ComponentTreeWalker.findComponentByType<com.intellij.ui.EditorTextField>(component)
                        ?: ComponentTreeWalker.findComponentByType<JTextField>(component)
                if (nested != null) {
                    setEditableText(nested, value)
                } else {
                    throw IllegalStateException("Cannot type into ${component.javaClass.name}")
                }
            }
        }
    }

    private fun findParametersTable(root: Component): JTable? {
        val tables = ComponentTreeWalker.findAllComponentsByType<JTable>(root)
        return tables.firstOrNull { table ->
            (0 until table.columnCount).any { index ->
                table.getColumnName(index).contains("Name", ignoreCase = true)
            }
        } ?: tables.maxByOrNull { it.columnCount }
    }

    private fun windowTitle(window: Window): String =
        when (window) {
            is JDialog -> window.title.orEmpty()
            is JFrame -> window.title.orEmpty()
            else -> window.name.orEmpty()
        }

    private fun windowContentRoot(window: Window): Container? =
        try {
            when (window) {
                is JDialog -> window.rootPane?.contentPane
                is JFrame -> window.rootPane?.contentPane
                is JWindow -> window.rootPane?.contentPane
                else -> window
            }
        } catch (_: Throwable) {
            null
        }

    private fun dialogContentRoot(dialog: JDialog): Container? =
        try {
            dialog.rootPane?.contentPane
        } catch (_: Throwable) {
            null
        }

    private fun trace(message: String) {
        println("    DialogController: $message")
    }

    private data class FoundCombo(
        val window: Window,
        val combo: JComboBox<*>,
    )
}
