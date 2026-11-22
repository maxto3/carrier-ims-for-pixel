package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ServiceManager
import android.provider.Telephony
import android.system.Os
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

class ApnModifier : Instrumentation() {
    companion object {
        private const val TAG = "ApnModifier"
        private val APN_URI: Uri = Telephony.Carriers.CONTENT_URI
        private const val PREFER_APN_URI_PREFIX = "content://telephony/carriers/preferapn/subId/"

        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_NAME = "name"
        const val BUNDLE_APN = "apn"
        const val BUNDLE_TYPE = "type"
        const val BUNDLE_MCC = "mcc"
        const val BUNDLE_MNC = "mnc"
        const val BUNDLE_SANITIZE_IMS = "sanitize_ims"
        const val BUNDLE_HEAL_ONLY = "heal_only"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val result = Bundle()
        if (arguments == null) {
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, "missing arguments")
            finish(Activity.RESULT_OK, result)
            return
        }
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
            if (arguments.getBoolean(BUNDLE_HEAL_ONLY, false) ||
                arguments.getString(BUNDLE_HEAL_ONLY) == "true"
            ) {
                healImsType(arguments)
            } else if (arguments.getBoolean(BUNDLE_SANITIZE_IMS, false) ||
                arguments.getString(BUNDLE_SANITIZE_IMS) == "true"
            ) {
                sanitizeDefaultApnImsType()
            } else {
                applyApn(arguments)
            }
            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "apply apn failed", t)
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

    private fun applyApn(arguments: Bundle) {
        val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
        val name = arguments.getString(BUNDLE_NAME).orEmpty().trim()
        val apn = arguments.getString(BUNDLE_APN).orEmpty().trim()
        val type = arguments.getString(BUNDLE_TYPE).orEmpty().trim().ifBlank { "default,supl" }
        val mcc = arguments.getString(BUNDLE_MCC).orEmpty().filter { it.isDigit() }.take(3)
        val mnc = arguments.getString(BUNDLE_MNC).orEmpty().filter { it.isDigit() }.take(3)
        require(subId >= 0) { "invalid subId" }
        require(name.isNotBlank()) { "APN name is blank" }
        require(apn.isNotBlank()) { "APN is blank" }
        require(mcc.length == 3) { "MCC must be 3 digits" }
        require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }

        val numeric = mcc + mnc
        val values = ContentValues().apply {
            put("name", name)
            put("apn", apn)
            put("type", type)
            put("mcc", mcc)
            put("mnc", mnc)
            put("numeric", numeric)
            put("protocol", "IPV4V6")
            put("roaming_protocol", "IPV4V6")
            put("carrier_enabled", 1)
            put("edited", 1)
            put("sub_id", subId)
        }

        val existingId = findExistingApnId(subId, numeric, apn, type)
        val apnId = if (existingId != null) {
            val updated = context.contentResolver.update(
                Uri.withAppendedPath(APN_URI, existingId.toString()),
                values,
                null,
                null
            )
            if (updated <= 0) {
                throw IllegalStateException("update APN failed")
            }
            existingId
        } else {
            val inserted = context.contentResolver.insert(APN_URI, values)
                ?: throw IllegalStateException("insert APN failed")
            inserted.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("invalid APN id: $inserted")
        }
        val preferValues = ContentValues().apply {
            put("apn_id", apnId)
        }
        val preferredUpdated = context.contentResolver.update(
            Uri.parse("$PREFER_APN_URI_PREFIX$subId"),
            preferValues,
            null,
            null
        )
        if (preferredUpdated <= 0) {
            throw IllegalStateException("set preferred APN failed")
        }
        Log.i(TAG, "APN inserted for subId=$subId id=$apnId name=$name apn=$apn")
    }

    private data class ApnRow(
        val id: Long,
        val apn: String,
        val name: String,
        val subId: Int,
        val numeric: String,
        val types: List<String>,
    )

    private fun queryApnRows(numeric: String?): List<ApnRow> {
        val selection = if (numeric != null) "numeric=?" else null
        val selectionArgs = if (numeric != null) arrayOf(numeric) else null
        val rows = mutableListOf<ApnRow>()
        context.contentResolver.query(
            APN_URI,
            arrayOf("_id", "apn", "name", "type", "sub_id", "numeric"),
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            val apnIndex = cursor.getColumnIndexOrThrow("apn")
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val subIndex = cursor.getColumnIndexOrThrow("sub_id")
            val numericIndex = cursor.getColumnIndexOrThrow("numeric")
            while (cursor.moveToNext()) {
                rows.add(
                    ApnRow(
                        id = cursor.getLong(idIndex),
                        apn = cursor.getString(apnIndex).orEmpty(),
                        name = cursor.getString(nameIndex).orEmpty(),
                        subId = if (cursor.isNull(subIndex)) -1 else cursor.getInt(subIndex),
                        numeric = cursor.getString(numericIndex).orEmpty(),
                        types = cursor.getString(typeIndex).orEmpty()
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                    )
                )
            }
        }
        return rows
    }

    /**
     * Splits the IMS type out of any APN row that mixes it with other types.
     *
     * Android 17 never brings up a clean IMS-only PDN when "ims" shares a row with
     * "default"/"supl", so the modem never receives a P-CSCF address. When [createImsRow]
     * is set and no dedicated IMS APN exists yet, one is inserted so the IMS PDN always
     * has a bearer of its own.
     */
    private fun sanitizeDefaultApnImsType(
        numeric: String? = null,
        createImsRow: Boolean = false,
    ): Boolean {
        val rows = queryApnRows(numeric)
        if (rows.isEmpty()) return false
        var changed = false
        for (row in rows) {
            if (row.types.size <= 1 || !row.types.contains("ims")) continue
            val newType = row.types.filter { it != "ims" }.joinToString(",")
            val updated = context.contentResolver.update(
                Uri.withAppendedPath(APN_URI, row.id.toString()),
                ContentValues().apply { put("type", newType) },
                null,
                null
            )
            if (updated > 0) {
                changed = true
                Log.i(TAG, "split ims out of APN id=${row.id} -> $newType")
            }
        }
        val hasDedicatedIms = rows.any { it.types.size == 1 && it.types[0] == "ims" }
        if (createImsRow && !hasDedicatedIms) {
            val source = rows.firstOrNull { it.subId >= 0 } ?: rows.first()
            changed = insertDedicatedImsRow(source) || changed
        }
        return changed
    }

    private fun insertDedicatedImsRow(source: ApnRow): Boolean {
        val numeric = source.numeric.filter { it.isDigit() }
        if (numeric.length !in 5..6) {
            Log.w(TAG, "cannot derive numeric for IMS APN, skip insert")
            return false
        }
        val mcc = numeric.take(3)
        val mnc = numeric.drop(3)
        val values = ContentValues().apply {
            put("name", "IMS")
            put("apn", "ims")
            put("type", "ims")
            put("mcc", mcc)
            put("mnc", mnc)
            put("numeric", numeric)
            put("protocol", "IPV4V6")
            put("roaming_protocol", "IPV4V6")
            put("carrier_enabled", 1)
            put("edited", 1)
            if (source.subId >= 0) put("sub_id", source.subId)
        }
        val inserted = context.contentResolver.insert(APN_URI, values) ?: return false
        Log.i(TAG, "created dedicated IMS APN id=${inserted.lastPathSegment} numeric=$numeric")
        return true
    }

    /**
     * Periodic self-heal path: SIM or OTA provisioning can merge "ims" back into a general
     * APN row after the initial split, so this re-checks a single numeric and repairs it.
     */
    private fun healImsType(arguments: Bundle) {
        val mcc = arguments.getString(BUNDLE_MCC).orEmpty().filter { it.isDigit() }.take(3)
        val mnc = arguments.getString(BUNDLE_MNC).orEmpty().filter { it.isDigit() }.take(3)
        require(mcc.length == 3) { "MCC must be 3 digits" }
        require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }
        val numeric = mcc + mnc
        val changed = sanitizeDefaultApnImsType(numeric, createImsRow = true)
        Log.i(TAG, "heal ims apn type for $numeric, changed=$changed")
    }

    private fun findExistingApnId(
        subId: Int,
        numeric: String,
        apn: String,
        type: String,
    ): Long? {
        val queries = listOf(
            "numeric=? AND apn=? AND type=? AND sub_id=?" to arrayOf(numeric, apn, type, subId.toString()),
            "numeric=? AND apn=? AND type=?" to arrayOf(numeric, apn, type),
        )
        for ((selection, args) in queries) {
            val existing = runCatching {
                context.contentResolver.query(
                    APN_URI,
                    arrayOf("_id"),
                    selection,
                    args,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
            }.getOrNull()
            if (existing != null) return existing
        }
        return null
    }
}
