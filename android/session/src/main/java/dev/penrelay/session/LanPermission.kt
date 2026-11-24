package dev.penrelay.session

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object LanPermission {
    const val NAME = "android.permission.ACCESS_LOCAL_NETWORK"
    fun allowed(sdk: Int, target: Int, granted: () -> Boolean): Boolean = sdk < 37 || target < 37 || granted()
    fun allowed(context: Context) = allowed(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion) {
        context.checkSelfPermission(NAME) == PackageManager.PERMISSION_GRANTED
    }
}
