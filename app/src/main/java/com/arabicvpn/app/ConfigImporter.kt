package com.arabicvpn.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

/**
 * مستورد ومحلّل إعدادات VPN بصيغة JSON.
 *
 * يدعم نوعين من ملفات JSON:
 *
 *  1) إعدادات OpenVPN بصيغة JSON المباشرة (الحقول المعروفة):
 *     {
 *       "name": "الخادم الرئيسي",
 *       "server": "vpn.example.com",
 *       "port": 1194,
 *       "protocol": "udp",            // "udp" أو "tcp"
 *       "username": "user",
 *       "password": "pass",
 *       "ovpn_config": "client\ndev tun\nremote vpn.example.com 1194 udp\n..."
 *     }
 *
 *  2) إعدادات OpenVPN بصيغة .ovpn مضمّنة داخل حقل "ovpn_config" أو "config".
 *
 * كما يدعم ملفات JSON تحتوي مصفوفة إعدادات: [ {…}, {…} ]
 *
 * الناتج: كائن [ParsedVpnConfig] يحتوي على الاسم، الخادم، المنفذ، البروتوكول،
 * وملف .ovpn جاهز للتحويل إلى VpnProfile عبر ConfigParser الخاص بـ ics-openvpn.
 */
class ConfigImporter {

    companion object {
        private const val TAG = "ConfigImporter"
    }

    /** نتيجة تحليل ملف الإعداد. */
    data class ParsedVpnConfig(
        val name: String,
        val server: String,
        val port: Int,
        val protocol: String,        // "udp" أو "tcp"
        val username: String?,
        val password: String?,
        val ovpnConfig: String,      // محتوى .ovpn الكامل
        val sourceDescription: String
    )

    /** نتيجة الاستيراد: نجاح أو فشل مع رسالة خطأ عربية مفصّلة. */
    sealed class ImportResult {
        data class Success(val configs: List<ParsedVpnConfig>) : ImportResult()
        data class Failure(
            val errorCode: String,
            val where: String,        // وين الخطأ
            val why: String,          // ليش صار
            val cause: String         // شنو السبب
        ) : ImportResult()
    }

