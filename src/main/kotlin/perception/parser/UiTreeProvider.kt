package perception.parser

import profile.UIProfiler

/**
 * Abstraction over UI tree sources.
 *
 * Different applications expose their component trees in different formats:
 *  - IntelliJ Robot Server → HTML (via HTTP)
 *  - Android UI Automator  → XML dump
 *  - Electron DevTools      → JSON via Chrome DevTools Protocol
 *  - macOS Accessibility    → AX tree via native API
 *
 * Every downstream consumer (parser, snapshot builder, discovery agent,
 * profiler) works with [UiComponent] and [UIProfiler.ClassContext].
 * Implementations of this interface bridge from the source format to those
 * common types.
 */
interface UiTreeProvider {
    /**
     * Fetch the current UI tree and return it as a list of parsed [UiComponent] roots.
     *
     * Implementations are responsible for:
     *  1. Obtaining the raw tree (HTTP, file, native call, …)
     *  2. Parsing it into the [UiComponent] hierarchy
     *  3. Applying profile-driven pruning via [UiTreeParser.profile] if set
     */
    fun fetchTree(): List<UiComponent>

    /**
     * Collect structural metadata for every component class present in the
     * raw tree.  Used by [UIProfiler] during first-run profiling.
     *
     * Unlike [fetchTree], this walk is **unfiltered** — no classes are skipped —
     * so the profiler can discover and classify the full UI vocabulary.
     */
    fun fetchClassContexts(): Map<String, UIProfiler.ClassContext>
}
