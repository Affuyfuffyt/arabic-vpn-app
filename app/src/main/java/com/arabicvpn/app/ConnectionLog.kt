package com.arabicvpn.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * نظام السجل (Connection Log) — يسجّل كل أحداث الاتصال بالتفصيل.
 *
 * كل سجل يحتوي على:
 *  - الطابع الزمني (timestamp)
 *  - نوع الحدث: معلومات / خطأ / تحذير
 *  - وصف عربي مفصّل: وين صار، ليش، شنو السبب
 *
 * يدعم:
 *  - نسخ السجل كاملًا (copy)
 *  - مسح السجل (clear)
 *  - الاستماع للتغييرات في الوقت الحقيقي (Listener)
 */
class ConnectionLog {

    /** نوع الحدث. */
    enum class EventType(val label: String, val symbol: String) {
        INFO("معلومات", "ℹ"),
        WARNING("تحذير", "⚠"),
        ERROR("خطأ", "✖"),
        SUCCESS("نجاح", "✓"),
        STATE("حالة", "→");

        fun arabicLabel(): String = label
    }

    /** سجل واحد. */
    data class LogEntry(
        val timestamp: Long,
        val type: EventType,
        val errorCode: String,        // رمز الخطأ (مثل VPN_AUTH_FAILED) أو "INFO"
        val where: String,            // وين صار الحدث
        val why: String,              // ليش صار
        val cause: String             // شنو السبب / التفاصيل
    ) {
        /** نص السجل منسّق للعرض. */
        fun toDisplayString(): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            return buildString {
                append("[$time] ${type.symbol} ${type.label}\n")
                append("  الرمز: $errorCode\n")
                append("  وين: $where\n")
                append("  ليش: $why\n")
                append("  السبب: $cause")
            }
        }

        /** نص السجل للنسخ (أكثر تفصيلًا، يشمل التاريخ). */
        fun toCopyString(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            return buildString {
                append("═══════════════════════════════════════\n")
                append("الوقت: $time\n")
                append("النوع: ${type.label} (${type.symbol})\n")
                append("الرمز: $errorCode\n")
                append("وين صار: $where\n")
                append("ليش: $why\n")
                append("شنو السبب: $cause\n")
            }
        }
    }

    /** مستمع للتغييرات في السجل. */
    interface LogListener {
        fun onLogChanged(entries: List<LogEntry>)
    }

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<LogListener>()
    private val maxEntries = 500

    // ───────────────── إضافة السجلات ─────────────────

    fun info(errorCode: String, where: String, why: String, cause: String) {
        add(LogEntry(System.currentTimeMillis(), EventType.INFO, errorCode, where, why, cause))
    }

    fun warning(errorCode: String, where: String, why: String, cause: String) {
        add(LogEntry(System.currentTimeMillis(), EventType.WARNING, errorCode, where, why, cause))
    }

    fun error(errorCode: String, where: String, why: String, cause: String) {
        add(LogEntry(System.currentTimeMillis(), EventType.ERROR, errorCode, where, why, cause))
    }

    fun success(errorCode: String, where: String, why: String, cause: String) {
        add(LogEntry(System.currentTimeMillis(), EventType.SUCCESS, errorCode, where, why, cause))
    }

    fun state(errorCode: String, where: String, why: String, cause: String) {
        add(LogEntry(System.currentTimeMillis(), EventType.STATE, errorCode, where, why, cause))
    }

    private fun add(entry: LogEntry) {
        entries.add(entry)
        // الحفاظ على حد أقصى لعدد السجلات
        while (entries.size > maxEntries) {
            entries.removeAt(0)
        }
        notifyListeners()
    }

    // ───────────────── العمليات ─────────────────

    /** كل السجلات. */
    fun getAll(): List<LogEntry> = entries.toList()

    /** آخر سجل. */
    fun lastEntry(): LogEntry? = entries.lastOrNull()

    /** هل السجل فاضي؟ */
    fun isEmpty(): Boolean = entries.isEmpty()

    /** عدد السجلات. */
    fun size(): Int = entries.size

    /** يمسح كل السجلات. */
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    /** ينسخ كل السجلات كنص واحد جاهز للصق. */
    fun copyAll(): String {
        if (entries.isEmpty()) return "السجل فاضي — ما فيه أي أحداث مسجّلة بعد."
        val sb = StringBuilder()
        sb.append("═══════════════════════════════════════════════════\n")
        sb.append("  سجل اتصال VPN — نسخة كاملة\n")
        sb.append("  عدد الأحداث: ${entries.size}\n")
        sb.append("  وقت النسخ: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("═══════════════════════════════════════════════════\n\n")
        for (e in entries) {
            sb.append(e.toCopyString())
            sb.append("\n")
        }
        return sb.toString()
    }

    // ───────────────── المستمعون ─────────────────

    fun addListener(listener: LogListener) {
        listeners.add(listener)
        listener.onLogChanged(entries.toList())
    }

    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = entries.toList()
        for (l in listeners) {
            try {
                l.onLogChanged(snapshot)
            } catch (e: Exception) {
                // تجاهل أخطاء المستمعين
            }
        }
    }
}
