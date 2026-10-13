// Local-only stand-in for org.junit.jupiter.api.Test so engine tests compile without Maven access.
// Gradle/CI builds use the real JUnit 5; this file is never part of the app.
package org.junit.jupiter.api

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test
