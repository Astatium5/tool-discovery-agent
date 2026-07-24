package perception.parser

data class UiComponent(
    val cls: String,
    val text: String,
    val accessibleName: String,
    val tooltip: String,
    val enabled: Boolean,
    val hasSubmenu: Boolean,
    val children: List<UiComponent>,
    val focused: Boolean = false,
) {
    val label get() =
        when {
            accessibleName.isNotBlank() -> accessibleName
            text.isNotBlank() -> text
            tooltip.isNotBlank() -> tooltip
            else -> cls
        }

    val xpath get() =
        when {
            accessibleName.isNotBlank() ->
                "//div[@class='$cls' and @accessiblename='${accessibleName.replace("'", "\\'")}']"
            text.isNotBlank() ->
                "//div[@class='$cls' and @text='${text.replace("'", "\\'")}']"
            tooltip.isNotBlank() ->
                "//div[@class='$cls' and @tooltiptext='${tooltip.replace("'", "\\'")}']"
            else -> "//div[@class='$cls']"
        }
}
