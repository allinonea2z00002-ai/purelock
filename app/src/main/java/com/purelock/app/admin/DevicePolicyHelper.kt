package com.purelock.app.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Optional stronger protection, used ONLY when the user has provisioned PureLock as Device Owner
 * themselves (see README). While a lock is active it blocks uninstalling and force-stopping the app;
 * both restrictions are lifted when the lock ends. Without Device Owner every function is a no-op.
 */
object DevicePolicyHelper {

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context) = ComponentName(context, PureLockAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun applyLockRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching {
            dpm(context).setUninstallBlocked(admin(context), context.packageName, true)
            dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_APPS_CONTROL)
        }
    }

    fun releaseLockRestrictions(context: Context) {
        if (!isDeviceOwner(context)) return
        runCatching {
            dpm(context).setUninstallBlocked(admin(context), context.packageName, false)
            dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_APPS_CONTROL)
        }
    }

    /** The exact command a user runs once from a computer to make PureLock the Device Owner. */
    fun provisioningCommand(context: Context): String =
        "adb shell dpm set-device-owner ${context.packageName}/com.purelock.app.admin.PureLockAdminReceiver"
}
