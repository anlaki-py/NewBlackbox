package top.niunaijun.blackboxa.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFilterTest {
    private val sample = """
        ===== BLACKBOX APP DIAGNOSTIC SESSION =====
        Device: Xiaomi 12T
        ===========================================
        2026-08-21 01:00:00.000 +0000 LIFECYCLE beforeApplicationOnCreate
        08-21 01:00:01.000  123  123 I Instagram: login opened
        08-21 01:00:02.000  123  123 W Instagram: unsupported environment
        2026-08-21 01:00:03.000 +0000 ERROR virtual application startup
        java.lang.IllegalStateException: rejected
            at com.instagram.Login.open(Login.java:10)
    """.trimIndent()

    @Test
    fun errors_keepTheirStackTraceAndExcludeRegularLogs() {
        val filtered = DiagnosticLogFilter.apply(sample, DiagnosticLogFilter.Category.ERRORS)

        assertTrue(filtered.contains("IllegalStateException"))
        assertTrue(filtered.contains("Login.java:10"))
        assertFalse(filtered.contains("login opened"))
    }

    @Test
    fun warnings_areSeparateFromErrors() {
        val filtered = DiagnosticLogFilter.apply(sample, DiagnosticLogFilter.Category.WARNINGS)

        assertTrue(filtered.contains("unsupported environment"))
        assertFalse(filtered.contains("IllegalStateException"))
    }

    @Test
    fun lifecycle_excludesDeviceAndLogcatRecords() {
        val filtered = DiagnosticLogFilter.apply(sample, DiagnosticLogFilter.Category.LIFECYCLE)

        assertTrue(filtered.contains("beforeApplicationOnCreate"))
        assertFalse(filtered.contains("Xiaomi 12T"))
        assertFalse(filtered.contains("login opened"))
    }
}
