package top.niunaijun.blackboxa.view.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityAppLogsBinding
import top.niunaijun.blackboxa.diagnostics.AppDiagnostics
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity

class AppLogsActivity : BaseActivity() {
    private val binding: ActivityAppLogsBinding by inflate()
    private lateinit var packageName: String
    private var userId: Int = 0
    private var displayedLogs: String = ""

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
        binding.toolbar.inflateMenu(R.menu.menu_app_logs)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.copy_logs) {
                copyLogs()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            displayedLogs = withContext(Dispatchers.IO) {
                AppDiagnostics.readLogs(packageName, userId)
            }
            binding.logs.text = displayedLogs
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("$packageName diagnostics", displayedLogs))
        toast(R.string.logs_copied)
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
