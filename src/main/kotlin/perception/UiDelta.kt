package perception

import perception.parser.ScopedSnapshotBuilder.ActiveContext
import perception.parser.ScopedSnapshotBuilder.CompactSnapshot
import perception.parser.ScopedSnapshotBuilder.WindowRef

/**
 * A compact, human-readable diff between two [CompactSnapshot]s.
 *
 * Purpose: give the LLM an explicit signal of "what happened as a result of
 * the last action" without dumping two full UI snapshots. Consumers typically
 * render this via [UiDeltaFormatter.format] into a short prompt block.
 *
 * Fields are intentionally minimal:
 * - [sameFingerprint] is the single most important bit; when true and the
 *   last action wasn't [model.AgentAction.Observe], the UI did not react.
 * - [contextChanged] flags transitions like POPUP_MENU -> DIALOG.
 * - [windowsOpened]/[windowsClosed] narrate stack changes.
 * - [focusChanged] helps recover when the model expected a field to take focus.
 * - [newInteractive]/[removedInteractive] are label-level diffs inside the
 *   **active window** only, so tokens stay bounded.
 */
data class UiDelta(
    val sameFingerprint: Boolean,
    val contextChanged: Pair<ActiveContext, ActiveContext>?,
    val windowsOpened: List<WindowRef>,
    val windowsClosed: List<WindowRef>,
    val focusChanged: Pair<String?, String?>?,
    val newInteractive: List<String>,
    val removedInteractive: List<String>,
    /** Previous tree size (flattened component count) — 0 when unknown. */
    val previousTreeSize: Int = 0,
    /** Current tree size (flattened component count). */
    val currentTreeSize: Int = 0,
) {
    val hasChanges: Boolean
        get() =
            !sameFingerprint ||
                contextChanged != null ||
                windowsOpened.isNotEmpty() ||
                windowsClosed.isNotEmpty() ||
                focusChanged != null ||
                newInteractive.isNotEmpty() ||
                removedInteractive.isNotEmpty() ||
                treeGrewSignificantly

    /**
     * True when the raw tree grew by more than ~10% or ~50 components while
     * the compact fingerprint stayed identical. This is the "file loaded, but
     * our compact view can't see it yet" signal — and the reason the LLM
     * should not assume its last action was a no-op.
     */
    val treeGrewSignificantly: Boolean
        get() =
            sameFingerprint &&
                previousTreeSize > 0 &&
                currentTreeSize - previousTreeSize >= 50 &&
                (currentTreeSize - previousTreeSize).toDouble() / previousTreeSize >= 0.10

    companion object {
        /** Delta used for the first iteration where there is no previous snapshot. */
        val INITIAL =
            UiDelta(
                sameFingerprint = false,
                contextChanged = null,
                windowsOpened = emptyList(),
                windowsClosed = emptyList(),
                focusChanged = null,
                newInteractive = emptyList(),
                removedInteractive = emptyList(),
                previousTreeSize = 0,
                currentTreeSize = 0,
            )

        /**
         * Compute a delta from [previous] to [current]. If [previous] is null,
         * [INITIAL] is returned so callers can treat "first observation" and
         * "no prior state" uniformly.
         */
        fun between(
            previous: CompactSnapshot?,
            current: CompactSnapshot,
        ): UiDelta {
            if (previous == null) return INITIAL

            val same = previous.fingerprint == current.fingerprint

            val ctxDelta =
                if (previous.activeContext != current.activeContext) {
                    previous.activeContext to current.activeContext
                } else {
                    null
                }

            val prevKeys = previous.windowStack.map { keyOf(it) }.toSet()
            val currKeys = current.windowStack.map { keyOf(it) }.toSet()
            val opened = current.windowStack.filter { keyOf(it) !in prevKeys }
            val closed = previous.windowStack.filter { keyOf(it) !in currKeys }

            val prevFocus = previous.focused?.label
            val currFocus = current.focused?.label
            val focusDelta = if (prevFocus != currFocus) prevFocus to currFocus else null

            val prevLabels = labelsOf(previous)
            val currLabels = labelsOf(current)
            val added = (currLabels - prevLabels).toList()
            val removed = (prevLabels - currLabels).toList()

            return UiDelta(
                sameFingerprint = same,
                contextChanged = ctxDelta,
                windowsOpened = opened,
                windowsClosed = closed,
                focusChanged = focusDelta,
                newInteractive = added,
                removedInteractive = removed,
                previousTreeSize = previous.treeSize,
                currentTreeSize = current.treeSize,
            )
        }

        private fun keyOf(w: WindowRef): String = "${w.type.name}:${w.title}"

        private fun labelsOf(snap: CompactSnapshot): Set<String> {
            val s = LinkedHashSet<String>()
            snap.activeWindow.fields.forEach { s += it.label }
            snap.activeWindow.buttons.forEach { s += it.label }
            snap.activeWindow.menuItems.forEach { s += it.label }
            return s
        }
    }
}

