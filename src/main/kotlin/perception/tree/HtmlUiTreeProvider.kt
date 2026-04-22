package perception.tree

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import profile.UIProfiler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * [UiTreeProvider] implementation for applications that expose their
 * component tree as HTML (e.g., IntelliJ Robot Server).
 *
 * This is the single place that depends on HTTP + Jsoup.  Swapping to a
 * different format (XML, JSON, native API) means creating a new
 * [UiTreeProvider] implementation — no other files change.
 *
 * @param endpoint  URL that returns the raw HTML tree
 *                  (e.g. "http://localhost:8082" for IntelliJ Robot Server)
 */
class HtmlUiTreeProvider(
    private val endpoint: String = "http://localhost:8082",
) : UiTreeProvider {
    private val http = HttpClient.newHttpClient()

    override fun fetchTree(): List<UiComponent> {
        val html = fetchHtml()
        return UiTreeParser.parse(html)
    }

    override fun fetchClassContexts(): Map<String, UIProfiler.ClassContext> {
        val html = fetchHtml()
        return collectClassContextsFromHtml(html)
    }

    private fun fetchHtml(): String {
        val response =
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.body()
    }

    companion object {
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
