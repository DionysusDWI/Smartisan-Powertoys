package com.shware.flashpill

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shware.flashpill.config.ApiConfig
import com.shware.flashpill.service.SidebarService
import com.shware.flashpill.ui.CapsuleListActivity

/**
 * 主界面：权限引导 + 启停侧边栏 + 打开胶囊列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startSidebar() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            SidebarService.stop(this)
            refresh()
        }
        findViewById<Button>(R.id.btnList).setOnClickListener {
            startActivity(Intent(this, CapsuleListActivity::class.java))
        }
        findViewById<Button>(R.id.btnAutostart).setOnClickListener { openAutostartSettings() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startSidebar() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        SidebarService.start(this)
        refresh()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    /** 跳转小米自启动管理（失败则回退到应用详情页） */
    private fun openAutostartSettings() {
        val miuiIntent = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        runCatching { startActivity(miuiIntent) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val audio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        tvStatus.text = buildString {
            append("悬浮窗权限：")
            append(if (overlay) "已授予" else "未授予")
            append("\n录音权限：")
            append(if (audio) "已授予" else "未授予")
            append("\nAPI Key：")
            append(if (ApiConfig.hasSiliconFlowKey) "已配置" else "未配置")
        }
    }
}