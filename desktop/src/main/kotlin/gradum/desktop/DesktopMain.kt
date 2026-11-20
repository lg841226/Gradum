package gradum.desktop

import gradum.desktop.dsl.gradumDesktopWindow
import gradum.server.ServerAuth
import gradum.server.bootServer
import java.net.BindException
import java.net.Socket
import kotlin.system.exitProcess

fun main() {
  val handle = bootServer(
    showBanner = false,
    uiRoutes = ::installUiRoutes,
    uiPagePrefixes = UI_PAGE_PREFIXES,
  ) ?: exitProcess(1)

  try {
    handle.startAsync()
  } catch (bindException: BindException) {
    System.err.println("Gradum desktop: port ${handle.port} is already in use (${bindException.message})")
    handle.runtime.skillWatcher.close()
    handle.runtime.mcpManager.close()
    exitProcess(2)
  }

  awaitListening(handle.port)

  val website = "http://localhost:${handle.port}/legatus?${ServerAuth.TOKEN_QUERY_PARAM}=${handle.token}"
  val windowConfig = gradumDesktopWindow(website) {
    size(1280, 800)
    title("Gradum")
    transparentTitleBar()
    menu {
      applicationMenu("Gradum")
      editMenu()
    }
  }

  val cocoaApp = CocoaApp(windowConfig)
  cocoaApp.show()
  cocoaApp.run()

  try {
    handle.shutdown()
  } catch (_: Throwable) {

  }

  exitProcess(0)
}

private fun awaitListening(port: Int) {
  repeat(100) {
    try {
      Socket("localhost", port).close()
      return
    } catch (_: Throwable) {
      Thread.sleep(100)
    }
  }
  System.err.println("Gradum desktop: server did not start listening on $port within 10s; continuing anyway")
}
