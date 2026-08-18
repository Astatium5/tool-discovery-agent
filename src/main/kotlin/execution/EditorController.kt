package execution

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/** Handles navigation and document interaction with the active editor. */
class EditorController(
    private val project: Project,
    private val edtDispatcher: EdtDispatcher,
) {
    fun findEditor(): Editor? =
        edtDispatcher.runOnEdtAndWait {
            FileEditorManager.getInstance(project).selectedTextEditor
        }

    fun openFile(path: String) {
        edtDispatcher.runOnEdtAndWait {
            val openProject =
                com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: throw IllegalStateException("No open project")
            val fileName = path.substringAfterLast('/')
            val file =
                FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(openProject))
                    .firstOrNull { it.path.endsWith(path) || it.name.equals(fileName, ignoreCase = true) }
                    ?: throw IllegalStateException("File '$path' not found in project")
            FileEditorManager.getInstance(openProject).openFile(file, true)
        }
    }

    fun focusEditor() {
        edtDispatcher.runOnEdtAndWait {
            val editor = findEditorOnEdt() ?: throw IllegalStateException("No editor is currently open")
            editor.contentComponent.requestFocusInWindow()
        }
    }

    fun typeInEditor(text: String) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        edtDispatcher.runOnEdtAndWait { editor.document.setText(text) }
    }

    fun moveCaretInEditor(offset: Int) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        edtDispatcher.runOnEdtAndWait {
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }
    }

    fun selectTextInEditor(
        startOffset: Int,
        endOffset: Int,
    ) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        edtDispatcher.runOnEdtAndWait { editor.selectionModel.setSelection(startOffset, endOffset) }
    }

    fun selectLineRange(
        fromLine: Int,
        toLine: Int,
    ) {
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        edtDispatcher.runOnEdtAndWait {
            val document = editor.document
            val startOffset = document.getLineStartOffset((fromLine - 1).coerceAtLeast(0))
            val endLine = (toLine - 1).coerceAtMost(document.lineCount - 1)
            val endOffset = document.getLineEndOffset(endLine)
            editor.selectionModel.setSelection(startOffset, endOffset)
            editor.caretModel.moveToOffset(endOffset)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }
    }

    fun getDocumentText(): String? {
        return edtDispatcher.runOnEdtAndWait { findEditorOnEdt()?.document?.text }
    }

    fun moveCaretToSymbol(symbol: String): InProcessGuiExecutor.MoveCaretOutcome {
        require(symbol.isNotEmpty()) {
            "symbol must be a non-empty identifier visible in the editor"
        }
        val editor = findEditor() ?: throw IllegalStateException("No editor is currently open")
        return edtDispatcher.runOnEdtAndWait {
            val text = editor.document.text
            val index = text.indexOf(symbol)
            if (index < 0) throw IllegalStateException("Symbol '$symbol' not found in editor")
            val currentOffset = editor.caretModel.offset
            val total = countOccurrences(text, symbol)
            val alreadyOn = currentOffset in index until (index + symbol.length)
            if (!alreadyOn) {
                editor.caretModel.moveToOffset(index + symbol.length / 2)
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            }
            val position = editor.offsetToLogicalPosition(editor.caretModel.offset)
            InProcessGuiExecutor.MoveCaretOutcome(
                line = position.line + 1,
                column = position.column + 1,
                totalOccurrences = total,
                alreadyOnSymbol = alreadyOn,
            )
        }
    }

    fun getEditorContext(around: Int = 25): InProcessGuiExecutor.EditorCodeContext? =
        edtDispatcher.runOnEdtAndWait {
            val editor = findEditorOnEdt() ?: return@runOnEdtAndWait null
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document)
            val caret = editor.caretModel.primaryCaret
            val position = caret.logicalPosition
            val startLine = (position.line - around).coerceAtLeast(0)
            val endLine = (position.line + around).coerceAtMost(document.lineCount - 1)
            val visibleText =
                buildString {
                    for (line in startLine..endLine) {
                        append(document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))))
                        append('\n')
                    }
                }
            InProcessGuiExecutor.EditorCodeContext(
                filePath = file?.path.orEmpty(),
                fileName = file?.name.orEmpty(),
                caretLine = position.line + 1,
                caretColumn = position.column + 1,
                totalLines = document.lineCount,
                symbolUnderCaret = identifierAtCaret(document.text, caret.offset),
                selectedText = caret.selectedText.orEmpty(),
                windowStartLine = startLine + 1,
                windowEndLine = endLine + 1,
                visibleText = visibleText,
            )
        }

    private fun findEditorOnEdt(): Editor? {
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun countOccurrences(
        text: String,
        symbol: String,
    ): Int =
        generateSequence(0) { position ->
            text.indexOf(symbol, position).takeIf { it >= 0 }?.plus(symbol.length)
        }.count()

    private fun identifierAtCaret(
        text: String,
        offset: Int,
    ): String {
        if (offset < 0 || offset > text.length) return ""
        var start = offset
        var end = offset

        fun isIdentifierCharacter(character: Char) = character.isLetterOrDigit() || character == '_'

        while (start > 0 && isIdentifierCharacter(text[start - 1])) start--
        while (end < text.length && isIdentifierCharacter(text[end])) end++
        return text.substring(start, end)
    }
}
