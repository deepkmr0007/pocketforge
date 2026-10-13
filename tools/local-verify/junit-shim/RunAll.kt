package dev.pocketforge.localverify

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.jar.JarFile

/** Minimal JUnit-style runner: finds @Test methods in the test jar, runs each, prints a summary. */
fun main(args: Array<String>) {
    val jar = File(args[0])
    val loader = Thread.currentThread().contextClassLoader
    val classes = JarFile(jar).use { j -> j.entries().toList().map { it.name }
        .filter { it.endsWith("Test.class") && !it.contains('$') }
        .map { it.removeSuffix(".class").replace('/', '.') } }.sorted()
    var pass = 0; var fail = 0
    val failures = ArrayList<String>()
    for (cn in classes) {
        val c = loader.loadClass(cn)
        val tests = c.declaredMethods.filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.jupiter.api.Test" } }.sortedBy { it.name }
        for (m in tests) {
            val inst = c.getDeclaredConstructor().newInstance()
            val t0 = System.nanoTime()
            try {
                m.invoke(inst); pass++
                println("PASS  ${c.simpleName}.${m.name} (${(System.nanoTime() - t0) / 1_000_000} ms)")
            } catch (e: InvocationTargetException) {
                fail++
                val cause = e.targetException
                failures += "${c.simpleName}.${m.name}: ${cause.javaClass.simpleName}: ${cause.message}"
                println("FAIL  ${c.simpleName}.${m.name}: ${cause.javaClass.simpleName}: ${cause.message}")
                cause.stackTrace.take(4).forEach { println("        at $it") }
            }
        }
    }
    println("\n${classes.size} test classes, ${pass + fail} tests: $pass passed, $fail failed")
    if (fail > 0) { failures.forEach { println("  - $it") }; System.exit(1) }
}
