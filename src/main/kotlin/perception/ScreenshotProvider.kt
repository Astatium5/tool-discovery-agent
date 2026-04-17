package perception

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage

interface ScreenshotProvider {
    fun capture(): BufferedImage?

    fun captureRegion(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): BufferedImage?
}

class DesktopScreenshotProvider : ScreenshotProvider {
    private val robot = Robot()

    override fun capture(): BufferedImage? {
        return try {
            val screenRect = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            robot.createScreenCapture(screenRect)
        } catch (e: Exception) {
            null
        }
    }

    override fun captureRegion(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): BufferedImage? {
        return try {
            robot.createScreenCapture(Rectangle(x, y, width, height))
        } catch (e: Exception) {
            null
        }
    }
}
