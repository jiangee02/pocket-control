package com.pocketcontrol.host

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pocketcontrol.host.service.SessionService

/**
 * 文件中转选择器：透明 Activity，收到主控下载请求时由 SessionService 拉起。
 * 独立存在的原因：从后台拉起 MainActivity 会被安卓后台启动限制静默拦截。
 * 若本 Activity 也被拦，SessionService 会补发通知，用户点通知即可打开。
 */
class PickerActivity : AppCompatActivity() {

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) SessionService.instance?.startDownload(uri)
            else SessionService.instance?.log("已取消选择文件")
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = SessionService.instance
        if (svc == null) {
            finish()
            return
        }
        svc.cancelPickFallback()
        try {
            picker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            svc.log("打开文件选择器失败：${e.message}")
            finish()
        }
    }
}