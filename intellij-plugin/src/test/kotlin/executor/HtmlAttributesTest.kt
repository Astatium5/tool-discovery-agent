package test.executor

import com.intellij.remoterobot.RemoteRobot
import org.junit.jupiter.api.Test
import test.BaseTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Test to inspect what attributes RemoteRobot provides in the HTML.
 * This helps us understand if we can get coordinates/bounds from the UI tree
 * without making additional HTTP calls.
 */
class HtmlAttributesTest : BaseTest() {

    @Test
    fun `dump raw HTML to see available attributes`() {
        val http = HttpClient.newHttpClient()
        val response = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8082"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        
        val html = response.body()
        println("=" * 80)
        println("RAW HTML FROM REMOTE ROBOT")
        println("=" * 80)
        
        // Save to file for analysis
        val outputFile = java.io.File("build/reports/raw-html-dump.html")
        outputFile.parentFile.mkdirs()
        outputFile.writeText(html)
        println("Saved to: ${outputFile.absolutePath}")
        
        // Print first 5000 chars for quick inspection
        println("\nFirst 5000 characters:")
        println(html.take(5000))
        
        // Look for specific attributes
        println("\n" + "=" * 80)
        println("CHECKING FOR BOUNDS ATTRIBUTES")
        println("=" * 80)
        
        val hasX = html.contains("x=")
        val hasY = html.contains("y=")
        val hasWidth = html.contains("width=")
        val hasHeight = html.contains("height=")
        val hasBounds = html.contains("bounds=")
        val hasLocation = html.contains("location=")
        val hasLocationOnScreen = html.contains("locationonscreen=")
        
        println("Has 'x=' attribute: $hasX")
        println("Has 'y=' attribute: $hasY")
        println("Has 'width=' attribute: $hasWidth")
        println("Has 'height=' attribute: $hasHeight")
        println("Has 'bounds=' attribute: $hasBounds")
        println("Has 'location=' attribute: $hasLocation")
        println("Has 'locationonscreen=' attribute: $hasLocationOnScreen")
        
        // Look for JButton elements specifically
        println("\n" + "=" * 80)
        println("JBUTTON ELEMENTS (first 3)")
        println("=" * 80)
        
        val jButtonPattern = Regex("<div[^>]*class=\"JButton\"[^>]*>")
        val jButtonMatches = jButtonPattern.findAll(html).take(3).toList()
        
        for (match in jButtonMatches) {
            println("\nJButton element:")
            println(match.value)
            
            // Extract all attributes
            val attrPattern = Regex("""(\w+)="([^"]*)"""")
            val attrs = attrPattern.findAll(match.value).map { it.groupValues[1] to it.groupValues[2] }.toList()
            println("  Attributes:")
            for ((name, value) in attrs) {
                println("    $name = \"$value\"")
            }
        }
        
        // Look for DialogRootPane elements
        println("\n" + "=" * 80)
        println("DIALOGROOTPANE ELEMENTS")
        println("=" * 80)
        
        val dialogPattern = Regex("<div[^>]*class=\"DialogRootPane\"[^>]*>")
        val dialogMatches = dialogPattern.findAll(html).take(3).toList()
        
        for (match in dialogMatches) {
            println("\nDialogRootPane element:")
            println(match.value)
        }
    }
    
    private operator fun String.times(n: Int): String = this.repeat(n)
}