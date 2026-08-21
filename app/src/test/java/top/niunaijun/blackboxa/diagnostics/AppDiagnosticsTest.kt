package top.niunaijun.blackboxa.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class AppDiagnosticsTest {
    @Test
    fun safeFileKey_keepsPackageNames() {
        assertEquals("com.instagram.android", AppDiagnostics.safeFileKey("com.instagram.android"))
    }

    @Test
    fun safeFileKey_removesPathCharacters() {
        assertEquals(".._unsafe_name", AppDiagnostics.safeFileKey("../unsafe/name"))
    }

    @Test
    fun safeFileKey_replacesBlankValues() {
        assertEquals("unknown", AppDiagnostics.safeFileKey(""))
        assertEquals("unknown", AppDiagnostics.safeFileKey(".."))
    }
}
