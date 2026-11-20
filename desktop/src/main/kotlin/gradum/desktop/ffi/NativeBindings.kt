package gradum.desktop.ffi

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

internal object NativeBindings {
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

  val sendVoid2Handle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )
  val sendVoidLongHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG)
  )
  val sendVoidByteHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE)
  )

  val sendIdLongHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)
  )

  val frameHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.of(ObjCRuntime.NS_RECT, ADDRESS, ADDRESS)
  )

  val setFrameOriginHandle: MethodHandle = ObjCRuntime.msgSendHandle(
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ObjCRuntime.NS_POINT)
  )

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
