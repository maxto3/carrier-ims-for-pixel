package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.TelephonyFrameworkInitializer
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Guards against an Android 17 regression: selecting "Wi-Fi preferred" moves the voice
 * domain to IWLAN and clears voice from the cellular domain, but when the carrier ePDG
 * tunnel cannot be established IMS is never registered and the device loses voice service.
 *
 * This component falls back to "cellular preferred" when the current mode prefers Wi-Fi
 * while IMS is not registered.
 */
class WfcModeController : Instrumentation() {
    companion object {
        private const val TAG = "WfcModeController"
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
        const val BUNDLE_MODE = "mode"
        const val BUNDLE_CHANGED = "changed"

        private const val WFC_MODE_WIFI_ONLY = 0
        private const val WFC_MODE_CELLULAR_PREFERRED = 1
        private const val WFC_MODE_WIFI_PREFERRED = 2
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }

        val result = Bundle()
        if (!waitForShizukuBinderReady()) {
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, result)
            return
        }

        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        var delegated = false
        try {
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            delegated = true
            applyGuard(arguments, result)
        } catch (t: Throwable) {
            Log.e(TAG, "check wfc mode failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                runCatching { am.stopDelegateShellPermissionIdentityCompat() }
                    .onFailure { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }

        finish(Activity.RESULT_OK, result)
    }

    private fun applyGuard(arguments: Bundle, result: Bundle) {
        val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            .takeIf { it >= 0 }
            ?: arguments.getString(BUNDLE_SELECT_SIM_ID)?.toIntOrNull()
            ?: -1
        require(subId >= 0) { "invalid subId" }

        val tm = context.getSystemService(TelephonyManager::class.java)
        val imsManagerClass = Class.forName("com.android.ims.ImsManager")
        val getMode = imsManagerClass.getMethod("getWfcMode", Context::class.java)
        val setMode = imsManagerClass.getMethod(
            "setWfcMode",
            Context::class.java,
            Integer.TYPE
        )
        val current = getMode.invoke(null, context) as Int
        result.putInt(BUNDLE_MODE, current)
        Log.i(TAG, "current wfc mode $current, subId=$subId, available=${runCatching { tm.isWifiCallingAvailable }.getOrNull()}")

        if (current != WFC_MODE_WIFI_PREFERRED && current != WFC_MODE_WIFI_ONLY) {
            Log.i(TAG, "wfc mode $current already uses cellular, skip")
            result.putBoolean(BUNDLE_CHANGED, false)
            result.putBoolean(BUNDLE_RESULT, true)
            return
        }

        val telephony = ITelephony.Stub.asInterface(
            ShizukuBinderWrapper(
                TelephonyFrameworkInitializer
                    .getTelephonyServiceManager()
                    .getTelephonyServiceRegisterer()
                    .get()!!
            )
        )
        val registered = telephony.isImsRegistered(subId)
        Log.i(TAG, "wfc mode $current, imsRegistered=$registered, subId=$subId")
        if (registered) {
            result.putBoolean(BUNDLE_CHANGED, false)
            result.putBoolean(BUNDLE_RESULT, true)
            return
        }

        setMode.invoke(null, context, WFC_MODE_CELLULAR_PREFERRED)
        Log.i(TAG, "wfc mode fell back to cellular for subId $subId")
        result.putBoolean(BUNDLE_CHANGED, true)
        result.putInt(BUNDLE_MODE, WFC_MODE_CELLULAR_PREFERRED)
        result.putBoolean(BUNDLE_RESULT, true)
    }
}
