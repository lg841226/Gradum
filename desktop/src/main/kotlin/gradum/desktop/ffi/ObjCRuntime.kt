package gradum.desktop.ffi

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle

/**
 * Pure Objective-C runtime bridge over FFM: selectors, classes, messages and
 * dynamic classes. Fixed-arity objc_msgSend downcalls only; on arm64 every
 * object/selector argument is one pointer register. All memory lives in
 * Arena.global; the process owns it for its whole life.
 */
internal object ObjCRuntime {
  val arena: Arena = Arena.global()
  private val linker: Linker = Linker.nativeLinker()

  private val libobjc: SymbolLookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena)

  init {
    val dlopen = linker.downcallHandle(
      SymbolLookup.loaderLookup().find("dlopen")
        .or { libobjc.find("dlopen") }
        .orElseThrow { IllegalStateException("missing native symbol: dlopen") },
      FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT),
    )
    for (framework in listOf(
      "/System/Library/Frameworks/AppKit.framework/AppKit",
      "/System/Library/Frameworks/WebKit.framework/WebKit",
    )) {
      val loaded = dlopen.invoke(arena.allocateFrom(framework), 0x2 /* RTLD_NOW */) as MemorySegment
      check(loaded != MemorySegment.NULL) { "failed to load $framework" }
    }
  }

  private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle {
    val symbol = libobjc.find(name)
      .orElseThrow { IllegalStateException("missing native symbol: $name") }
    return linker.downcallHandle(symbol, descriptor)
  }

  fun msgSendHandle(descriptor: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(
      libobjc.find("objc_msgSend").orElseThrow { IllegalStateException("missing native symbol: objc_msgSend") },
      descriptor,
    )

  val NS_RECT: MemoryLayout = MemoryLayout.structLayout(
    JAVA_DOUBLE.withName("x"),
    JAVA_DOUBLE.withName("y"),
    JAVA_DOUBLE.withName("width"),
    JAVA_DOUBLE.withName("height"),
  )

  private val selRegister = downcall("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS))
  private val getClass = downcall("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS))
  private val allocateClassPair = downcall(
    "objc_allocateClassPair", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)
  )
  private val registerClassPair = downcall("objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS))
  private val addMethod = downcall(
    "class_addMethod", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
  )
  private val poolPush = downcall("objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS))
  private val poolPop = downcall("objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS))

  private val msgObject0 = msgSendHandle(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
  private val msgObject1 = msgSendHandle(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
  private val msgVoid0 = msgSendHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
  private val msgVoid1 = msgSendHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
  private val msgVoidLong = msgSendHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))
  private val msgVoidByte = msgSendHandle(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE))

  private val selectorCache = HashMap<String, MemorySegment>()

  fun sel(name: String): MemorySegment = selectorCache.getOrPut(name) {
    guard { selRegister.invoke(arena.allocateFrom(name)) } as MemorySegment
  }

  fun classNamed(name: String): MemorySegment {
    val cls = guard { getClass.invoke(arena.allocateFrom(name)) } as MemorySegment
    check(cls != MemorySegment.NULL) { "unknown Objective-C class: $name" }
    return cls
  }

  fun rect(x: Double, y: Double, width: Double, height: Double): MemorySegment {
    val buffer = arena.allocate(NS_RECT)
    buffer.set(JAVA_DOUBLE, 0, x)
    buffer.set(JAVA_DOUBLE, 8, y)
    buffer.set(JAVA_DOUBLE, 16, width)
    buffer.set(JAVA_DOUBLE, 24, height)
    return buffer
  }

  fun nsString(value: String): MemorySegment =
    send1(classNamed("NSString"), "stringWithUTF8String:", arena.allocateFrom(value))

  fun allocInit(className: String): MemorySegment = send0(send0(classNamed(className), "alloc"), "init")

  fun allocInit(cls: MemorySegment): MemorySegment = send0(send0(cls, "alloc"), "init")

  fun alloc(cls: MemorySegment): MemorySegment = send0(cls, "alloc")

  fun beginClass(name: String, superClassName: String = "NSObject"): MemorySegment {
    val cls = guard {
      allocateClassPair.invoke(classNamed(superClassName), arena.allocateFrom(name), 0L)
    } as MemorySegment
    check(cls != MemorySegment.NULL) { "class $name already exists or cannot be allocated" }
    return cls
  }

  fun addImp(cls: MemorySegment, selector: String, types: String, target: MethodHandle, descriptor: FunctionDescriptor) {
    val stub = linker.upcallStub(target, descriptor, arena)
    val ok = guard { addMethod.invoke(cls, sel(selector), stub, arena.allocateFrom(types)) } as Byte
    check(ok != 0.toByte()) { "class_addMethod failed for $selector" }
  }

  fun finishClass(cls: MemorySegment) {
    guard { registerClassPair.invoke(cls) }
  }

  fun send0(receiver: MemorySegment, selector: String): MemorySegment =
    guard { msgObject0.invoke(receiver, sel(selector)) } as MemorySegment

  fun send1(receiver: MemorySegment, selector: String, arg: MemorySegment): MemorySegment =
    guard { msgObject1.invoke(receiver, sel(selector), arg) } as MemorySegment

  fun void0(receiver: MemorySegment, selector: String) {
    guard { msgVoid0.invoke(receiver, sel(selector)) }
  }

  fun void1(receiver: MemorySegment, selector: String, arg: MemorySegment) {
    guard { msgVoid1.invoke(receiver, sel(selector), arg) }
  }

  fun voidLong(receiver: MemorySegment, selector: String, arg: Long) {
    guard { msgVoidLong.invoke(receiver, sel(selector), arg) }
  }

  fun voidByte(receiver: MemorySegment, selector: String, arg: Byte) {
    guard { msgVoidByte.invoke(receiver, sel(selector), arg) }
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
    val pool = guard { poolPush.invoke() } as MemorySegment
    try {
      return block()
    } finally {
      guard { poolPop.invoke(pool) }
    }
  }

  internal inline fun guard(block: () -> Any?): Any? =
    try {
      block()
    } catch (error: Throwable) {
      throw RuntimeException("native call failed", error)
    }
}
