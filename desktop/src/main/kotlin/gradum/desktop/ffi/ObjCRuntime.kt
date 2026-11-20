package gradum.desktop.ffi

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

internal object ObjCRuntime {
  val globalArena: Arena = Arena.global()
  private val linker: Linker = Linker.nativeLinker()
  private val objcSymbolLookup: SymbolLookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", globalArena)

  init {
    val dlopenHandle = linker.downcallHandle(
      SymbolLookup.loaderLookup().find("dlopen")
        .or { objcSymbolLookup.find("dlopen") }
        .orElseThrow { IllegalStateException("missing native symbol: dlopen") },
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT),
    )
    for (framework in listOf(
      "/System/Library/Frameworks/AppKit.framework/AppKit",
      "/System/Library/Frameworks/WebKit.framework/WebKit",
    )) {
      val loaded = dlopenHandle.invoke(globalArena.allocateFrom(framework), 0x2 /* RTLD_NOW */) as MemorySegment
      check(loaded != MemorySegment.NULL) { "failed to load $framework" }
    }
  }

  internal fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle {
    val symbol = objcSymbolLookup.find(name)
      .orElseThrow { IllegalStateException("missing native symbol: $name") }
    return linker.downcallHandle(symbol, descriptor)
  }

  internal fun msgSendHandle(descriptor: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(
      objcSymbolLookup.find("objc_msgSend").orElseThrow {
        IllegalStateException("missing native symbol: objc_msgSend")
      },
      descriptor,
    )

  val NS_RECT: MemoryLayout = MemoryLayout.structLayout(
    JAVA_DOUBLE.withName("x"),
    JAVA_DOUBLE.withName("y"),
    JAVA_DOUBLE.withName("width"),
    JAVA_DOUBLE.withName("height"),
  )

  private val selectorCache = HashMap<String, MemorySegment>()

  fun sel(name: String): MemorySegment = selectorCache.getOrPut(name) {
    guard { NativeBindings.selRegisterNameHandle.invoke(globalArena.allocateFrom(name)) } as MemorySegment
  }

  fun classNamed(name: String): MemorySegment {
    val cls = guard { NativeBindings.objcGetClassHandle.invoke(globalArena.allocateFrom(name)) } as MemorySegment
    check(cls != MemorySegment.NULL) { "unknown Objective-C class: $name" }
    return cls
  }

  fun nsRect(x: Double, y: Double, width: Double, height: Double): MemorySegment {
    val buffer = globalArena.allocate(NS_RECT)
    buffer.set(JAVA_DOUBLE, 0, x)
    buffer.set(JAVA_DOUBLE, 8, y)
    buffer.set(JAVA_DOUBLE, 16, width)
    buffer.set(JAVA_DOUBLE, 24, height)
    return buffer
  }

  fun nsString(value: String): MemorySegment =
    send1(classNamed("NSString"), "stringWithUTF8String:", globalArena.allocateFrom(value))

  fun allocInit(className: String): MemorySegment = send0(send0(classNamed(className), "alloc"), "init")

  fun allocInit(cls: MemorySegment): MemorySegment = send0(send0(cls, "alloc"), "init")

  fun alloc(cls: MemorySegment): MemorySegment = send0(cls, "alloc")

  fun beginClass(name: String, superClassName: String = "NSObject"): MemorySegment {
    val cls = guard {
      NativeBindings.objcAllocateClassPairHandle.invoke(classNamed(superClassName), globalArena.allocateFrom(name), 0L)
    } as MemorySegment
    check(cls != MemorySegment.NULL) { "class $name already exists or cannot be allocated" }
    return cls
  }

  fun addImp(cls: MemorySegment, selector: String, types: String, target: MethodHandle, descriptor: FunctionDescriptor) {
    val methodUpcallStub = linker.upcallStub(target, descriptor, globalArena)
    val methodAddedSuccess = guard {
      NativeBindings.classAddMethodHandle.invoke(cls, sel(selector), methodUpcallStub, globalArena.allocateFrom(types))
    } as Byte
    check(methodAddedSuccess != 0.toByte()) { "class_addMethod failed for $selector" }
  }

  fun finishClass(cls: MemorySegment) {
    guard { NativeBindings.objcRegisterClassPairHandle.invoke(cls) }
  }

  fun send0(receiver: MemorySegment, selector: String): MemorySegment =
    guard { NativeBindings.sendId0Handle.invoke(receiver, sel(selector)) } as MemorySegment

  fun send1(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment =
    guard { NativeBindings.sendId1Handle.invoke(receiver, sel(selector), arg) } as MemorySegment

  fun void0(receiver: MemorySegment, selector: String) {
    guard { NativeBindings.sendVoid0Handle.invoke(receiver, sel(selector)) }
  }

  fun void1(receiver: MemorySegment, selector: String, arg: MemorySegment) {
    guard { NativeBindings.sendVoid1Handle.invoke(receiver, sel(selector), arg) }
  }

  fun voidLong(receiver: MemorySegment, selector: String, arg: Long) {
    guard { NativeBindings.sendVoidLongHandle.invoke(receiver, sel(selector), arg) }
  }

  fun voidByte(receiver: MemorySegment, selector: String, arg: Byte) {
    guard { NativeBindings.sendVoidByteHandle.invoke(receiver, sel(selector), arg) }
  }

  fun utf8(string: MemorySegment): String {
    if (string == MemorySegment.NULL) return ""
    val chars = send0(string, "UTF8String").reinterpret(Long.MAX_VALUE)
    if (chars == MemorySegment.NULL) return ""
    var length = 0
    while (chars.get(JAVA_BYTE, length.toLong()) != 0.toByte()) length++
    val bytes = ByteArray(length)
    for (index in 0 until length) bytes[index] = chars.get(JAVA_BYTE, index.toLong())
    return String(bytes, Charsets.UTF_8)
  }

  inline fun <T> autorelease(block: () -> T): T {
    val pool = guard { NativeBindings.autoreleasePoolPushHandle.invoke() } as MemorySegment
    try {
      return block()
    } finally {
      guard { NativeBindings.autoreleasePoolPopHandle.invoke(pool) }
    }
  }

  internal inline fun guard(block: () -> Any?): Any? =
    try {
      block()
    } catch (error: Throwable) {
      throw RuntimeException("native call failed", error)
    }
}
