package com.shware.flashpill.config

import com.shware.flashpill.BuildConfig

/**
 * 运行期配置。
 * API Key 来自 local.properties → BuildConfig，不硬编码在代码里。
 */
object ApiConfig {
    /** 硅基流动 API Key */
    val siliconFlowApiKey: String
        get() = BuildConfig.SILICONFLOW_API_KEY

    /** 是否已配置有效 Key（未配置或仍是占位符时返回 false） */
    val hasSiliconFlowKey: Boolean
        get() = siliconFlowApiKey.isNotBlank() && !siliconFlowApiKey.startsWith("sk-xxx")
}