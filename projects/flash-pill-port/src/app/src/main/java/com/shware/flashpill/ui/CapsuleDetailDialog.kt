package com.shware.flashpill.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.shware.flashpill.data.Capsule
import com.shware.flashpill.data.CapsuleRepository
import com.shware.flashpill.databinding.DialogCapsuleDetailBinding
import com.shware.flashpill.util.CapsuleActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * 胶囊详情 / 编辑对话框：
 * 编辑文字、5 色标、置顶 / 已完成、播放原声、分享、复制、转日历、提醒、删除。
 */
class CapsuleDetailDialog(
    private val hostContext: Context,
    private val capsule: Capsule,
    private val onChanged: () -> Unit,
) : Dialog(hostContext) {

    companion object {
        /** 原版 5 分类色标 */
        val TAG_COLORS = listOf(
            "NOTE" to 0xFF4A90D9.toInt(),      // 便签：蓝
            "IMPORTANT" to 0xFFE05252.toInt(), // 重要：红
            "TODO" to 0xFFE08A2E.toInt(),      // 待办：橙
            "MESSAGE" to 0xFF4CAF50.toInt(),   // 待发送：绿
            "IDEA" to 0xFF9C6ADE.toInt(),      // 灵感：紫
        )
    }

    private lateinit var binding: DialogCapsuleDetailBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var selectedTag: String? = capsule.colorTag
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogCapsuleDetailBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        binding.etText.setText(capsule.text)
        binding.cbPinned.isChecked = capsule.pinned
        binding.cbDone.isChecked = capsule.done
        rebuildTags()

        binding.btnPlay.isEnabled = capsule.audioPath != null
        binding.btnPlay.alpha = if (capsule.audioPath != null) 1f else 0.4f

        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnShare.setOnClickListener { CapsuleActions.share(context, capsule) }
        binding.btnCopy.setOnClickListener { CapsuleActions.copy(context, capsule) }
        binding.btnCalendar.setOnClickListener { CapsuleActions.toCalendar(context, capsule) }
        binding.btnRemind.setOnClickListener { showRemindOptions() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { delete() }
    }

    override fun onStop() {
        stopPlay()
        super.onStop()
    }

    private fun rebuildTags() {
        val container: LinearLayout = binding.tagRow
        container.removeAllViews()
        val size = dp(36)

        for ((key, color) in TAG_COLORS) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(12) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (key == selectedTag) {
                        setStroke(dp(3), Color.WHITE)
                    } else {
                        setStroke(0, Color.TRANSPARENT)
                    }
                }
                setOnClickListener {
                    selectedTag = if (selectedTag == key) null else key
                    rebuildTags()
                }
            }
            container.addView(dot)
        }
    }

    private fun showRemindOptions() {
        val options = arrayOf("15 分钟后", "1 小时后", "明天上午 9:00")
        AlertDialog.Builder(context)
            .setTitle(com.shware.flashpill.R.string.remind_title)
            .setItems(options) { _, which ->
                val delayMs = when (which) {
                    0 -> 15 * 60_000L
                    1 -> 60 * 60_000L
                    else -> {
                        val target = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, 9)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        target.timeInMillis - System.currentTimeMillis()
                    }
                }
                CapsuleActions.remind(context, capsule, System.currentTimeMillis() + delayMs)
            }
            .show()
    }

    private fun save() {
        val updated = capsule.copy(
            text = binding.etText.text.toString().trim(),
            colorTag = selectedTag,
            pinned = binding.cbPinned.isChecked,
            done = binding.cbDone.isChecked
        )
        scope.launch {
            withContext(Dispatchers.IO) { CapsuleRepository.get(context).update(updated) }
            onChanged()
            dismiss()
        }
    }

    private fun delete() {
        scope.launch {
            withContext(Dispatchers.IO) {
                CapsuleRepository.get(context).delete(capsule)
                capsule.audioPath?.let { runCatching { File(it).delete() } }
            }
            onChanged()
            dismiss()
        }
    }

    private fun togglePlay() {
        val path = capsule.audioPath ?: return
        if (player != null) {
            stopPlay()
            return
        }
        player = MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                setOnCompletionListener { stopPlay() }
                prepare()
                start()
            }.onFailure {
                release()
                player = null
            }
        }
    }

    private fun stopPlay() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}