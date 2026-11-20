package gradum.desktop.dsl

internal data class DesktopWindowConfig(
  val url: String,
  val windowTitle: String,
  val contentWidth: Double,
  val contentHeight: Double,
  val transparentTitleBar: Boolean,
  val trafficLightOffsetY: Double,
  val menus: List<DesktopMenuSpec>
)

internal data class DesktopMenuSpec(val title: String, val items: List<DesktopMenuItemSpec>)

internal data class DesktopMenuItemSpec(
  val title: String,
  val action: String,
  val modifierMask: Long,
  val keyEquivalent: String,
  val isSeparator: Boolean = false
)

internal fun gradumDesktopWindow(
  url: String,
  configure: DesktopWindowBuilder.() -> Unit
): DesktopWindowConfig = DesktopWindowBuilder(url).apply(configure).build()

class DesktopWindowBuilder internal constructor(private val url: String) {
  private var windowTitle: String = "Gradum"
  private var contentWidth: Double = 1280.0
  private var contentHeight: Double = 800.0
  private var transparentTitleBar: Boolean = false
  private var trafficLightOffsetY: Double = 0.0
  private val menus: MutableList<DesktopMenuSpec> = mutableListOf()

  fun size(width: Int, height: Int) {
    contentWidth = width.toDouble()
    contentHeight = height.toDouble()
  }

  fun title(text: String) {
    windowTitle = text
  }

  fun transparentTitleBar(enabled: Boolean = true) {
    transparentTitleBar = enabled
  }

  fun trafficLightOffset(points: Double) {
    trafficLightOffsetY = points
  }

  fun menu(block: DesktopMenuBuilder.() -> Unit) {
    menus += DesktopMenuBuilder().apply(block).specs
  }

  internal fun build(): DesktopWindowConfig = DesktopWindowConfig(
    url = url,
    windowTitle = windowTitle,
    contentWidth = contentWidth,
    contentHeight = contentHeight,
    transparentTitleBar = transparentTitleBar,
    trafficLightOffsetY = trafficLightOffsetY,
    menus = menus.toList(),
  )
}

class DesktopMenuBuilder internal constructor() {
  internal val specs: MutableList<DesktopMenuSpec> = mutableListOf()

  fun applicationMenu(name: String) {
    specs += DesktopMenuSpec("", listOf(commandItem("Quit $name", "q", "terminate:")))
  }

  fun editMenu(name: String = "Edit") {
    specs += DesktopMenuSpec(
      name,
      listOf(
        commandItem("Undo", "z", "undo:"),
        commandItem("Redo", "Z", "redo:"),
        separator(),
        commandItem("Cut", "x", "cut:"),
        commandItem("Copy", "c", "copy:"),
        commandItem("Paste", "v", "paste:"),
        commandItem("Select All", "a", "selectAll:"),
      ),
    )
  }

  private fun commandItem(title: String, key: String, action: String): DesktopMenuItemSpec =
    DesktopMenuItemSpec(title, action, COMMAND_KEY_MASK, key)

  private fun separator(): DesktopMenuItemSpec =
    DesktopMenuItemSpec("", "", 0, "", isSeparator = true)

  private companion object {
    const val COMMAND_KEY_MASK: Long = 1L shl 20
  }
}
