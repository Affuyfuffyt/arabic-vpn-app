package com.arabicvpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.arabicvpn.app.databinding.ActivityMainBinding

/**
 * النشاط الرئيسي — واجهة التطبيق العربية بالكامل (RTL).
 *
 * المكوّنات:
 *  - شريط الحالة: يعرض حالة الاتصال (متصل/لا/جاي يحاول) والخادم.
 *  - أزرار: استيراد إعداد، اتصال، قطع.
 *  - سجل الأخطاء: قائمة RecyclerView مع نسخ ومسح.
 *
 * يربط [VpnConnectionManager] و [ConnectionLog] بالواجهة.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vpnManager: VpnConnectionManager
    private val connectionLog = ConnectionLog()
    private lateinit var logAdapter: LogAdapter

    // الإعداد المُستورد الحالي (جاهز للاتصال)
    private var importedConfig: ConfigImporter.ParsedVpnConfig? = null

    // منتقي الملفات (Storage Access Framework)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedFile(uri)
        } else {
            connectionLog.warning(
                "FILE_PICKER_CANCELLED",
                "أثناء اختيار ملف الإعداد",
                "ألغى المستخدم اختيار الملف",
                "ما تم اختيار أي ملف. اضغط استيراد إعداد لاختيار ملف JSON"
            )
        }
    }

    // ───────────────── مستمع حالة الاتصال ─────────────────

    private val stateListener = object : VpnConnectionManager.StateListener {
        override fun onStateChanged(info: VpnConnectionManager.ConnectionInfo) {
            runOnUiThread { updateStatusUI(info) }
        }
    }

    // ───────────────── مستمع السجل ─────────────────

    private val logListener = object : ConnectionLog.LogListener {
        override fun onLogChanged(entries: List<ConnectionLog.LogEntry>) {
            runOnUiThread {
                logAdapter.update(entries)
                // التمرير لآخر سجل
                binding.rvLog.scrollToPosition(entries.size - 1)
                // تحديث عدّاد السجلات
                binding.tvLogCount.text = "عدد الأحداث: ${entries.size}"
            }
        }
    }

    // ───────────────── دورة الحياة ─────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // إجبار اتجاه RTL
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // تهيئة المدير
        vpnManager = VpnConnectionManager(this, connectionLog)

        // تهيئة قائمة السجل
        logAdapter = LogAdapter()
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter

        // ربط المستمعين
        connectionLog.addListener(logListener)
        vpnManager.addStateListener(stateListener)

        // ربط الأزرار
        binding.btnImport.setOnClickListener { openFilePicker() }
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { vpnManager.disconnect() }
        binding.btnCopyLog.setOnClickListener { copyLog() }
        binding.btnClearLog.setOnClickListener { connectionLog.clear() }

        // سجل بدء التشغيل
        connectionLog.info(
            "APP_STARTED",
            "أثناء تشغيل التطبيق",
            "تم تشغيل تطبيق VPN العربي",
            "التطبيق جاهز. استورد إعداد JSON ثم اضغط اتصال"
        )

        // طلب صلاحية الإشعارات (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
        }
    }

    override fun onStart() {
        super.onStart()
        vpnManager.start()
    }

    override fun onStop() {
        super.onStop()
        vpnManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionLog.removeListener(logListener)
        vpnManager.removeStateListener(stateListener)
    }

    // ───────────────── استيراد الملف ─────────────────

    private fun openFilePicker() {
        connectionLog.info(
            "FILE_PICKER_OPEN",
            "أثناء فتح منتقي الملفات",
            "جاري فتح منتقي الملفات لاختيار إعداد JSON",
            "اختر ملف بصيغة .json يحتوي على إعداد OpenVPN"
        )
        try {
            filePickerLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        } catch (e: Exception) {
            connectionLog.error(
                "FILE_PICKER_ERROR",
                "أثناء فتح منتقي الملفات",
                "فشل فتح المنتقي: ${e.message}",
                "نوع الخطأ: ${e.javaClass.simpleName}. قد يكون ما فيه تطبيق لإدارة الملفات"
            )
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "ملف غير معروف"
        connectionLog.info(
            "FILE_SELECTED",
            "بعد اختيار الملف",
            "تم اختيار الملف: $fileName",
            "جاري قراءة وتحليل محتوى الملف"
        )

        val importer = ConfigImporter()
        val result = importer.importFromUri(this, uri, fileName)

        when (result) {
            is ConfigImporter.ImportResult.Success -> {
                if (result.configs.isEmpty()) {
                    connectionLog.error(
                        "NO_CONFIGS_FOUND",
                        "بعد تحليل الملف \"$fileName\"",
                        "ما لقيت أي إعداد صالح في الملف",
                        "تأكد إن الملف يحتوي على إعداد VPN واحد على الأقل بصيغة JSON"
                    )
                    return
                }
                // نأخذ أول إعداد (أو نعرض القائمة لاحقًا)
                val config = result.configs[0]
                importedConfig = config

                connectionLog.success(
                    "CONFIG_PARSED",
                    "بعد تحليل الملف \"$fileName\"",
                    "تم تحليل الإعداد بنجاح: \"${config.name}\"",
                    "الخادم: ${config.server}:${config.port} (${config.protocol}). اضغط اتصال للبدء"
                )

                // عرض معلومات الإعداد في الواجهة
                binding.tvConfigName.text = "الإعداد: ${config.name}"
                binding.tvConfigServer.text = "الخادم: ${config.server}:${config.port} (${config.protocol})"
                binding.tvConfigName.visibility = View.VISIBLE
                binding.tvConfigServer.visibility = View.VISIBLE

                // إذا فيه أكثر من إعداد، نسجّل تنبيه
                if (result.configs.size > 1) {
                    connectionLog.warning(
                        "MULTIPLE_CONFIGS",
                        "بعد تحليل الملف \"$fileName\"",
                        "الملف يحتوي على ${result.configs.size} إعدادات",
                        "تم اختيار أول إعداد فقط: \"${config.name}\". الإعدادات الباقية متوفرة لكن مو مستخدمة"
                    )
                }
            }
            is ConfigImporter.ImportResult.Failure -> {
                connectionLog.error(
                    result.errorCode,
                    result.where,
                    result.why,
                    result.cause
                )
                Toast.makeText(this, "فشل استيراد الملف: ${result.why}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────────────── الاتصال ─────────────────

    private fun onConnectClicked() {
        val config = importedConfig
        if (config == null) {
            connectionLog.warning(
                "NO_CONFIG_LOADED",
                "أثناء الضغط على اتصال",
                "ما فيه إعداد محمّل للاتصال",
                "استورد ملف JSON أولًا، ثم اضغط اتصال"
            )
            Toast.makeText(this, "استورد إعداد JSON أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        vpnManager.connect(config)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VpnConnectionManager.REQUEST_VPN_PERMISSION) {
            vpnManager.onVpnPermissionResult(resultCode)
        }
    }

    // ───────────────── تحديث الواجهة ─────────────────

    private fun updateStatusUI(info: VpnConnectionManager.ConnectionInfo) {
        // الحالة
        binding.tvStatusValue.text = info.state.arabicLabel()

        // الخادم / وين وصل
        binding.tvServerValue.text = when {
            info.serverAddress != null -> info.serverAddress
            info.profileName != null -> info.profileName
            else -> "—"
        }

        // الرسالة / جاي يحاول
        binding.tvStatusMessage.text = info.statusMessage ?: ""

        // لون الحالة
        val colorRes = when (info.state) {
            VpnConnectionManager.ConnectionState.CONNECTED -> R.color.status_connected
            VpnConnectionManager.ConnectionState.CONNECTING -> R.color.status_connecting
            VpnConnectionManager.ConnectionState.ERROR -> R.color.status_error
            VpnConnectionManager.ConnectionState.DISCONNECTING -> R.color.status_connecting
            VpnConnectionManager.ConnectionState.IDLE -> R.color.status_idle
        }
        binding.tvStatusValue.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorRes))

        // تفعيل/تعطيل الأزرار حسب الحالة
        val isIdle = info.state == VpnConnectionManager.ConnectionState.IDLE
        val isError = info.state == VpnConnectionManager.ConnectionState.ERROR
        val isConnected = info.state == VpnConnectionManager.ConnectionState.CONNECTED
        val isConnecting = info.state == VpnConnectionManager.ConnectionState.CONNECTING ||
                info.state == VpnConnectionManager.ConnectionState.DISCONNECTING

        binding.btnConnect.isEnabled = isIdle || isError
        binding.btnDisconnect.isEnabled = isConnected || isConnecting
        binding.btnImport.isEnabled = isIdle || isError
    }

    // ───────────────── نسخ السجل ─────────────────

    private fun copyLog() {
        val text = connectionLog.copyAll()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("سجل VPN", text))
        Toast.makeText(this, "تم نسخ السجل (${connectionLog.size()} حدث)", Toast.LENGTH_SHORT).show()
        connectionLog.info(
            "LOG_COPIED",
            "أثناء نسخ السجل",
            "تم نسخ ${connectionLog.size()} حدث إلى الحافظة",
            "تقدر تلصق السجل في أي تطبيق (مثل واتساب أو ملاحظات)"
        )
    }
}
