package android.util

/**
 * JVM 单元测试用的 `android.util.Log` 桩。
 *
 * 为什么需要它：`scripts/run-unit-tests.sh` 是把 Gradle 编译好的 class 直接喂给
 * JUnitCore 的，classpath 上**没有 android.jar**（这正是 [com.ticketfortwo.app.rtc.IceProbe]
 * 只能用 `System.nanoTime()` 而不能用 `SystemClock` 的原因）。而 `WsClient` 一进门就
 * `Log.i`，缺了这个桩的话，任何碰到它的测试都会先 NoClassDefFoundError。
 *
 * 只要"能调用、返回 0"，不需要真的 Android，所以不引 Robolectric。
 * 放在 test 源集里，不会进 APK。
 */
object Log {
    private fun emit(level: Char, tag: String?, msg: String): Int {
        println("$level/$tag: $msg")
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String): Int = emit('V', tag, msg)
    @JvmStatic fun d(tag: String?, msg: String): Int = emit('D', tag, msg)
    @JvmStatic fun i(tag: String?, msg: String): Int = emit('I', tag, msg)
    @JvmStatic fun w(tag: String?, msg: String): Int = emit('W', tag, msg)
    @JvmStatic fun e(tag: String?, msg: String): Int = emit('E', tag, msg)
    @JvmStatic fun w(tag: String?, msg: String, t: Throwable?): Int = emit('W', tag, "$msg / $t")
    @JvmStatic fun e(tag: String?, msg: String, t: Throwable?): Int = emit('E', tag, "$msg / $t")
}