    /**
     * يحلّل محتوى ملف JSON نصيًا ويعيد قائمة بالإعدادات.
     *
     * @param jsonText النص الكامل للملف.
     * @param fileName اسم الملف (للتشخيص في رسائل الخطأ).
     */
    fun parseJsonConfig(jsonText: String, fileName: String = "غير معروف"): ImportResult {
        return try {
            val trimmed = jsonText.trim()
            if (trimmed.isEmpty()) {
                return ImportResult.Failure(
                    errorCode = "EMPTY_FILE",
                    where = "أثناء قراءة الملف \"$fileName\"",
                    why = "الملف فاضي وما فيه أي محتوى",
                    cause = "تأكد إن الملف اللي اخترته يحتوي على إعداد VPN صالح بصيغة JSON"
                )
            }

            val configs = mutableListOf<ParsedVpnConfig>()

            // محاولة قراءة كائن واحد أو مصفوفة
            when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) {
                        return ImportResult.Failure(
                            errorCode = "EMPTY_ARRAY",
                            where = "أثناء تحليل المصفوفة في الملف \"$fileName\"",
                            why = "المصفوفة فاضية ما فيها أي إعداد",
                            cause = "أضف عنصر إعداد واحد على الأقل داخل المصفوفة [ ... ]"
                        )
                    }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val parsed = parseSingleObject(obj, "$fileName [عنصر ${i + 1}]")
                        configs.add(parsed)
                    }
                }
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    configs.add(parseSingleObject(obj, fileName))
                }
                else -> {
                    return ImportResult.Failure(
                        errorCode = "INVALID_JSON_FORMAT",
                        where = "أثناء التحقق من بداية الملف \"$fileName\"",
                        why = "الملف ما يبدأ بـ { أو [ فمو صيغة JSON صحيحة",
                        cause = "تأكد إن الملف بصيغة JSON صحيحة ويبدأ بكائن { أو مصفوفة ["
                    )
                }
            }

            ImportResult.Success(configs)

        } catch (e: org.json.JSONException) {
            Log.e(TAG, "JSON parse error", e)
            ImportResult.Failure(
                errorCode = "JSON_PARSE_ERROR",
                where = "أثناء تحليل JSON في الملف \"$fileName\"",
                why = "صيغة JSON غير صحيحة: ${e.message}",
                cause = "راجع الملف وتأكد إن الأقواس والفواصل صحيحة، أو إن الملف مو تالف"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            ImportResult.Failure(
                errorCode = "UNEXPECTED_ERROR",
                where = "أثناء معالجة الملف \"$fileName\"",
                why = "خطأ غير متوقع: ${e.message}",
                cause = "نوع الخطأ: ${e.javaClass.simpleName}. حاول مرة ثانية أو استخدم ملف مختلف"
            )
        }
    }

    /**
     * يحلّل كائن JSON واحد إلى [ParsedVpnConfig].
     */
    private fun parseSingleObject(obj: JSONObject, source: String): ParsedVpnConfig {
        // الاسم: نحاول عدة مفاتيح شائعة
        val name = obj.optString("name",
            obj.optString("profile_name",
                obj.optString("title",
                    obj.optString("server", "إعداد VPN"))))

        // الخادم
        val server = obj.optString("server",
            obj.optString("host",
                obj.optString("remote",
                    obj.optString("server_address", "غير محدد"))))

        // المنفذ
        val port = obj.optInt("port",
            obj.optInt("server_port", 1194))

        // البروتوكول
        val protocol = obj.optString("protocol",
            obj.optString("proto", "udp")).lowercase()

        // بيانات الدخول (اختيارية)
        val username = if (obj.has("username")) obj.getString("username") else null
        val password = if (obj.has("password")) obj.getString("password") else null

        // محتوى .ovpn — قد يكون مضمّنًا مباشرة
        val ovpnConfig = when {
            obj.has("ovpn_config") -> obj.getString("ovpn_config")
            obj.has("config") -> obj.getString("config")
            obj.has("ovpn") -> obj.getString("ovpn")
            obj.has("content") -> obj.getString("content")
            else -> buildOvpnFromFields(name, server, port, protocol, username, password)
        }

        return ParsedVpnConfig(
            name = name,
            server = server,
            port = port,
            protocol = if (protocol == "tcp") "tcp" else "udp",
            username = username,
            password = password,
            ovpnConfig = ovpnConfig,
            sourceDescription = source
        )
    }

    /**
     * يبني محتوى .ovpn أساسيًا من الحقول المتفرقة إذا ما كان فيه حقل ovpn_config.
     * هذا يضمن إنه دائمًا في ملف .ovpn صالح للتحويل عبر ConfigParser.
     */
    private fun buildOvpnFromFields(
        name: String, server: String, port: Int, protocol: String,
        username: String?, password: String?
    ): String {
        val sb = StringBuilder()
        sb.append("client\n")
        sb.append("dev tun\n")
        sb.append("proto $protocol\n")
        sb.append("remote $server $port $protocol\n")
        sb.append("resolv-retry infinite\n")
        sb.append("nobind\n")
        sb.append("persist-key\n")
        sb.append("persist-tun\n")
        sb.append("cipher AES-256-CBC\n")
        sb.append("auth SHA256\n")
        sb.append("verb 3\n")
        if (username != null && password != null) {
            sb.append("auth-user-pass\n")
        }
        return sb.toString()
    }

    /**
     * يقرأ ملفًا من [contentResolver] ويحلّله.
     * يُستخدم مع نتيجة منتقي الملفات (Storage Access Framework).
     */
    fun importFromUri(context: Context, uri: android.net.Uri, fileName: String): ImportResult {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return ImportResult.Failure(
                    errorCode = "CANNOT_READ_URI",
                    where = "أثناء فتح الملف \"$fileName\" من النظام",
                    why = "ما قدرت أقرأ محتوى الملف",
                    cause = "تأكد إن الملف موجود وما تم حذفه، وأن التطبيق عنده صلاحية الوصول إليه"
                )
            parseJsonConfig(text, fileName)
        } catch (e: SecurityException) {
            ImportResult.Failure(
                errorCode = "PERMISSION_DENIED",
                where = "أثناء محاولة الوصول للملف \"$fileName\"",
                why = "التطبيق ما عنده صلاحية لقراءة هذا الملف",
                cause = "اختر الملف من جديد عبر منتقي الملفات لمنح صلاحية الوصول"
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                errorCode = "READ_ERROR",
                where = "أثناء قراءة الملف \"$fileName\"",
                why = "خطأ في قراءة الملف: ${e.message}",
                cause = "نوع الخطأ: ${e.javaClass.simpleName}. قد يكون الملف تالفًا أو كبيرًا جدًا"
            )
        }
    }
}
