package com.shware.flashpill.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shware.flashpill.data.Capsule
import com.shware.flashpill.databinding.ItemCapsuleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CapsuleAdapter(
    private val onClick: (Capsule) -> Unit,
    private val onToggleDone: (Capsule) -> Unit,
) : ListAdapter<Capsule, CapsuleAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Capsule>() {
            override fun areItemsTheSame(oldItem: Capsule, newItem: Capsule) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Capsule, newItem: Capsule) =
                oldItem == newItem
        }

        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun colorOf(tag: String?): Int = when (tag) {
            "NOTE" -> 0xFF4A90D9.toInt()       // 便签：蓝
            "IMPORTANT" -> 0xFFE05252.toInt()  // 重要：红
            "TODO" -> 0xFFE08A2E.toInt()       // 待办：橙
            "MESSAGE" -> 0xFF4CAF50.toInt()    // 待发送：绿
            "IDEA" -> 0xFF9C6ADE.toInt()       // 灵感：紫
            else -> 0x00000000
        }
    }

    inner class VH(val binding: ItemCapsuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCapsuleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)

        holder.binding.tvText.text = when {
            item.text.isNotBlank() -> item.text
            item.audioPath != null -> "（语音待转写…）"
            else -> "（空）"
        }
        holder.binding.tvTime.text = timeFormat.format(Date(item.createdAt))
        holder.binding.tvStatus.text = when (item.status) {
            Capsule.STATUS_TRANSCRIBING -> "转写中…"
            Capsule.STATUS_TRANSCRIBED -> ""
            else -> if (item.audioPath != null) "待转写" else ""
        }

        // 完成态：勾选 + 文字加删除线
        holder.binding.cbDone.setOnCheckedChangeListener(null)
        holder.binding.cbDone.isChecked = item.done
        holder.binding.cbDone.setOnCheckedChangeListener { _, _ -> onToggleDone(item) }

        holder.binding.tvText.paintFlags = if (item.done) {
            holder.binding.tvText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            holder.binding.tvText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        holder.binding.dotTag.setBackgroundColor(colorOf(item.colorTag))
        holder.binding.root.setOnClickListener { onClick(item) }
    }
}