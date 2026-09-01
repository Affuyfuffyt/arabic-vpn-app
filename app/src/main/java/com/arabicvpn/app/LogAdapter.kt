package com.arabicvpn.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * محوّل (Adapter) لعرض سجلات الاتصال في RecyclerView.
 * يلوّن كل سجل حسب نوعه (معلومات/خطأ/تحذير/نجاح/حالة).
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val entries = mutableListOf<ConnectionLog.LogEntry>()

    fun update(newEntries: List<ConnectionLog.LogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime: TextView = view.findViewById(R.id.tv_log_time)
        private val tvType: TextView = view.findViewById(R.id.tv_log_type)
        private val tvCode: TextView = view.findViewById(R.id.tv_log_code)
        private val tvWhere: TextView = view.findViewById(R.id.tv_log_where)
        private val tvWhy: TextView = view.findViewById(R.id.tv_log_why)
        private val tvCause: TextView = view.findViewById(R.id.tv_log_cause)

        fun bind(entry: ConnectionLog.LogEntry) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))

            tvTime.text = time
            tvType.text = "${entry.type.symbol} ${entry.type.arabicLabel()}"
            tvCode.text = "الرمز: ${entry.errorCode}"
            tvWhere.text = "وين: ${entry.where}"
            tvWhy.text = "ليش: ${entry.why}"
            tvCause.text = "السبب: ${entry.cause}"

            // تلوين حسب النوع
            val color = when (entry.type) {
                ConnectionLog.EventType.ERROR -> Color.parseColor("#FF6B6B")
                ConnectionLog.EventType.WARNING -> Color.parseColor("#FFD93D")
                ConnectionLog.EventType.SUCCESS -> Color.parseColor("#6BCB77")
                ConnectionLog.EventType.INFO -> Color.parseColor("#4D96FF")
                ConnectionLog.EventType.STATE -> Color.parseColor("#B392E0")
            }
            tvType.setTextColor(color)
        }
    }
}
