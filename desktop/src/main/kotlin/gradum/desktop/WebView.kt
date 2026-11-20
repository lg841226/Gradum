package gradum.desktop

import gradum.desktop.ffi.AppKit
import gradum.desktop.ffi.ObjCRuntime
import java.lang.foreign.MemorySegment

internal object WebView {
  fun create(frame: MemorySegment, url: String, dragHandler: MemorySegment? = null): MemorySegment {
    val configuration = ObjCRuntime.allocInit("WKWebViewConfiguration")
    if (dragHandler != null) {
      val controller = ObjCRuntime.send0(configuration, "userContentController")
      ObjCRuntime.void2(controller, "addScriptMessageHandler:name:", dragHandler, ObjCRuntime.nsString("drag"))
    }
    val webView = AppKit.initWithFrameConfiguration(
      webView = ObjCRuntime.alloc(ObjCRuntime.classNamed("WKWebView")),
      frame = frame,
      configuration = configuration,
    )
    check(webView != MemorySegment.NULL) { "WKWebView creation failed" }
    ObjCRuntime.void1(webView, "loadRequest:", urlRequest(url))
    return webView
  }

  private fun urlRequest(url: String): MemorySegment {
    val nsUrl = ObjCRuntime.send1(ObjCRuntime.classNamed("NSURL"), "URLWithString:", ObjCRuntime.nsString(url))
    return ObjCRuntime.send1(ObjCRuntime.classNamed("NSURLRequest"), "requestWithURL:", nsUrl)
  }
}
