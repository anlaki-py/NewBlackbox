package top.niunaijun.blackboxa.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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

    @Test
    fun clearLogDirectory_removesAllLogFilesAndSubdirectories() {
        val directory = Files.createTempDirectory("app-diagnostics-test").toFile()
        directory.resolve("launcher.log").writeText("launch")
        directory.resolve("nested").apply {
            mkdir()
            resolve("crash.log").writeText("crash")
        }

        assertTrue(AppDiagnostics.clearLogDirectory(directory))
        assertTrue(directory.exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun clearLogDirectory_succeedsWhenDirectoryDoesNotExist() {
        val directory = Files.createTempDirectory("app-diagnostics-test").toFile()
        directory.deleteRecursively()

        assertTrue(AppDiagnostics.clearLogDirectory(directory))
    }

    @Test
    fun clearLogDirectory_failsWhenPathIsNotDirectory() {
        val file = Files.createTempFile("app-diagnostics-test", ".log").toFile()

        assertFalse(AppDiagnostics.clearLogDirectory(file))
        file.delete()
    }
}
