package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.SubscriptionManager
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Reads and writes the VoIMS opt-in provisioning value (key 68).
 *
 * When this value is 0 the modem does not advertise IMS voice capability during
 * network attach, the network answers "VoPS not supported" and IMS never registers.
 * Writing 1 restores VoLTE/VoNR registration without requiring root: the shell uid
 * delegate is enough for this telephony call.
 */
class ImsProvisioning : Instrumentation() {
    companion object {
        private const val TAG = "ImsProvisioning"
        private const val KEY_VOIMS_OPT_IN_STATUS = 68
        private const val VALUE_DISABLED = 0
        private const val VALUE_ENABLED = 1

        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_ENABLE = "enable"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
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

            val telephony = ITelephony.Stub.asInterface(
                ShizukuBinderWrapper(
                    TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyServiceRegisterer()
                        .get()!!
                )
            )
            // Resolve through the runtime class: the AIDL stub used at compile time may
            // declare a different return type than the framework implementation.
            val getter = telephony.javaClass.getMethod(
                "getImsProvisioningInt",
                Integer.TYPE,
                Integer.TYPE
            )
            val setter = telephony.javaClass.getMethod(
                "setImsProvisioningInt",
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            )

            val selectedSubId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            val enable = arguments.getBoolean(BUNDLE_ENABLE, true)
            val targetValue = if (enable) VALUE_ENABLED else VALUE_DISABLED
            val subIds: IntArray = if (selectedSubId == -1) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                intArrayOf(selectedSubId)
            }

            for (subId in subIds) {
                val current = getter.invoke(telephony, subId, KEY_VOIMS_OPT_IN_STATUS) as Int
                Log.i(TAG, "provisioning[68] subId=$subId current=$current target=$targetValue")
                if (current != targetValue) {
                    setter.invoke(telephony, subId, KEY_VOIMS_OPT_IN_STATUS, targetValue)
                    Log.i(TAG, "provisioning[68] subId=$subId updated to $targetValue")
                }
                if (enable) {
                    // Once voims=1 the user switch no longer falls back to the carrier default,
                    // so an unset (-1) value is read as disabled. Write it explicitly.
                    if (readBoolean(telephony, "isAdvancedCallingSettingEnabled", subId) != true) {
                        writeBoolean(telephony, "setAdvancedCallingSettingEnabled", subId, true)
                    }
                    if (readBoolean(telephony, "isVoNrEnabled", subId) != true) {
                        writeBoolean(telephony, "setVoNrEnabled", subId, true)
                    }
                }
            }

            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "update ims provisioning failed", t)
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

    private fun readBoolean(telephony: Any, methodName: String, subId: Int): Boolean? =
        runCatching {
            val method = telephony.javaClass.getMethod(methodName, Integer.TYPE)
            method.invoke(telephony, subId) as? Boolean
        }.onFailure { Log.w(TAG, "$methodName failed", it) }.getOrNull()

    private fun writeBoolean(telephony: Any, methodName: String, subId: Int, value: Boolean) {
        runCatching {
            val method = telephony.javaClass.getMethod(
                methodName,
                Integer.TYPE,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(telephony, subId, value)
        }.onFailure { Log.w(TAG, "$methodName failed", it) }
    }
}
