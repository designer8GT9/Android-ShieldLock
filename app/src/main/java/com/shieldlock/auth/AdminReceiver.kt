package com.shieldlock.auth

import android.app.admin.DeviceAdminReceiver

/**
 * Permite bloquear la pantalla (lockNow) y, en modo máximo, ser Device Owner.
 * Se declara en el AndroidManifest y usa res/xml/device_admin.xml.
 */
class AdminReceiver : DeviceAdminReceiver()
