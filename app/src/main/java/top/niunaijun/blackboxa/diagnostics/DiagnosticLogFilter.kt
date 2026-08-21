package top.niunaijun.blackboxa.diagnostics

object DiagnosticLogFilter {
    enum class Category {
        ALL,
        ERRORS,
        WARNINGS,
        LIFECYCLE,
        DEVICE,
        LOGCAT
    }

    private val appEventPattern = Regex(
        "^\\d{4}-\\d{2}-\\d{2} .* (LIFECYCLE|ERROR|LAUNCHER|LOGGER)\\b"
    )
    private val threadTimePattern = Regex(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+([VDIWEAF])\\s"
    )

    fun apply(content: String, selectedCategory: Category): String {
        if (selectedCategory == Category.ALL) return content

        val records = parseRecords(content)
        val matching = records.filter { it.category == selectedCategory }
        if (matching.isEmpty()) {
            return "No ${selectedCategory.displayName().lowercase()} were found in the saved diagnostics."
        }

        return buildString {
            appendLine("Filtered category: ${selectedCategory.displayName()}")
            appendLine()
            matching.forEach { record ->
                append(record.text.trimEnd()).append("\n\n")
            }
        }
    }

    fun Category.displayName(): String = when (this) {
        Category.ALL -> "All logs"
        Category.ERRORS -> "Errors and crashes"
        Category.WARNINGS -> "Warnings"
        Category.LIFECYCLE -> "Launch and lifecycle"
        Category.DEVICE -> "Device and app info"
        Category.LOGCAT -> "Regular logcat"
    }

    private fun parseRecords(content: String): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        var currentCategory = Category.DEVICE
        var currentText = StringBuilder()

        fun finishRecord() {
            if (currentText.isNotEmpty()) {
                records += LogRecord(currentCategory, currentText.toString())
                currentText = StringBuilder()
            }
        }

        content.lineSequence().forEach { line ->
            val detectedCategory = categoryAtRecordStart(line)
            if (detectedCategory != null) {
                finishRecord()
                currentCategory = detectedCategory
            }
            currentText.appendLine(line)
        }
        finishRecord()
        return records
    }

    private fun categoryAtRecordStart(line: String): Category? {
        if (line.startsWith("===== UNCAUGHT JAVA CRASH")) return Category.ERRORS
        if (line.startsWith("===== BLACKBOX APP DIAGNOSTIC SESSION")) return Category.DEVICE
        if (line.startsWith("===== ") && line.endsWith(" =====")) return Category.DEVICE

        val appEvent = appEventPattern.find(line)?.groupValues?.get(1)
        when (appEvent) {
            "ERROR" -> return Category.ERRORS
            "LIFECYCLE", "LAUNCHER" -> return Category.LIFECYCLE
            "LOGGER" -> return if (containsErrorMarker(line)) Category.ERRORS else Category.LOGCAT
        }

        val priority = threadTimePattern.find(line)?.groupValues?.get(1)
        return when (priority) {
            "E", "A", "F" -> Category.ERRORS
            "W" -> Category.WARNINGS
            "V", "D", "I" -> Category.LOGCAT
            else -> when {
                containsErrorMarker(line) -> Category.ERRORS
                containsWarningMarker(line) -> Category.WARNINGS
                else -> null
            }
        }
    }

    private fun containsErrorMarker(line: String): Boolean {
        return line.contains("FATAL EXCEPTION", ignoreCase = true) ||
            line.contains("Fatal signal", ignoreCase = true) ||
            line.contains("AndroidRuntime", ignoreCase = true) ||
            line.contains("Exception:", ignoreCase = true) ||
            line.contains(" ERROR ")
    }

    private fun containsWarningMarker(line: String): Boolean {
        return line.contains(" WARNING ") || line.startsWith("W/")
    }

    private data class LogRecord(val category: Category, val text: String)
}
