package perception.vision

import com.intellij.openapi.diagnostic.logger
import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import org.jsoup.Jsoup
import profile.ApplicationProfile
import vision.ElementInfo
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ── Extension functions for ComponentFixture (hide JS complexity) ─────────────

fun ComponentFixture.getBounds(): Rectangle {
    val result =
        callJs<String>(
            """
            var loc = component.getLocationOnScreen();
            loc.x + ',' + loc.y + ',' + component.getWidth() + ',' + component.getHeight();
            """.trimIndent(),
        )
    val parts = result.split(",")
    return Rectangle(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
}

fun ComponentFixture.getLabel(): String {
    return callJs<String>(
        """
        var label = '';
        if (typeof component.getAccessibleName === 'function') {
            label = component.getAccessibleName();
        }
        if (!label && typeof component.getText === 'function') {
            label = component.getText();
        }
        if (!label && typeof component.getToolTipText === 'function') {
            label = component.getToolTipText();
        }
        // Try client property for action buttons (IntelliJ toolbar)
        if (!label) {
            try {
                var tooltip = component.getClientProperty('ToolTipText');
                if (tooltip && typeof tooltip.toString === 'function') {
                    label = tooltip.toString();
                }
            } catch (e) {}
        }
        if (label && label.length > 50) label = label.substring(0, 50);
        label || '';
        """.trimIndent(),
    )
}

fun ComponentFixture.isEnabledComponent(): Boolean {
    return try {
        callJs<Boolean>("component.isEnabled()")
    } catch (e: Exception) {
        true
    }
}

fun ComponentFixture.captureScreenshot(): BufferedImage? {
    return try {
        getScreenshot()
    } catch (e: Exception) {
        null
    }
}

fun RemoteRobot.findIdeFrame(): ComponentFixture = find<ComponentFixture>(byXpath("//div[@class='IdeFrameImpl']"), Duration.ofSeconds(5))

fun RemoteRobot.getIdeBounds(): Rectangle {
    return try {
        val ideFrame = findIdeFrame()
        ideFrame.getBounds()
    } catch (e: Exception) {
        Rectangle(Toolkit.getDefaultToolkit().screenSize)
    }
}

fun RemoteRobot.captureIdeScreenshot(): BufferedImage? {
    return try {
        findIdeFrame().captureScreenshot()
    } catch (e: Exception) {
        null
    }
}

// ── RemoteRobotScreenshotProvider ───────────────────────────────────────────────

class RemoteRobotScreenshotProvider(
    private val robot: RemoteRobot,
    private val profile: ApplicationProfile,
) {
    private val log = logger<RemoteRobotScreenshotProvider>()

    data class OverlayResult(
        val screenshot: BufferedImage,
        val elementMap: Map<Int, ElementInfo>,
        val uiDescription: String,
        val windowBounds: Rectangle,
    )

    fun capture(): OverlayResult {
        val windowBounds = robot.getIdeBounds()
        val screenshot = robot.captureIdeScreenshot() ?: BufferedImage(windowBounds.width, windowBounds.height, BufferedImage.TYPE_INT_RGB)
        val elements = findInteractiveElements()
        drawOverlays(screenshot, elements, windowBounds)
        return OverlayResult(screenshot, elements.associateBy { it.id }, buildDescription(elements), windowBounds)
    }

    private fun findInteractiveElements(): List<ElementInfo> {
        val elements = mutableListOf<ElementInfo>()
        var idCounter = 1

        // Try fetching HTML tree and parsing for coordinates
        try {
            val html = fetchHtmlTree()
            log.debug("  HTML length: ${html.length}")
            val doc = Jsoup.parse(html)
            val allDivs = doc.select("div")

            // Debug: show attributes on first few elements
            val firstEl = allDivs.firstOrNull()
            if (firstEl != null) {
                val attrs = firstEl.attributes().map { it.key }.sorted()
                log.debug("  Available HTML attributes: ${attrs.joinToString()}")
                // Show first element with class
                val withClass = allDivs.firstOrNull { it.attr("class").isNotBlank() }
                if (withClass != null) {
                    log.debug(
                        "  Element with class '${withClass.attr(
                            "class",
                        )}': ${withClass.attributes().joinToString { "${it.key}='${it.value.take(20)}'" }}",
                    )
                }
            }

            for (el in allDivs) {
                val cls = el.attr("class").trim()
                if (cls.isBlank()) continue

                // Try to get coordinates from HTML attributes
                val x = el.attr("x").toIntOrNull() ?: el.attr("location_x").toIntOrNull()
                val y = el.attr("y").toIntOrNull() ?: el.attr("location_y").toIntOrNull()
                val w = el.attr("width").toIntOrNull()
                val h = el.attr("height").toIntOrNull()

                // If coordinates in HTML, use them directly
                if (x != null && y != null && w != null && h != null && w > 10 && h > 10 && w < 2000 && h < 2000) {
                    val text = el.attr("text").trim().take(50)
                    val accessibleName = el.attr("accessiblename").trim().take(50)
                    val visibleText = el.attr("visible_text").trim().take(50)
                    val tooltip = el.attr("tooltip").trim().take(50)
                    val label = accessibleName.ifBlank { tooltip.ifBlank { visibleText.ifBlank { text } } }
                    val enabled = el.attr("enabled") != "false"
                    val clickable = el.attr("clickable") == "true"

                    if (clickable || cls in INTERACTIVE_CLASSES) {
                        elements.add(
                            ElementInfo(
                                id = idCounter++,
                                cls = cls,
                                label = label,
                                x = x,
                                y = y,
                                width = w,
                                height = h,
                                enabled = enabled,
                                role = cls.toRole(),
                                xpath = "//div[@class='$cls']",
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("  findInteractiveElements: HTML parsing failed: ${e.message}")
        }

        // Fallback: use xpath to find common components
        if (elements.isEmpty()) {
            for (className in INTERACTIVE_CLASSES) {
                try {
                    val components = robot.findAll<ComponentFixture>(byXpath("//div[@class='$className']"))
                    for (component in components) {
                        val bounds = component.getBounds()
                        if (bounds.width > 10 && bounds.height > 10 && bounds.width < 2000 && bounds.height < 2000) {
                            elements.add(
                                ElementInfo(
                                    id = idCounter++,
                                    cls = className,
                                    label = component.getLabel(),
                                    x = bounds.x,
                                    y = bounds.y,
                                    width = bounds.width,
                                    height = bounds.height,
                                    enabled = component.isEnabledComponent(),
                                    role = className.toRole(),
                                    xpath = "//div[@class='$className']",
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip failed searches
                }
            }
        }

        log.info("  findInteractiveElements: Found ${elements.size} elements")
        return elements
    }

    private fun fetchHtmlTree(): String {
        val http = HttpClient.newHttpClient()
        val response =
            http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8082"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return response.body()
    }

    private fun String.toRole(): String =
        when (this) {
            "EditorComponentImpl", "EditorImpl" -> "editor"
            "JButton", "ActionButton", "ActionButtonWithText" -> "button"
            "ActionMenuItem" -> "menu_item"
            "ActionMenu" -> "menu"
            "JCheckBox" -> "checkbox"
            "JComboBox" -> "combobox"
            "JTabbedPane", "EditorTabLabel" -> "tab"
            "JTree", "ProjectViewTree" -> "tree"
            "JBTable", "JTable" -> "table"
            "JList", "JBList" -> "list"
            "JTextField", "JBTextField" -> "text_field"
            "HeavyWeightWindow" -> "popup"
            else -> "element"
        }

    private companion object {
        val INTERACTIVE_CLASSES =
            setOf(
                "EditorComponentImpl",
                "EditorImpl",
                "JButton",
                "ActionButton",
                "ActionButtonWithText",
                "ActionMenuItem",
                "ActionMenu",
                "JCheckBox",
                "JComboBox",
                "EditorTabLabel",
                "JTree",
                "ProjectViewTree",
                "JBTable",
                "JList",
                "JTextField",
                "JBTextField",
                "HeavyWeightWindow",
            )
    }

    private fun buildXPath(
        className: String,
        label: String,
    ): String {
        return "//div[@class='$className']"
    }

    private fun drawOverlays(
        screenshot: BufferedImage,
        elements: List<ElementInfo>,
        windowBounds: Rectangle,
    ) {
        val g = screenshot.createGraphics()
        g.stroke = BasicStroke(2f)
        g.font = Font("Arial", Font.BOLD, 14)

        for (element in elements) {
            if (!element.enabled) continue
            val relX = element.x - windowBounds.x
            val relY = element.y - windowBounds.y

            g.color = Color(0, 150, 255, 180)
            g.drawRect(relX, relY, element.width, element.height)

            g.color = Color(255, 255, 255, 200)
            g.fillOval(relX + 5, relY + 5, 20, 20)

            g.color = Color(0, 100, 200)
            g.drawString(element.id.toString(), relX + 8, relY + 20)
        }
        g.dispose()
    }

    private fun buildDescription(elements: List<ElementInfo>): String {
        val sb = StringBuilder("## Interactive UI Elements\n")
        sb.append("NOTE: These are toolbar buttons, menu items, and dialog elements.\n")
        sb.append("Editor text (code) is NOT numbered - use move_caret() to navigate.\n\n")

        for (e in elements) {
            val labelDisplay = if (e.label.isNotBlank()) e.label else inferLabelFromClass(e.cls, e.role)
            val roleDisplay = inferRole(e.cls, e.y, e.height)
            val status = if (!e.enabled) " [DISABLED]" else ""
            sb.append("[${e.id}] $labelDisplay ($roleDisplay)$status\n")
        }
        sb.append("\n## For editor operations (navigating to code), use move_caret(symbol) NOT click_element!\n")
        return sb.toString()
    }

    private fun inferLabelFromClass(
        cls: String,
        role: String,
    ): String {
        return when (cls) {
            "ActionButton" -> "Toolbar button"
            "ActionMenu" -> "Menu"
            "ActionMenuItem" -> "Menu item"
            "JButton" -> "Button"
            "JCheckBox" -> "Checkbox"
            "EditorComponentImpl" -> "Code editor (use move_caret, not click)"
            "EditorImpl" -> "Editor (use move_caret, not click)"
            "JTree", "ProjectViewTree" -> "Project tree"
            else -> role
        }
    }

    private fun inferRole(
        cls: String,
        y: Int,
        height: Int,
    ): String {
        // Toolbar buttons are usually at top of window (y < 100)
        if (cls == "ActionButton" && y < 100) return "toolbar button"
        if (cls == "EditorComponentImpl") return "editor - use keyboard shortcuts"
        return cls.toRole()
    }
}
