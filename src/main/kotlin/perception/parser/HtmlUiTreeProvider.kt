package perception.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import profile.UIProfiler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * [UiTreeProvider] implementation for applications that expose their
 * component tree as HTML (e.g., IntelliJ Robot Server).
 *
 * This is the single place that depends on HTTP + Jsoup.  Swapping to a
 * different format (XML, JSON, native API) means creating a new
 * [UiTreeProvider] implementation — no other files change.
 *
 * Safety net: the JDK's `HttpClient` has **no default read timeout**, so
 * any upstream wedge of Remote Robot's dispatch queue (we try very hard
 * to avoid those — see `ActionGenerator.fetchEditorCodeSafely`) would
 * otherwise hang the agent forever. We bound each request at
 * [REQUEST_TIMEOUT] and retry up to [MAX_RETRIES] times so a transient
 * stall doesn't abort the loop, but a genuine wedge surfaces as a real
 * exception instead of an infinite hang.
 *
 * @param endpoint  URL that returns the raw HTML tree
 *                  (e.g. "http://localhost:8082" for IntelliJ Robot Server)
 */
class HtmlUiTreeProvider(
    private val endpoint: String = "http://localhost:8082",
) : UiTreeProvider {
    private val http =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    override fun fetchTree(): List<UiComponent> {
        val html = fetchHtml()
        return UiTreeParser.parse(html)
    }

    override fun fetchClassContexts(): Map<String, UIProfiler.ClassContext> {
        val html = fetchHtml()
        return collectClassContextsFromHtml(html)
    }

    private fun fetchHtml(): String {
        println("    HtmlUiTreeProvider: Fetching HTML from $endpoint...")
        var lastError: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val response =
                    http.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                println(
                    "    HtmlUiTreeProvider: Received ${response.body().length} chars, status ${response.statusCode()}" +
                        (if (attempt > 1) " (attempt $attempt/$MAX_RETRIES)" else ""),
                )
                return response.body()
            } catch (e: java.net.ConnectException) {
                // Server is genuinely gone — no point retrying quickly.
                println("    HtmlUiTreeProvider: Connection refused to $endpoint - is Remote Robot server running?")
                throw e
            } catch (e: java.net.http.HttpTimeoutException) {
                lastError = e
                println(
                    "    HtmlUiTreeProvider: Read timed out (attempt $attempt/$MAX_RETRIES) - " +
                        "Robot server unresponsive; retrying after ${RETRY_BACKOFF_MS}ms",
                )
                if (attempt < MAX_RETRIES) Thread.sleep(RETRY_BACKOFF_MS)
            } catch (e: java.nio.channels.ClosedChannelException) {
                lastError = e
                println(
                    "    HtmlUiTreeProvider: Channel closed (attempt $attempt/$MAX_RETRIES) - " +
                        "retrying after ${RETRY_BACKOFF_MS}ms",
                )
                if (attempt < MAX_RETRIES) Thread.sleep(RETRY_BACKOFF_MS)
            } catch (e: Exception) {
                println("    HtmlUiTreeProvider: Failed to fetch HTML: ${e::class.simpleName} - ${e.message}")
                throw e
            }
        }
        throw lastError ?: RuntimeException("HtmlUiTreeProvider: exhausted retries with no recorded error")
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * Per-request read timeout. 6s is long enough to ride through the
         * brief EDT stall when a dialog first appears, but short enough
         * that a genuine wedge fails the request instead of hanging the
         * whole agent forever.
         */
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(6)

        private const val MAX_RETRIES: Int = 3

        /**
         * Pause between retries. Transient stalls typically clear in a
         * second or two; we back off enough to let the EDT catch up
         * without stacking more blocked requests on top.
         */
        private const val RETRY_BACKOFF_MS: Long = 1500L

        /**
         * Walk every element in the raw HTML and collect per-class structural
         * metadata.  This is an unfiltered walk — no classes are skipped.
         *
         * Kept as a static method so [UIProfiler] can also call it for
         * incremental classification when it only has raw HTML available.
         */
        fun collectClassContextsFromHtml(html: String): Map<String, UIProfiler.ClassContext> {
            val doc = Jsoup.parse(html)
            val contexts = mutableMapOf<String, UIProfiler.ClassContext>()
            walkElement(doc.body(), parentClass = "", contexts)
            return contexts
        }

        private fun walkElement(
            el: Element,
            parentClass: String,
            contexts: MutableMap<String, UIProfiler.ClassContext>,
        ) {
            val cls = el.attr("class").trim()
            if (cls.isBlank()) {
                el.children().forEach { walkElement(it, parentClass, contexts) }
                return
            }

            val ctx = contexts.getOrPut(cls) { UIProfiler.ClassContext(className = cls) }
            ctx.count++
            if (parentClass.isNotBlank()) ctx.parents.add(parentClass)
            if (el.attr("text").isNotBlank() || el.attr("visible_text").isNotBlank()) ctx.hasText = true
            if (el.attr("accessiblename").isNotBlank()) ctx.hasAccessibleName = true
            if (el.attr("tooltiptext").isNotBlank()) ctx.hasTooltip = true

            for (child in el.children()) {
                val childCls = child.attr("class").trim()
                if (childCls.isNotBlank()) ctx.children.add(childCls)
                walkElement(child, cls, contexts)
            }
        }
    }
}
