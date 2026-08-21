package top.niunaijun.blackboxa.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AppDiagnostics {
    private const val MAX_SESSION_BYTES = 2L * 1024 * 1024
    private const val MAX_DISPLAY_BYTES = 4L * 1024 * 1024
    private const val MAX_SESSION_FILES = 8
    private const val LAUNCH_LOG_NAME = "launcher.log"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    @Volatile
    private var activeSession: DiagnosticSession? = null

    @JvmStatic
    fun recordLaunchRequested(packageName: String, userId: Int) {
        appendLauncherEvent(packageName, userId, "Launch requested from BlackBox")
    }

    @JvmStatic
    fun recordLaunchResult(packageName: String, userId: Int, launched: Boolean) {
        val result = if (launched) "Launch request accepted" else "Launch request failed"
        appendLauncherEvent(packageName, userId, result)
    }

    @JvmStatic
    fun startSession(context: Context, packageName: String, processName: String?, userId: Int) {
        if (activeSession != null) return

        runCatching {
            val directory = diagnosticsDirectory(packageName, userId)
            directory.mkdirs()
            pruneOldSessions(directory)

            val process = processName.orEmpty().ifBlank { packageName }
            val fileName = "${fileTimestampFormat.format(Date())}_${Process.myPid()}_${safeFileKey(process)}.log"
            val session = DiagnosticSession(File(directory, fileName))
            activeSession = session
            session.append(buildSessionHeader(context, packageName, process, userId))
            installCrashHandler(session, packageName, process, userId)
            startLogcatCapture(session)
        }.onFailure {
            android.util.Log.e("AppDiagnostics", "Unable to start diagnostics for $packageName", it)
        }
    }

    @JvmStatic
    fun recordLifecycle(event: String) {
        activeSession?.append("${timestamp()} LIFECYCLE $event\n")
    }

    @JvmStatic
    fun recordError(event: String, throwable: Throwable) {
        activeSession?.append("${timestamp()} ERROR $event\n${stackTrace(throwable)}\n")
    }

    @JvmStatic
    fun readLogs(packageName: String, userId: Int): String {
        val directory = diagnosticsDirectory(packageName, userId)
        val files = directory.listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (files.isEmpty()) {
            return "No diagnostics have been recorded for this app yet.\n\nLaunch it once, then return here."
        }

        val result = StringBuilder()
        var bytesRead = 0L
        for (file in files) {
            if (bytesRead >= MAX_DISPLAY_BYTES) break
            val remaining = (MAX_DISPLAY_BYTES - bytesRead).coerceAtMost(file.length()).toInt()
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(remaining)
                var offset = 0
                while (offset < buffer.size) {
                    val count = input.read(buffer, offset, buffer.size - offset)
                    if (count < 0) break
                    offset += count
                }
                buffer.copyOf(offset)
            }
            result.append("===== ").append(file.name).append(" =====\n")
            result.append(bytes.toString(Charsets.UTF_8)).append("\n\n")
            bytesRead += bytes.size
        }
        if (files.sumOf { it.length() } > bytesRead) {
            result.append("[Older log content omitted from this view.]\n")
        }
        return result.toString()
    }

    @JvmStatic
    fun clearLogs(packageName: String, userId: Int): Boolean {
        return clearLogDirectory(diagnosticsDirectory(packageName, userId))
    }

    internal fun clearLogDirectory(directory: File): Boolean {
        if (!directory.exists()) return true
        val entries = directory.listFiles() ?: return false
        return entries.fold(true) { allDeleted, entry -> entry.deleteRecursively() && allDeleted }
    }

    internal fun safeFileKey(value: String): String {
        val sanitized = value.map { character ->
            if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') {
                character
            } else {
                '_'
            }
        }.joinToString("").take(160)
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "unknown"
    }

    private fun installCrashHandler(
        session: DiagnosticSession,
        packageName: String,
        processName: String,
        userId: Int
    ) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                session.append(buildCrashReport(packageName, processName, userId, thread, throwable))
                session.close()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun startLogcatCapture(session: DiagnosticSession) {
        Thread({
            try {
                val command = mutableListOf("logcat", "-v", "threadtime")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    command.add("--pid=${Process.myPid()}")
                }
                val logcatProcess = ProcessBuilder(command).redirectErrorStream(true).start()
                session.append("${timestamp()} LOGGER logcat capture started\n")
                logcatProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!session.append("$line\n")) {
                            logcatProcess.destroy()
                            break
                        }
                    }
                }
            } catch (throwable: Throwable) {
                session.append("${timestamp()} LOGGER logcat capture failed: $throwable\n")
            }
        }, "blackbox-app-diagnostics").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendLauncherEvent(packageName: String, userId: Int, message: String) {
        runCatching {
            val directory = diagnosticsDirectory(packageName, userId)
            directory.mkdirs()
            val file = File(directory, LAUNCH_LOG_NAME)
            if (file.length() >= MAX_SESSION_BYTES / 4) file.writeText("")
            file.appendText("${timestamp()} LAUNCHER $message\n")
        }.onFailure {
            android.util.Log.e("AppDiagnostics", "Unable to record launch for $packageName", it)
        }
    }

    private fun diagnosticsDirectory(packageName: String, userId: Int): File {
        return File(BEnvironment.getVirtualRoot(), "diagnostics/$userId/${safeFileKey(packageName)}")
    }

    private fun pruneOldSessions(directory: File) {
        directory.listFiles { file -> file.isFile && file.extension == "log" && file.name != LAUNCH_LOG_NAME }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_SESSION_FILES - 1)
            ?.forEach { it.delete() }
    }

    private fun buildSessionHeader(context: Context, packageName: String, processName: String, userId: Int): String {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val hostInfo = runCatching {
            BlackBoxCore.getContext().packageManager.getPackageInfo(BlackBoxCore.getHostPkg(), 0)
        }.getOrNull()
        val runtime = Runtime.getRuntime()
        return buildString {
            appendLine("===== BLACKBOX APP DIAGNOSTIC SESSION =====")
            appendLine("Started: ${timestamp()}")
            appendLine("Package: $packageName")
            appendLine("App version: ${packageInfo?.versionName ?: "unknown"} (${packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: -1})")
            appendLine("Target SDK: ${packageInfo?.applicationInfo?.targetSdkVersion ?: -1}")
            appendLine("Virtual user: $userId")
            appendLine("Process: $processName")
            appendLine("PID / UID: ${Process.myPid()} / ${Process.myUid()}")
            appendLine("Host version: ${hostInfo?.versionName ?: "unknown"} (${hostInfo?.let(PackageInfoCompat::getLongVersionCode) ?: -1})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Build fingerprint: ${Build.FINGERPRINT}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Memory free / total / max: ${runtime.freeMemory()} / ${runtime.totalMemory()} / ${runtime.maxMemory()}")
            appendLine("===========================================")
        }
    }

    private fun buildCrashReport(
        packageName: String,
        processName: String,
        userId: Int,
        thread: Thread,
        throwable: Throwable
    ): String {
        val runtime = Runtime.getRuntime()
        return buildString {
            appendLine()
            appendLine("===== UNCAUGHT JAVA CRASH =====")
            appendLine("Time: ${timestamp()}")
            appendLine("Package: $packageName")
            appendLine("Virtual user: $userId")
            appendLine("Process: $processName (${Process.myPid()})")
            appendLine("Thread: ${thread.name} (${thread.id}), state=${thread.state}")
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine("Memory free / total / max: ${runtime.freeMemory()} / ${runtime.totalMemory()} / ${runtime.maxMemory()}")
            appendLine("Elapsed CPU ms: ${Process.getElapsedCpuTime()}")
            appendLine("Stack trace:")
            appendLine(stackTrace(throwable))
            appendLine("===== END CRASH =====")
        }
    }

    private fun stackTrace(throwable: Throwable): String {
        val output = StringWriter()
        throwable.printStackTrace(PrintWriter(output))
        return output.toString()
    }

    private fun timestamp(): String = synchronized(timestampFormat) {
        timestampFormat.format(Date())
    }

    private class DiagnosticSession(private val file: File) {
        private val closed = AtomicBoolean(false)
        private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))

        @Synchronized
        fun append(text: String): Boolean {
            if (closed.get()) return false
            if (file.length() + text.toByteArray(Charsets.UTF_8).size > MAX_SESSION_BYTES) {
                writer.appendLine("${timestamp()} LOGGER Session limit reached; further output was dropped.")
                writer.flush()
                close()
                return false
            }
            writer.append(text)
            writer.flush()
            return true
        }

        @Synchronized
        fun close() {
            if (closed.compareAndSet(false, true)) {
                writer.flush()
                writer.close()
            }
        }
    }
}