/**
 * Renders a [UiDelta] as a small, fixed-shape prompt block.
 *
 * Keep the output under ~10 short lines: the LLM only needs the signal,
 * not a full second snapshot.
 */
object UiDeltaFormatter {
    private const val MAX_LIST = 8

    fun format(
        delta: UiDelta,
        lastActionDescribed: String?,
    ): String {
        if (!delta.hasChanges) {
            return "No change since last action."
        }

        val sb = StringBuilder()

        if (delta.sameFingerprint && lastActionDescribed != null) {
            if (delta.treeGrewSignificantly) {
                // The fingerprint is stable but the raw tree grew — almost
                // always means the last action DID take effect (file loaded,
                // tool window opened) but our compact view can't surface it.
                // Tell the LLM not to treat this as a no-op.
                sb.append("Perception unchanged — but the UI tree grew (")
                    .append(delta.previousTreeSize)
                    .append(" -> ")
                    .append(delta.currentTreeSize)
                    .append(" components). Your last action ($lastActionDescribed) likely took effect; ")
                    .append("proceed with the next logical step instead of repeating it.\n")
            } else {
                sb.append("NO PROGRESS: last action did not change the UI ($lastActionDescribed)\n")
            }
        }

        delta.contextChanged?.let { (from, to) ->
            sb.append("- context: ").append(from.name).append(" -> ").append(to.name).append("\n")
        }

        if (delta.windowsOpened.isNotEmpty()) {
            sb.append("- opened: ")
                .append(delta.windowsOpened.joinToString(", ") { "\"${it.title}\" (${it.type.name})" })
                .append("\n")
        }
        if (delta.windowsClosed.isNotEmpty()) {
            sb.append("- closed: ")
                .append(delta.windowsClosed.joinToString(", ") { "\"${it.title}\" (${it.type.name})" })
                .append("\n")
        }

        delta.focusChanged?.let { (before, after) ->
            sb.append("- focus: ")
                .append(before ?: "(none)")
                .append(" -> ")
                .append(after ?: "(none)")
                .append("\n")
        }

        if (delta.newInteractive.isNotEmpty()) {
            sb.append("- appeared: ")
                .append(delta.newInteractive.take(MAX_LIST).joinToString(", "))
            if (delta.newInteractive.size > MAX_LIST) {
                sb.append(", +").append(delta.newInteractive.size - MAX_LIST).append(" more")
            }
            sb.append("\n")
        }
        if (delta.removedInteractive.isNotEmpty()) {
            sb.append("- disappeared: ")
                .append(delta.removedInteractive.take(MAX_LIST).joinToString(", "))
            if (delta.removedInteractive.size > MAX_LIST) {
                sb.append(", +").append(delta.removedInteractive.size - MAX_LIST).append(" more")
            }
            sb.append("\n")
        }

        return sb.toString().trimEnd()
    }
}
