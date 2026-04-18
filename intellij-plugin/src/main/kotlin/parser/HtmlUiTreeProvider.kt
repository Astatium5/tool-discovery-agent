package parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import profile.UIProfiler
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Fetches UI component tree from IntelliJ Remote Robot Server via HTTP.
 *
 * The endpoint returns HTML which is parsed into UiComponent objects.
 */
class HtmlUiTreeProvider(
    private val endpoint: String = "http://localhost:8082",
) : UiTreeProvider {
    private val http = HttpClient.newHttpClient()

    override fun fetchRawHtml(): String = http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    override fun fetchTree(): List<UiComponent> {
        val html = fetchRawHtml()
        return UiTreeParser.parse(html)
    }

    override fun fetchClassContexts(): Map<String, UIProfiler.ClassContext> {
        val html = fetchRawHtml()
        return collectClassContextsFromHtml(html)
    }

    companion object {
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
