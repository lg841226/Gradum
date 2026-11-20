package gradum.desktop

import gradum.desktop.dsl.DesktopMenuItemSpec
import gradum.desktop.dsl.DesktopWindowConfig
import gradum.desktop.ffi.AppKit
import gradum.desktop.ffi.ObjCRuntime
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

internal class CocoaApp(private val config: DesktopWindowConfig) {
  private var application = MemorySegment.NULL
  private var window = MemorySegment.NULL
  private var webView = MemorySegment.NULL
  private var delegate = MemorySegment.NULL
  private var navigationDelegate = MemorySegment.NULL

  fun show() {
    NativeCallbacks.current = this
    ObjCRuntime.autorelease {
      application = ObjCRuntime.send0(ObjCRuntime.classNamed("NSApplication"), "sharedApplication")
      ObjCRuntime.voidLong(application, "setActivationPolicy:", 0)

      val delegateClass = ObjCRuntime.beginClass("GradumAppDelegate")
      ObjCRuntime.addImp(
        delegateClass,
        "applicationShouldTerminateAfterLastWindowClosed:",
        "B@:@",
        MethodHandles.lookup().findStatic(
          NativeCallbacks::class.java,
          "applicationShouldTerminateAfterLastWindowClosed",
          MethodType.methodType(
            Byte::class.javaPrimitiveType,
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
          ),
        ),
        FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS),
      )

      ObjCRuntime.addImp(
        delegateClass,
        "applicationDidFinishLaunching:",
        "v@:@",
        MethodHandles.lookup().findStatic(
          NativeCallbacks::class.java,
          "applicationDidFinishLaunching",
          MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
          ),
        ),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
      )

      ObjCRuntime.addImp(
        delegateClass,
        "windowDidResize:",
        "v@:@",
        MethodHandles.lookup().findStatic(
          NativeCallbacks::class.java,
          "windowDidResize",
          MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
          ),
        ),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
      )

      ObjCRuntime.addImp(
        delegateClass,
        "windowDidUpdate:",
        "v@:@",
        MethodHandles.lookup().findStatic(
          NativeCallbacks::class.java,
          "windowDidUpdate",
          MethodType.methodType(
            Void.TYPE,
            MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java,
          ),
        ),
        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
      )

      ObjCRuntime.finishClass(delegateClass)
      delegate = ObjCRuntime.allocInit(delegateClass)
      ObjCRuntime.void1(application, "setDelegate:", delegate)
    }
  }

  fun launch() {
    ObjCRuntime.autorelease {
      val frame = ObjCRuntime.nsRect(120.0, 120.0, config.contentWidth, config.contentHeight)
      window = AppKit.initWithContentRect(
        window = ObjCRuntime.alloc(ObjCRuntime.classNamed("NSWindow")),
        frame = frame,
        styleMask = 15 or (1 shl 15),
        backing = 2,
        defer = 0,
      )
      check(window != MemorySegment.NULL) { "NSWindow creation failed" }

      if (config.transparentTitleBar) {
        ObjCRuntime.voidByte(window, "setTitlebarAppearsTransparent:", 1)
        ObjCRuntime.voidLong(window, "setTitleVisibility:", 1)
      }

      webView = WebView.create(frame, config.url, buildDragHandler())
      ObjCRuntime.void1(window, "setContentView:", webView)
      installNavigationDelegate()
      buildMenu()

      ObjCRuntime.void1(window, "setTitle:", ObjCRuntime.nsString(config.windowTitle))
      ObjCRuntime.void0(window, "center")
      ObjCRuntime.voidByte(window, "setReleasedWhenClosed:", 0)
      ObjCRuntime.voidByte(window, "setMovableByWindowBackground:", 1)
      ObjCRuntime.void1(window, "setDelegate:", delegate)
      ObjCRuntime.void1(window, "makeKeyAndOrderFront:", MemorySegment.NULL)
      ObjCRuntime.voidByte(application, "activateIgnoringOtherApps:", 1)
    }
  }

  private var dragHandler: MemorySegment? = null

  private fun buildDragHandler(): MemorySegment {
    if (dragHandler != null) return dragHandler!!
    val callbackType = MethodType.methodType(
      Void.TYPE,
      MemorySegment::class.java, MemorySegment::class.java,
      MemorySegment::class.java, MemorySegment::class.java,
    )
    val handlerClass = ObjCRuntime.beginClass("GradumDragHandler")
    ObjCRuntime.addImp(
      handlerClass, "userContentController:didReceiveScriptMessage:", "v@:@@",
      MethodHandles.lookup().findStatic(
        NativeCallbacks::class.java, "startWindowDrag", callbackType
      ),
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    ObjCRuntime.finishClass(handlerClass)
    return ObjCRuntime.allocInit(handlerClass).also { dragHandler = it }
  }

  fun performWindowDrag() {
    if (window == MemorySegment.NULL) return
    val application = ObjCRuntime.send0(ObjCRuntime.classNamed("NSApplication"), "sharedApplication")
    val event = ObjCRuntime.send0(application, "currentEvent")
    ObjCRuntime.void1(window, "performWindowDragWithEvent:", event)
  }

  private val trafficLightBaselineY = HashMap<Long, Double>()

  fun applyTrafficLightOffset() {
    val offset = config.trafficLightOffsetY
    if (window == MemorySegment.NULL || offset == 0.0) return
    for (buttonType in 0L..2L) {
      val button = ObjCRuntime.sendObjectLong(window, "standardWindowButton:", buttonType)
      if (button == MemorySegment.NULL) continue
      val frame = ObjCRuntime.frameOf(button)
      val x = frame.get(JAVA_DOUBLE, 0)
      val y = frame.get(JAVA_DOUBLE, 8)
      val height = frame.get(JAVA_DOUBLE, 24)
      if (height <= 0.0) continue
      val baseY = trafficLightBaselineY.getOrPut(buttonType) { y }
      val targetY = baseY + offset
      if (kotlin.math.abs(y - targetY) > 0.5) {
        ObjCRuntime.setOrigin(button, x = x, y = targetY)
      }
    }
  }

  private fun buildMenu() {
    val mainMenu = ObjCRuntime.allocInit("NSMenu")
    for ((title, items) in config.menus) {
      val holder = ObjCRuntime.allocInit("NSMenuItem")
      ObjCRuntime.void1(mainMenu, "addItem:", holder)
      val submenu = ObjCRuntime.allocInit("NSMenu")
      ObjCRuntime.void1(holder, "setSubmenu:", submenu)
      if (title.isNotEmpty()) {
        ObjCRuntime.void1(submenu, "setTitle:", ObjCRuntime.nsString(title))
      }
      for (itemSpec in items) addItemTo(submenu, itemSpec)
    }
    ObjCRuntime.void1(application, "setMainMenu:", mainMenu)
  }

  private fun addItemTo(menu: MemorySegment, spec: DesktopMenuItemSpec) {
    if (spec.isSeparator) {
      ObjCRuntime.void1(menu, "addItem:", ObjCRuntime.send0(ObjCRuntime.classNamed("NSMenuItem"), "separatorItem"))
    } else {
      AppKit.addItemToMenu(menu, spec.title, spec.keyEquivalent, spec.modifierMask, spec.action)
    }
  }

  private fun installNavigationDelegate() {
    val callbackType = MethodType.methodType(
      Void.TYPE,
      MemorySegment::class.java,
      MemorySegment::class.java,
      MemorySegment::class.java,
      MemorySegment::class.java
    )

    val callbackSignature = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)
    val lookup = MethodHandles.lookup()
    val delegateClass = ObjCRuntime.beginClass("GradumNavigationDelegate")

    ObjCRuntime.addImp(
      delegateClass, "webView:didFinishNavigation:", "v@:@@",
      lookup.findStatic(NativeCallbacks::class.java, "didFinishNavigation", callbackType), callbackSignature
    )

    ObjCRuntime.addImp(
      delegateClass, "webView:didFailProvisionalNavigation:withError:", "v@:@@@",
      lookup.findStatic(NativeCallbacks::class.java, "didFailNavigation", callbackType), callbackSignature
    )

    ObjCRuntime.addImp(
      delegateClass, "webView:didFailNavigation:withError:", "v@:@@@",
      lookup.findStatic(NativeCallbacks::class.java, "didFailNavigation", callbackType), callbackSignature
    )

    ObjCRuntime.finishClass(delegateClass)
    navigationDelegate = ObjCRuntime.allocInit(delegateClass)
    ObjCRuntime.void1(webView, "setNavigationDelegate:", navigationDelegate)
  }

  fun setWindowTitle(title: String) {
    if (window != MemorySegment.NULL && title.isNotEmpty()) {
      ObjCRuntime.void1(window, "setTitle:", ObjCRuntime.nsString(title))
    }
  }

  fun run() {
    ObjCRuntime.void0(application, "run")
  }
}

