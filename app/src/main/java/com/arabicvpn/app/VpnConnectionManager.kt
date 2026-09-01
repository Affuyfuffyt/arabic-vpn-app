package com.arabicvpn.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * مدير اتصال VPN — يربط التطبيق بمحرك ics-openvpn.
 *
 * المسؤوليات:
 *  1) تحويل إعداد JSON المُحلَّل إلى [VpnProfile] عبر [ConfigParser].
 *  2) حفظ البروفايل عبر [ProfileManager].
 *  3) بدء الاتصال عبر [VPNLaunchHelper.startOpenVpn].
 *  4) قطع الاتصال عبر [IOpenVPNServiceInternal.stopVPN].
 *  5) الاستماع لحالة الاتصال في الوقت الحقيقي عبر [VpnStatus.StateListener]
 *     وتحويلها إلى آلة الحالات: IDLE → CONNECTING → CONNECTED / ERROR.
 *  6) تسجيل كل خطوة في [ConnectionLog] بشرح عربي مفصّل.
 *
 * دورة الحياة:
 *  - [start] يربط المستمعين ويهيّئ ProfileManager.
 *  - [stop] يفك الربط وينظّف.
 */
class VpnConnectionManager(
    private val context: Context,
    private val log: ConnectionLog
) {

    companion object {
        private const val TAG = "VpnConnectionManager"
        const val REQUEST_VPN_PERMISSION = 1001
    }

    /** حالة الاتصال (آلة الحالات). */
    enum class ConnectionState {
        IDLE,           // خامل — ما فيه اتصال
        CONNECTING,     // جاي يحاول يتصل
        CONNECTED,      // متصل
        DISCONNECTING,  // جاي يقطع
        ERROR;          // خطأ

        fun arabicLabel(): String = when (this) {
            IDLE -> "غير متصل"
            CONNECTING -> "جاري الاتصال..."
            CONNECTED -> "متصل"
            DISCONNECTING -> "جاري القطع..."
            ERROR -> "خطأ في الاتصال"
        }
    }

    /** معلومات الاتصال الحالية. */
    data class ConnectionInfo(
        val state: ConnectionState,
        val profileName: String?,
        val serverAddress: String?,
        val statusMessage: String?
    )

    /** مستمع لتغيّر حالة الاتصال. */
    interface StateListener {
        fun onStateChanged(info: ConnectionInfo)
    }

    // ───────────────── الحالة الداخلية ─────────────────

    private var currentState: ConnectionState = ConnectionState.IDLE
    private var currentProfile: VpnProfile? = null
    private var currentProfileName: String? = null
    private var currentServerAddress: String? = null
    private var currentStatusMessage: String? = null
    private var pendingConfig: ConfigImporter.ParsedVpnConfig? = null

    private val stateListeners = mutableListOf<StateListener>()
    private var serviceInternal: IOpenVPNServiceInternal? = null
    private var bound = false

    // ───────────────── مستمع حالة ics-openvpn ─────────────────

    private val vpnStatusListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?
        ) {
            handleStatusUpdate(state, logmessage, level)
        }

        override fun setConnectedVPN(uuid: String?) {
            // يُستدعى عند تعيين البروفايل المتصل
        }
    }

    // ───────────────── مستمع سجل ics-openvpn ─────────────────

    private val vpnLogListener = object : VpnStatus.LogListener {
        override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
            logItem ?: return
            try {
                val msg = logItem.getString(context)
                // نمرّر سجلات محرك OpenVPN إلى سجلنا العربي
                val level = logItem.mLevel ?: VpnStatus.LogLevel.INFO
                when (level) {
                    VpnStatus.LogLevel.ERROR -> log.error(
                        "OVPN_LOG",
                        "من محرك OpenVPN",
                        msg ?: "رسالة خطأ من المحرك",
                        "هذا سجل من المحرك نفسه، راجعه لمعرفة تفاصيل الخطأ"
                    )
                    VpnStatus.LogLevel.WARNING -> log.warning(
                        "OVPN_LOG",
                        "من محرك OpenVPN",
                        msg ?: "رسالة تحذير من المحرك",
                        "تحذير من المحرك — عادةً مو خطير لكن يستاهل الانتباه"
                    )
                    else -> log.info(
                        "OVPN_LOG",
                        "من محرك OpenVPN",
                        msg ?: "رسالة من المحرك",
                        "معلومة تشغيلية من المحرك"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to forward log item", e)
            }
        }
    }

    // ───────────────── ربط الخدمة ─────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceInternal = IOpenVPNServiceInternal.Stub.asInterface(service)
            bound = true
            log.info(
                "SERVICE_BOUND",
                "أثناء ربط خدمة OpenVPN",
                "تم ربط خدمة OpenVPN بنجاح",
                "الخدمة جاهزة لاستقبال أوامر الاتصال والقطع"
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceInternal = null
            bound = false
            log.warning(
                "SERVICE_DISCONNECTED",
                "أثناء ربط خدمة OpenVPN",
                "انقطع الاتصال بخدمة OpenVPN",
                "قد يكون النظام أوقف الخدمة — سيتم إعادة الربط عند الحاجة"
            )
        }
    }

    // ───────────────── دورة الحياة ─────────────────

    /** يبدأ المدير: يربط المستمعين والخدمة. يُستدعى من onStart. */
    fun start() {
        try {
            // تهيئة ProfileManager
            ProfileManager.getInstance(context)
            // تسجيل مستمعي الحالة والسجل
            VpnStatus.addStateListener(vpnStatusListener)
            VpnStatus.addLogListener(vpnLogListener)
            // ربط الخدمة
            bindOpenVpnService()
            log.info(
                "MANAGER_STARTED",
                "أثناء تشغيل مدير VPN",
                "تم تشغيل مدير VPN وربطه بمحرك OpenVPN",
                "التطبيق جاهز لاستيراد الإعدادات والاتصال"
            )
        } catch (e: Exception) {
            log.error(
                "MANAGER_START_FAILED",
                "أثناء تشغيل مدير VPN",
                "فشل تشغيل المدير: ${e.message}",
                "نوع الخطأ: ${e.javaClass.simpleName}. تأكد إن مكتبة ics-openvpn مضمّنة بشكل صحيح"
            )
        }
    }

    /** يوقف المدير: يفك الربط. يُستدعى من onStop. */
    fun stop() {
        try {
            VpnStatus.removeStateListener(vpnStatusListener)
            VpnStatus.removeLogListener(vpnLogListener)
            unbindOpenVpnService()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping manager", e)
        }
    }

    private fun bindOpenVpnService() {
        val intent = Intent(context, OpenVPNService::class.java)
        intent.action = OpenVPNService.START_SERVICE
        try {
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            log.error(
                "BIND_FAILED",
                "أثناء ربط خدمة OpenVPN",
                "فشل ربط الخدمة: ${e.message}",
                "تأكد إن خدمة OpenVPNService معرّفة في AndroidManifest.xml"
            )
        }
    }

    private fun unbindOpenVpnService() {
        if (bound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
            bound = false
            serviceInternal = null
        }
    }

    // ───────────────── استيراد الإعداد ─────────────────

    /**
     * يحوّل إعداد JSON مُحلَّل إلى [VpnProfile] ويحفظه.
     * يعيد البروفايل جاهزًا للاتصال، أو null عند الفشل (مع تسجيل الخطأ).
     */
    fun importConfig(config: ConfigImporter.ParsedVpnConfig): VpnProfile? {
        return try {
            log.info(
                "IMPORT_START",
                "أثناء استيراد إعداد VPN",
                "جاري تحويل إعداد \"${config.name}\" إلى بروفايل OpenVPN",
                "الخادم: ${config.server}:${config.port} (${config.protocol})"
            )

            val parser = ConfigParser()
            parser.parseConfig(StringReader(config.ovpnConfig))
            val profile = parser.convertProfile()
            // تعيين الاسم من JSON
            profile.mName = config.name

            // حفظ بيانات الدخول إذا وُجدت
            if (!config.username.isNullOrEmpty()) {
                profile.mUsername = config.username
            }
            if (!config.password.isNullOrEmpty()) {
                profile.mPassword = config.password
            }

            // حفظ البروفايل
            ProfileManager.saveProfile(context, profile)

            log.success(
                "IMPORT_SUCCESS",
                "أثناء استيراد إعداد VPN",
                "تم استيراد إعداد \"${config.name}\" بنجاح",
                "البروفايل محفوظ وجاهز للاتصال. UUID: ${profile.uuid}"
            )
            profile

        } catch (e: ConfigParser.ConfigParseError) {
            log.error(
                "CONFIG_PARSE_ERROR",
                "أثناء تحليل إعداد OpenVPN لإعداد \"${config.name}\"",
                "خطأ في تحليل الإعداد: ${e.message}",
                "الإعداد قد يكون غير مكتمل أو يحتوي على أوامر غير مدعومة. تأكد إن ملف .ovpn المضمّن صالح"
            )
            null
        } catch (e: Exception) {
            log.error(
                "IMPORT_FAILED",
                "أثناء استيراد إعداد \"${config.name}\"",
                "فشل الاستيراد: ${e.message}",
                "نوع الخطأ: ${e.javaClass.simpleName}. راجع محتوى الإعداد وحاول مرة ثانية"
            )
            null
        }
    }

    // ───────────────── الاتصال ─────────────────

    /**
     * يبدأ الاتصال بإعداد مُحلَّل.
     * يتطلب صلاحية VPN — إذا ما عندها، يطلبها عبر [VpnService.prepare].
     *
     * @return true إذا بدأ الاتصال أو طُلبت الصلاحية، false إذا فشل.
     */
    fun connect(config: ConfigImporter.ParsedVpnConfig): Boolean {
        pendingConfig = config
        currentProfileName = config.name
        currentServerAddress = "${config.server}:${config.port}"

        // 1) استيراد الإعداد إلى VpnProfile
        val profile = importConfig(config)
        if (profile == null) {
            setState(ConnectionState.ERROR, "فشل استيراد الإعداد قبل الاتصال")
            return false
        }
        currentProfile = profile

        // 2) التحقق من صلاحية VPN
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            // المستخدم لازم يوافق على صلاحية VPN — النشاط يبدأ الـ intent
            log.warning(
                "VPN_PERMISSION_NEEDED",
                "أثناء طلب صلاحية VPN",
                "النظام يطلب موافقة المستخدم على اتصال VPN",
                "سيظهر مربع حوار للطلب منك الموافقة. بعد الموافقة يكمل الاتصال"
            )
            (context as? MainActivity)?.startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
            return true
        }

        // 3) الصلاحية موجودة — ابدأ الاتصال مباشرة
        return startVpnConnection(profile)
    }

    /**
     * يبدأ اتصال VPN فعليًا بعد التأكد من الصلاحية.
     * يُستدعى من MainActivity.onActivityResult بعد موافقة المستخدم.
     */
    fun onVpnPermissionResult(resultCode: Int): Boolean {
        val profile = currentProfile ?: return false
        if (resultCode == android.app.Activity.RESULT_OK) {
            log.info(
                "VPN_PERMISSION_GRANTED",
                "بعد موافقة المستخدم على صلاحية VPN",
                "وافق المستخدم على صلاحية VPN",
                "جاري بدء الاتصال الآن"
            )
            return startVpnConnection(profile)
        } else {
            log.error(
                "VPN_PERMISSION_DENIED",
                "بعد رفض المستخدم لصلاحية VPN",
                "رفض المستخدم منح صلاحية VPN",
                "بدون هذه الصلاحية ما يصير يتصل. اضغط اتصال مرة ثانية ووافق على الطلب"
            )
            setState(ConnectionState.ERROR, "تم رفض صلاحية VPN")
            return false
        }
    }

    /**
     * يبدأ اتصال OpenVPN عبر [VPNLaunchHelper].
     */
    private fun startVpnConnection(profile: VpnProfile): Boolean {
        return try {
            setState(ConnectionState.CONNECTING, "جاري الاتصال بـ \"${profile.mName}\"")

            log.state(
                "CONNECTING",
                "بدء الاتصال بـ \"${profile.mName}\"",
                "جاري تشغيل نفق OpenVPN",
                "الخادم: ${currentServerAddress ?: "غير محدد"}. الحالة ستتحدّث تلقائيًا"
            )

            VPNLaunchHelper.startOpenVpn(profile, context, "user_request", true)
            true
        } catch (e: Exception) {
            log.error(
                "CONNECT_START_FAILED",
                "أثناء بدء اتصال OpenVPN لإعداد \"${profile.mName}\"",
                "فشل بدء الاتصال: ${e.message}",
                "نوع الخطأ: ${e.javaClass.simpleName}. تأكد إن خدمة OpenVPNService تعمل"
            )
            setState(ConnectionState.ERROR, "فشل بدء الاتصال: ${e.message}")
            false
        }
    }

    // ───────────────── القطع ─────────────────

    /**
     * يقطع اتصال VPN الحالي.
     */
    fun disconnect() {
        if (currentState == ConnectionState.IDLE) {
            log.info(
                "DISCONNECT_IDLE",
                "أثناء محاولة القطع",
                "ما فيه اتصال نشط لقطعه",
                "التطبيق في حالة خمول — ما يحتاج قطع"
            )
            return
        }
        setState(ConnectionState.DISCONNECTING, "جاري قطع الاتصال")
        log.state(
            "DISCONNECTING",
            "أثناء قطع الاتصال",
            "جاري إيقاف نفق OpenVPN",
            "سيتم تحديث الحالة إلى غير متصل بعد الإيقاف"
        )
        try {
            // محاولة عبر الخدمة المربوطة
            if (serviceInternal != null) {
                serviceInternal?.stopVPN(false)
            } else {
                // إذا ما كانت الخدمة مربوطة، أعد الربط ثم أوقف
                bindOpenVpnService()
                serviceInternal?.stopVPN(false)
            }
        } catch (e: RemoteException) {
            log.error(
                "DISCONNECT_FAILED",
                "أثناء قطع الاتصال",
                "فشل إرسال أمر القطع: ${e.message}",
                "الخدمة قد تكون متوقفة. الحالة ستتحدّث تلقائيًا"
            )
        } catch (e: Exception) {
            log.error(
                "DISCONNECT_FAILED",
                "أثناء قطع الاتصال",
                "خطأ غير متوقع: ${e.message}",
                "نوع الخطأ: ${e.javaClass.simpleName}"
            )
        }
    }

    // ───────────────── معالجة تحديثات الحالة ─────────────────

    /**
     * يحوّل حالة ics-openvpn (ConnectionStatus) إلى حالة التطبيق.
     */
    private fun handleStatusUpdate(state: String?, logmessage: String?, level: ConnectionStatus?) {
        val msg = logmessage ?: ""
        val stateStr = state ?: "UNKNOWN"

        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                setState(ConnectionState.CONNECTED, "متصل بـ \"${currentProfileName ?: "الخادم"}\"")
                log.success(
                    "CONNECTED",
                    "بعد نجاح الاتصال",
                    "تم الاتصال بنجاح بـ \"${currentProfileName ?: "الخادم"}\"",
                    "الخادم: ${currentServerAddress ?: "غير محدد"}. النفق نشط والبيانات تمر الآن"
                )
            }
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_START -> {
                if (currentState != ConnectionState.CONNECTED) {
                    setState(ConnectionState.CONNECTING, "جاري الاتصال — $msg")
                }
                log.state(
                    stateStr,
                    "أثناء محاولة الاتصال",
                    msg.ifEmpty { "المحرك في مرحلة الاتصال: $stateStr" },
                    "الحالة: ${level?.name ?: stateStr}. لسه يحاول يتصل بالخادم"
                )
            }
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                setState(ConnectionState.ERROR, "فشل المصادقة — $msg")
                log.error(
                    "AUTH_FAILED",
                    "أثناء مصادقة الاتصال بـ \"${currentProfileName ?: "الخادم"}\"",
                    "فشلت المصادقة مع الخادم: $msg",
                    "الأسباب الشائعة: اسم مستخدم/كلمة مرور خطأ، شهادة منتهية، أو الخادم رفض الاتصال. تحقق من بيانات الدخول"
                )
            }
            ConnectionStatus.LEVEL_NONETWORK -> {
                setState(ConnectionState.ERROR, "ما فيه شبكة — $msg")
                log.error(
                    "NO_NETWORK",
                    "أثناء فحص الشبكة",
                    "ما فيه اتصال إنترنت متاح: $msg",
                    "تأكد إنك متصل بالواي فاي أو بيانات الجوال، ثم حاول مرة ثانية"
                )
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
                log.warning(
                    "VPN_PAUSED",
                    "أثناء تشغيل VPN",
                    "تم إيقاف VPN مؤقتًا: $msg",
                    "قد يكون بسبب توقف الشاشة أو إيقاف المستخدم. سيتابع تلقائيًا عند توفر الشبكة"
                )
            }
            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (currentState == ConnectionState.DISCONNECTING) {
                    log.success(
                        "DISCONNECTED",
                        "بعد قطع الاتصال",
                        "تم قطع الاتصال بنجاح",
                        "النفق مغلق والحالة رجعت إلى غير متصل"
                    )
                }
                setState(ConnectionState.IDLE, "غير متصل")
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                log.warning(
                    "WAITING_USER_INPUT",
                    "أثناء انتظار إدخال المستخدم",
                    "المحرك ينتظر إدخالًا منك: $msg",
                    "قد يطلب كلمة مرور أو استجابة تحدّي. تحقق من الإشعارات أو نافذة التطبيق"
                )
            }
            null -> {
                log.warning(
                    "UNKNOWN_STATE",
                    "أثناء استلام حالة غير معروفة",
                    "وصلت حالة غير معروفة: $stateStr",
                    "msg: $msg. قد تكون حالة مؤقتة من المحرك"
                )
            }
        }
    }

    // ───────────────── إدارة الحالة ─────────────────

    private fun setState(newState: ConnectionState, message: String) {
        if (currentState != newState) {
            currentState = newState
            currentStatusMessage = message
            notifyStateListeners()
        } else {
            currentStatusMessage = message
            notifyStateListeners()
        }
    }

    fun getCurrentState(): ConnectionState = currentState
    fun getCurrentInfo(): ConnectionInfo = ConnectionInfo(
        state = currentState,
        profileName = currentProfileName,
        serverAddress = currentServerAddress,
        statusMessage = currentStatusMessage
    )

    fun addStateListener(listener: StateListener) {
        stateListeners.add(listener)
        listener.onStateChanged(getCurrentInfo())
    }

    fun removeStateListener(listener: StateListener) {
        stateListeners.remove(listener)
    }

    private fun notifyStateListeners() {
        val info = getCurrentInfo()
        for (l in stateListeners) {
            try {
                l.onStateChanged(info)
            } catch (e: Exception) {
                Log.w(TAG, "State listener error", e)
            }
        }
    }
}
