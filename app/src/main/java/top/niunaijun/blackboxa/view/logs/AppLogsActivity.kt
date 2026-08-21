package top.niunaijun.blackboxa.view.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityAppLogsBinding
import top.niunaijun.blackboxa.diagnostics.AppDiagnostics
import top.niunaijun.blackboxa.diagnostics.DiagnosticLogFilter
import top.niunaijun.blackboxa.diagnostics.DiagnosticLogFilter.displayName
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLogsActivity : BaseActivity() {
    private val binding: ActivityAppLogsBinding by inflate()
    private lateinit var packageName: String
    private var userId: Int = 0
    private var rawLogs: String = ""
    private var displayedLogs: String = ""
    private var selectedCategory = DiagnosticLogFilter.Category.ALL
    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(displayedLogs)
                    } ?: error("Unable to open selected file")
                }.isSuccess
            }
            toast(if (saved) R.string.logs_saved else R.string.logs_save_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        userId = intent.getIntExtra(EXTRA_USER_ID, 0)
        if (packageName.isBlank()) {
            finish()
            return
        }

        setContentView(binding.root)
        initToolbar(binding.toolbar, R.string.app_logs, true)
        binding.toolbar.subtitle = packageName
        setupCategoryFilter()
        binding.copyLogs.setOnClickListener { copyLogs() }
        binding.saveLogs.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val category = selectedCategory.name.lowercase(Locale.US)
            createLogDocument.launch("BlackBox_${AppDiagnostics.safeFileKey(packageName)}_${category}_$timestamp.txt")
        }
    }

    private fun setupCategoryFilter() {
        val categories = DiagnosticLogFilter.Category.entries
        binding.categoryFilter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories.map { it.displayName() }
        )
        binding.categoryFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCategory = categories[position]
                applyFilter()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            rawLogs = withContext(Dispatchers.IO) {
                AppDiagnostics.readLogs(packageName, userId)
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        displayedLogs = DiagnosticLogFilter.apply(rawLogs, selectedCategory)
        binding.logs.text = displayedLogs
    }

    private fun copyLogs() {
        val copied = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("$packageName diagnostics", displayedLogs))
        }.isSuccess
        toast(if (copied) R.string.logs_copied else R.string.logs_copy_failed)
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_USER_ID = "user_id"

        fun start(context: Context, packageName: String, userId: Int) {
            context.startActivity(Intent(context, AppLogsActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_USER_ID, userId)
            })
        }
    }
}
