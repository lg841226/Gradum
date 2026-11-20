package gradum.desktop.ffi

import java.lang.foreign.MemorySegment

internal object AppKit {
  fun initWithContentRect(
    defer: Byte,
    backing: Long,
    styleMask: Long,
    frame: MemorySegment,
    window: MemorySegment
  ): MemorySegment = ObjCRuntime.guard {
    NativeBindings.initWindowHandle.invoke(
      window, ObjCRuntime.sel("initWithContentRect:styleMask:backing:defer:"), frame, styleMask, backing, defer
    )
  } as MemorySegment

  fun initWithFrameConfiguration(
    frame: MemorySegment,
    webView: MemorySegment,
    configuration: MemorySegment
  ): MemorySegment = ObjCRuntime.guard {
    NativeBindings.initWebViewHandle.invoke(webView, ObjCRuntime.sel("initWithFrame:configuration:"), frame, configuration)
  } as MemorySegment

  fun addItemToMenu(menu: MemorySegment, title: String, keyEquivalent: String, modifierMask: Long, action: String) {
    val item = ObjCRuntime.guard {
      NativeBindings.initMenuItemHandle.invoke(
        ObjCRuntime.alloc(ObjCRuntime.classNamed("NSMenuItem")),
        ObjCRuntime.sel("initWithTitle:action:keyEquivalent:"),
        ObjCRuntime.nsString(title), ObjCRuntime.sel(action), ObjCRuntime.nsString(keyEquivalent),
      )
    } as MemorySegment
    ObjCRuntime.voidLong(item, "setKeyEquivalentModifierMask:", modifierMask)
    ObjCRuntime.void1(menu, "addItem:", item)
  }
}
