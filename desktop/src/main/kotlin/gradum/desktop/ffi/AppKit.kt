package gradum.desktop.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.*

internal object AppKit {
  private val initWindow = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ObjCRuntime.NS_RECT, JAVA_LONG, JAVA_LONG, JAVA_BYTE)
  )
  private val initWebView = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ObjCRuntime.NS_RECT, ADDRESS)
  )
  private val initMenuItem = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )

  fun initWithContentRect(
    window: MemorySegment,
    frame: MemorySegment,
    styleMask: Long,
    backing: Long,
    defer: Byte,
  ): MemorySegment = ObjCRuntime.guard {
    initWindow.invoke(
      window, ObjCRuntime.sel("initWithContentRect:styleMask:backing:defer:"), frame, styleMask, backing, defer
    )
  } as MemorySegment

  fun initWithFrameConfiguration(
    webView: MemorySegment,
    frame: MemorySegment,
    configuration: MemorySegment,
  ): MemorySegment = ObjCRuntime.guard {
    initWebView.invoke(webView, ObjCRuntime.sel("initWithFrame:configuration:"), frame, configuration)
  } as MemorySegment

  fun addItemToMenu(menu: MemorySegment, title: String, keyEquivalent: String, modifierMask: Long, action: String) {
    val item = ObjCRuntime.guard {
      initMenuItem.invoke(
        ObjCRuntime.alloc(ObjCRuntime.classNamed("NSMenuItem")),
        ObjCRuntime.sel("initWithTitle:action:keyEquivalent:"),
        ObjCRuntime.nsString(title), ObjCRuntime.sel(action), ObjCRuntime.nsString(keyEquivalent),
      )
    } as MemorySegment
    ObjCRuntime.voidLong(item, "setKeyEquivalentModifierMask:", modifierMask)
    ObjCRuntime.void1(menu, "addItem:", item)
  }
}
