package com.purelock.app.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Receiver required for optional Device Owner integration. It does nothing on its own: Device Owner
 * can only be provisioned by the user (adb or QR provisioning on a fresh device), never silently by the app.
 */
class PureLockAdminReceiver : DeviceAdminReceiver()
