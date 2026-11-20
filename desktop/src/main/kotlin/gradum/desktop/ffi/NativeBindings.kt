package gradum.desktop.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

/**
 * Every native downcall handle in one place, so the call sites stay readable.
 * Loading this object forces ObjCRuntime's dlopen pass first.
 */
internal object NativeBindings {
  // Objective-C runtime entry points.
  val selRegisterNameHandle: MethodHandle = ObjCRuntime.downcall(
    "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS)
  )
  val objcGetClassHandle: MethodHandle = ObjCRuntime.downcall(
    "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS)
  )
  val objcAllocateClassPairHandle: MethodHandle = ObjCRuntime.downcall(
    "objc_allocateClassPair", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)
  )
  val objcRegisterClassPairHandle: MethodHandle = ObjCRuntime.downcall(
    "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS)
  )
  val classAddMethodHandle: MethodHandle = ObjCRuntime.downcall(
    "class_addMethod", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )
  val autoreleasePoolPushHandle: MethodHandle = ObjCRuntime.downcall(
    "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS)
  )
  val autoreleasePoolPopHandle: MethodHandle = ObjCRuntime.downcall(
    "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS)
  )

  // objc_msgSend shapes used by the send/void helpers.
  val sendId0Handle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)
  )
  val sendId1Handle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )
  val sendVoid0Handle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)
  )
  val sendVoid1Handle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)
  )
  val sendVoidLongHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG)
  )
  val sendVoidByteHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE)
  )

  // Framework initializers with struct or mixed-layout arguments.
  val initWindowHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ObjCRuntime.NS_RECT, JAVA_LONG, JAVA_LONG, JAVA_BYTE)
  )
  val initWebViewHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ObjCRuntime.NS_RECT, ADDRESS)
  )
  val initMenuItemHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )
}
