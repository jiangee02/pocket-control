package com.pocketcontrol.host

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.pocketcontrol.host.service.RcAccessibilityService

/**
 * 无障碍自动开关：依赖 WRITE_SECURE_SETTINGS 权限（由 Shizuku 通过 pm grant 授予，永久有效）。
 */
object AccessUtil {

    fun svcId(ctx: Context): String =
        ComponentName(ctx, RcAccessibilityService::class.java).flattenToString()

    /**
     * 真实探测：尝试一次无副作用的系统设置写入（写回当前值）。
     * SecurityException = 授权未生效；其他异常不作为未授权依据。
     */
    fun canWrite(ctx: Context): Boolean = try {
        val cur = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED
        ) ?: "0"
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, cur)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: Throwable) {
        true
    }

    fun isEnabled(ctx: Context): Boolean {
        val cur = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val id = svcId(ctx)
        return cur.split(':').any { it.equals(id, ignoreCase = true) }
    }

    fun enable(ctx: Context): Boolean {
        val id = svcId(ctx)
        val cur = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val list = cur.split(':').filter { it.isNotBlank() }.toMutableList()
        if (list.none { it.equals(id, ignoreCase = true) }) list.add(id)
        return try {
            val ok1 = Settings.Secure.putString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")
            )
            val ok2 = Settings.Secure.putString(
                ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1"
            )
            ok1 && ok2
        } catch (e: SecurityException) {
            false
        }
    }

    fun disable(ctx: Context): Boolean {
        val id = svcId(ctx)
        val cur = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return true
        val list = cur.split(':').filter { it.isNotBlank() && !it.equals(id, ignoreCase = true) }
        return try {
            val ok1 = Settings.Secure.putString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")
            )
            var ok2 = true
            if (list.isEmpty()) {
                ok2 = Settings.Secure.putString(
                    ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "0"
                )
            }
            ok1 && ok2
        } catch (e: SecurityException) {
            false
        }
    }
}
