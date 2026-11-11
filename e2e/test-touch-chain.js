"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const root = path.resolve(__dirname, "..");
const source = fs.readFileSync(path.join(root, "android-app/app/src/main/java/com/pocketcontrol/host/service/RcAccessibilityService.kt"), "utf8");
function section(start, end) {
  const a = source.indexOf(start), b = source.indexOf(end, a);
  if (a < 0 || b < 0) throw new Error(`Source boundary missing: ${start}`);
  return source.slice(a, b);
}
const fields = section("    private val strokes", "    private val am");
const scroll = section("    fun scroll(dy: Int)", "    fun touch(action:");
const touch = source.slice(source.indexOf("    fun touch(action:"), source.lastIndexOf("}"));
const lib = process.env.GRADLE_LIB || path.join(root, "tools/gradle-8.9/lib");
const jars = fs.readdirSync(lib).filter(n => /^(kotlin-|trove4j-|annotations-).*\.jar$/.test(n)).map(n => path.join(lib, n));
const stdlib = jars.find(n => /kotlin-stdlib-[\d.]+\.jar$/.test(n));
if (!stdlib) throw new Error("Set GRADLE_LIB to a Gradle lib directory containing the Kotlin compiler");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "pc-touch-test-"));
const code = `
data class PointF(val x: Float, val y: Float)
class Path {
    lateinit var start: PointF
    lateinit var end: PointF
    fun moveTo(x: Float, y: Float) { start = PointF(x,y); end = start }
    fun lineTo(x: Float, y: Float) { end = PointF(x,y) }
}
class Handler { fun post(block: () -> Unit) { block() } }
class SessionService {
    companion object { val instance: SessionService? = SessionService() }
    fun log(message: String) {}
}
open class GestureResultCallback {
    open fun onCompleted(gesture: GestureDescription?) {}
    open fun onCancelled(gesture: GestureDescription?) {}
}
class GestureDescription(val list: List<StrokeDescription>) {
    companion object { fun getMaxStrokeCount() = 10 }
    class StrokeDescription(val path: Path, val start: Long, val duration: Long, val willContinue: Boolean = false) {
        var parent: StrokeDescription? = null
        init { check(duration > 0) }
        fun continueStroke(path: Path, start: Long, duration: Long, keep: Boolean): StrokeDescription {
            check(willContinue && path.start == this.path.end) { "Broken continuation" }
            return StrokeDescription(path, start, duration, keep).also { it.parent = this }
        }
    }
    class Builder {
        private val list = mutableListOf<StrokeDescription>()
        fun addStroke(stroke: StrokeDescription): Builder { list.add(stroke); return this }
        fun build() = GestureDescription(list.toList())
    }
}
open class FakeService {
    val handler = Handler()
    val history = mutableListOf<GestureDescription>()
    var pendingCallback: GestureResultCallback? = null
    var active = listOf<GestureDescription.StrokeDescription>()
    var rejectNext = false
    fun dispSize() = 1000 to 1000
    fun dispatchGesture(g: GestureDescription, cb: GestureResultCallback?, h: Handler?): Boolean {
        check(pendingCallback == null) { "Overlapping dispatch" }
        if (rejectNext) { rejectNext = false; return false }
        if (active.isNotEmpty()) {
            check(g.list.filter { it.start == 0L }.map { it.parent }.toSet() == active.toSet()) { "First sample loses or re-presses a pointer" }
        }
        check(g.list.any { it.parent == null || it.path.start != it.path.end || !it.willContinue }) { "Empty continuation" }
        history.add(g)
        active = g.list.filter { it.willContinue }
        pendingCallback = cb
        return true
    }
    fun complete() {
        val cb = checkNotNull(pendingCallback) { "No pending gesture" }
        pendingCallback = null
        cb.onCompleted(history.last())
    }
    fun cancel() {
        val cb = checkNotNull(pendingCallback)
        pendingCallback = null
        active = emptyList()
        cb.onCancelled(history.last())
    }
    fun flush() {
        var count = 0
        while (pendingCallback != null) { check(count++ < 50); complete() }
    }
}
class TouchService : FakeService() {
${fields}
${scroll}
${touch}
}
fun test(name: String, block: () -> Unit) { block(); println("PASS: " + name) }
fun main() {
    test("drag / hold / reverse / release preserves one chain and exact coordinates") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        val first = s.active.single()
        s.touch("move", 1, .4f, .1f); s.complete()
        check(s.active.single().parent === first)
        val held = s.active.single()
        val count = s.history.size
        repeat(1000) { s.touch("move", 1, .4f, .1f) }
        check(s.history.size == count && s.pendingCallback == null && s.active.single() === held)
        s.touch("move", 1, .2f, .1f); s.complete()
        check(s.active.single().parent === held && s.active.single().path.end == PointF(200f,100f))
        s.touch("up", 1, .2f, .1f); s.complete()
        check(s.active.isEmpty() && s.history.flatMap { it.list }.count { it.parent == null } == 1)
    }
    test("long press and same-coordinate release do not wiggle") {
        val s = TouchService()
        s.touch("down", 1, .2f, .3f); s.complete()
        check(s.active.single().path.start == s.active.single().path.end)
        repeat(1000) { s.touch("move", 1, .2001f, .3001f) }
        check(s.history.size == 1)
        s.touch("up", 1, .2f, .3f); s.flush()
        check(s.active.isEmpty() && s.history.size == 2)
    }
    test("moves coalesce without overtaking release or next press") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f)
        repeat(100) { s.touch("move", 1, .2f + it/1000f, .1f) }
        s.touch("up", 1, .3f, .1f)
        s.touch("down", 1, .6f, .6f)
        s.touch("up", 1, .6f, .6f)
        check(s.history.size == 1)
        s.flush()
        check(s.history.size == 5 && s.active.isEmpty())
        check(s.history[1].list.single().path.end.x == 299f)
    }
    test("second finger is added without restarting the first, then removed cleanly") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        val first = s.active.single()
        s.touch("down", 2, .8f, .8f); s.complete()
        check(s.active[0].parent === first && s.active[1].start == 1L)
        s.touch("move", 1, .2f, .2f)
        s.touch("move", 2, .7f, .7f); s.flush()
        s.touch("up", 1, .2f, .2f); s.complete()
        check(s.active.size == 1)
        val remaining = s.active.single()
        s.touch("move", 2, .6f, .6f); s.complete()
        check(s.active.single().parent === remaining)
        s.touch("up", 2, .6f, .6f); s.complete()
        check(s.active.isEmpty())
    }
    test("system cancellation never manufactures a new press") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        s.touch("move", 1, .4f, .1f)
        s.touch("move", 1, .5f, .1f)
        val count = s.history.size
        s.cancel()
        s.touch("move", 1, .6f, .1f); s.touch("up", 1, .6f, .1f)
        check(s.history.size == count && s.active.isEmpty())
        s.touch("down", 1, .2f, .2f); s.complete()
        check(s.active.size == 1)
    }
    test("disconnect drains in-flight stroke then releases at its endpoint") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        s.touch("move", 1, .4f, .1f)
        s.touch("move", 1, .5f, .1f)
        s.resetTouches(); s.flush()
        check(s.active.isEmpty() && s.history.size == 3)
        check(s.history.last().list.single().path.end == PointF(400f,100f))
    }
    test("rapid tap and reset while idle are safe") {
        val s = TouchService()
        s.resetTouches()
        s.touch("down", 1, .1f, .1f); s.touch("up", 1, .1f, .1f); s.flush()
        s.resetTouches()
        check(s.history.size == 2 && s.active.isEmpty())
    }
    test("synchronous rejection clears state for next real press") {
        val s = TouchService()
        s.rejectNext = true
        s.touch("down", 1, .1f, .1f)
        s.touch("move", 1, .3f, .3f); s.touch("up", 1, .3f, .3f)
        check(s.history.isEmpty())
        s.touch("down", 1, .1f, .1f); s.complete()
        check(s.active.size == 1)
    }
    test("rejected move retains the accepted endpoint for release") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        val held = s.active.single()
        s.rejectNext = true
        s.touch("move", 1, .8f, .8f)
        check(s.active.single() === held)
        s.touch("up", 1, .8f, .8f); s.flush()
        check(s.active.isEmpty() && s.history.last().list.single().parent === held)
        check(s.history.last().list.single().path.end == PointF(100f,100f))
    }
    test("rejected release still permits disconnect cleanup") {
        val s = TouchService()
        s.touch("down", 1, .1f, .1f); s.complete()
        s.rejectNext = true
        s.touch("up", 1, .1f, .1f)
        s.rejectNext = true
        s.resetTouches()
        check(s.active.size == 1)
        s.resetTouches(); s.flush()
        check(s.active.isEmpty() && s.history.size == 2)
    }
    test("wheel finishes before touch, wheel is ignored while a finger holds") {
        val s = TouchService()
        s.scroll(1)
        s.touch("down", 1, .1f, .1f)
        check(s.history.size == 1)
        s.complete(); s.complete()
        check(s.history.size == 2 && s.active.size == 1)
        s.scroll(1)
        check(s.history.size == 2)
        s.touch("up", 1, .1f, .1f); s.complete()
        s.scroll(1); s.complete()
        check(s.active.isEmpty())
    }
    test("duplicate down is ignored and edge coordinates stay within screen") {
        val s = TouchService()
        s.touch("down", 1, 1f, 1f); s.complete()
        s.touch("down", 1, .2f, .2f)
        check(s.history.size == 1 && s.active.single().path.end == PointF(999f,999f))
        s.touch("up", 1, 1f, 1f); s.complete()
    }
    println("Touch queue tests passed (fake Android dispatcher; device behavior not covered).")
}
`;
try {
  const file = path.join(tmp, "TouchTest.kt");
  fs.writeFileSync(file, code);
  execFileSync("java", ["-cp", jars.join(path.delimiter), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", stdlib, "-nowarn", "-d", path.join(tmp, "classes"), file], { stdio: "inherit" });
  execFileSync("java", ["-cp", [path.join(tmp, "classes"), stdlib].join(path.delimiter), "TouchTestKt"], { stdio: "inherit" });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}
