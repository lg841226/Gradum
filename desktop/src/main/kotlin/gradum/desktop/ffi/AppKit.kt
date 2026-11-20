package gradum.desktop.ffi

import java.lang.foreign.MemorySegment

/**
 * Designated initializers of the AppKit/WebKit objects the shell uses; their
 * handles live in [NativeBindings].
 */
internal object AppKit {
  fun initWithContentRect(
    window: MemorySegment,
    frame: MemorySegment,
    styleMask: Long,
    backing: Long,
    defer: Byte,
  ): MemorySegment = ObjCRuntime.guard {
    NativeBindings.initWindowHandle.invoke(
      window, ObjCRuntime.sel("initWithContentRect:styleMask:backing:defer:"), frame, styleMask, backing, defer
    )
  } as MemorySegment

  fun initWithFrameConfiguration(
    webView: MemorySegment,
    frame: MemorySegment,
    configuration: MemorySegment,
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