internal object NativeCallbacks {
  @JvmStatic
  @Suppress("unused_parameter")
  fun applicationShouldTerminateAfterLastWindowClosed(
    self: MemorySegment, cmd: MemorySegment, sender: MemorySegment
  ): Byte = 1

  @JvmStatic
  @Volatile
  var current: CocoaApp? = null

  @JvmStatic
  @Suppress("unused_parameter")
  fun applicationDidFinishLaunching(
    self: MemorySegment, cmd: MemorySegment, notification: MemorySegment
  ) {
    current?.launch()
  }

  @JvmStatic
  @Suppress("unused_parameter")
  fun startWindowDrag(
    self: MemorySegment, cmd: MemorySegment, controller: MemorySegment, message: MemorySegment
  ) {
    current?.performWindowDrag()
  }

  @JvmStatic
  @Suppress("unused_parameter")
  fun windowDidResize(
    self: MemorySegment, cmd: MemorySegment, notification: MemorySegment
  ) {
    current?.applyTrafficLightOffset()
  }

  @JvmStatic
  @Suppress("unused_parameter")
  fun windowDidUpdate(
    self: MemorySegment, cmd: MemorySegment, notification: MemorySegment
  ) {
    current?.applyTrafficLightOffset()
  }

  @JvmStatic
  @Suppress("unused_parameter")
  fun didFinishNavigation(
    self: MemorySegment,
    cmd: MemorySegment,
    webView: MemorySegment,
    navigation: MemorySegment
  ) {
    current?.setWindowTitle(ObjCRuntime.utf8(ObjCRuntime.send0(webView, "title")))
  }

  @JvmStatic
  @Suppress("unused_parameter")
  fun didFailNavigation(
    cmd: MemorySegment,
    self: MemorySegment,
    error: MemorySegment,
    webView: MemorySegment,
  ) {
    System.err.println(
      "Gradum desktop: page load failed: " +
        ObjCRuntime.utf8(ObjCRuntime.send0(error, "localizedDescription"))
    )
  }
}
