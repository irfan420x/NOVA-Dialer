package com.nova.dialer.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class RecentCall(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int, // CallLog.Calls.INCOMING_TYPE / OUTGOING_TYPE / MISSED_TYPE / ...
    val date: Long,
    val durationSeconds: Long,
    val simLabel: String?,
    val photoUri: String?
)

data class ContactItem(
    val id: Long,
    val contactId: Long,
    val lookupKey: String,
    val name: String,
    val number: String,
    val photoUri: String?,
    val starred: Boolean,
    val timesContacted: Int
)

object CallRepo {

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasCallLogPermission(context: Context) = hasPermission(context, Manifest.permission.READ_CALL_LOG)
    fun hasContactsPermission(context: Context) = hasPermission(context, Manifest.permission.READ_CONTACTS)
    fun hasCallPermission(context: Context) = hasPermission(context, Manifest.permission.CALL_PHONE)

    // ---------- Recents (CallLog) ----------

    fun loadRecents(context: Context, limit: Int = 200): List<RecentCall> {
        if (!hasCallLogPermission(context)) return emptyList()
        val out = mutableListOf<RecentCall>()
        try {
            val projection = arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.CACHED_PHOTO_URI
            )
            // v1.4: NO LIMIT in the sort order — the MediaStore provider
            // rejects the LIMIT token ("Invalid token LIMIT") and the
            // CallLog provider is not guaranteed to accept it either. The
            // caller caps the result with .take(limit) instead.
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numI = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameI = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeI = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateI = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durI = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val accI = c.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
                val photoI = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_PHOTO_URI)
                while (c.moveToNext()) {
                    val number = c.getString(numI) ?: continue
                    out += RecentCall(
                        id = c.getLong(idI),
                        number = number,
                        name = c.getString(nameI)?.takeIf { it.isNotBlank() },
                        type = c.getInt(typeI),
                        date = c.getLong(dateI),
                        durationSeconds = c.getLong(durI),
                        simLabel = simLabel(context, c.getString(accI)),
                        photoUri = c.getString(photoI)
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Recents", "Failed to load call log", e)
        }
        return out.take(limit)
    }

    fun deleteCallLogEntries(context: Context, ids: List<Long>): Boolean {
        if (ids.isEmpty()) return true
        return try {
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN ($placeholders)", args
            )
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Recents", "Failed to delete call log entries", e)
            false
        }
    }

    fun clearMissedCalls(context: Context): Boolean {
        return try {
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.TYPE} = ?", arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            )
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Recents", "Failed to clear missed calls", e)
            false
        }
    }

    private fun simLabel(context: Context, accountId: String?): String? {
        if (accountId.isNullOrBlank()) return null
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.callCapablePhoneAccounts
                .firstOrNull { it.id == accountId }
                ?.let { tm.getPhoneAccount(it)?.label?.toString() }
        } catch (_: Exception) { null }
    }

    // ---------- Contacts ----------

    fun loadContacts(context: Context, limit: Int = 2000): List<ContactItem> {
        if (!hasContactsPermission(context)) return emptyList()
        val out = mutableListOf<ContactItem>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone._ID,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                val contactI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val lookupI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val nameI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val timesI = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED)
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(nameI) ?: continue
                    val number = c.getString(numI) ?: continue
                    out += ContactItem(
                        id = c.getLong(idI),
                        contactId = c.getLong(contactI),
                        lookupKey = c.getString(lookupI) ?: "",
                        name = name,
                        number = number,
                        photoUri = c.getString(photoI),
                        starred = c.getInt(starI) == 1,
                        timesContacted = c.getInt(timesI)
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to load contacts", e)
        }
        return out
    }

    fun loadFavorites(context: Context): List<ContactItem> =
        loadContacts(context).filter { it.starred }

    /** T9 / name / number search over all contacts (in-memory, fast). */
    fun searchContacts(context: Context, query: String): List<ContactItem> {
        val all = loadContacts(context)
        val q = query.trim()
        if (q.isEmpty()) return all
        val digits = PhoneUtils.digitsOnly(q)
        return all.filter { c ->
            if (digits.isNotEmpty() && digits.all { it.isDigit() } && digits.length >= 2) {
                PhoneUtils.t9Matches(c.name, c.number, digits)
            } else {
                c.name.contains(q, ignoreCase = true) || PhoneUtils.digitsOnly(c.number).contains(digits)
            }
        }
    }

    fun setStarred(context: Context, contactId: Long, starred: Boolean): Boolean {
        return try {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) {
            ErrorLog.log(context, "Contacts", "Failed to ${if (starred) "star" else "unstar"} contact", e)
            false
        }
    }

    // ---------- Calling ----------

    /** One callable SIM, matched to its Telecom phone account. */
    data class SimInfo(
        val handle: android.telecom.PhoneAccountHandle,
        val displayName: String,
        val carrierName: String,
        val subId: Int
    )

    /** The SIMs that can place calls on this phone (empty when none). */
    fun listSims(context: Context): List<SimInfo> {
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as android.telephony.SubscriptionManager
            val subs = try { sm.activeSubscriptionInfoList } catch (_: Exception) { null }
                ?: return emptyList()
            val handles = try { tm.callCapablePhoneAccounts } catch (_: Exception) { null }
                ?: return emptyList()
            val out = ArrayList<SimInfo>()
            for (sub in subs) {
                // Match the subscription to its phone account: the handle id
                // is the subscription id on AOSP; fall back to the label.
                val handle = handles.firstOrNull { h ->
                    h.id == sub.subscriptionId.toString()
                } ?: handles.firstOrNull { h ->
                    try { tm.getPhoneAccount(h)?.label?.toString() == sub.displayName.toString() }
                    catch (_: Exception) { false }
                } ?: continue
                out += SimInfo(
                    handle = handle,
                    displayName = sub.displayName?.toString()?.ifBlank { null } ?: "SIM ${sub.simSlotIndex + 1}",
                    carrierName = sub.carrierName?.toString() ?: "",
                    subId = sub.subscriptionId
                )
            }
            out
        } catch (e: Exception) {
            ErrorLog.log(context, "Call", "Failed to list SIMs", e)
            emptyList()
        }
    }

    fun placeCall(context: Context, number: String): Boolean =
        placeCall(context, number, null)

    fun placeCall(
        context: Context,
        number: String,
        handle: android.telecom.PhoneAccountHandle?
    ): Boolean {
        val dial = number.trim()
        if (PhoneUtils.digitsOnly(dial).isEmpty()) return false
        if (!hasCallPermission(context)) {
            ErrorLog.log(context, "Call", "CALL_PHONE permission not granted")
            return false
        }
        // v1.4: Uri.fromParts keeps * and # intact (USSD codes like *#06#
        // or *123#). digitsOnly would strip them, and a raw Uri.parse
        // would treat # as a fragment delimiter.
        val telUri = Uri.fromParts("tel", dial, null)
        return try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            // v1.5: forcing a SIM (dual-SIM phones) goes through the extras.
            val extras = android.os.Bundle().apply {
                if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            tm.placeCall(telUri, extras)
            true
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_CALL, telUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    if (handle != null) putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                ErrorLog.log(context, "Call", "Failed to place call to $dial", e2)
                false
            }
        }
    }

    fun voicemailNumber(context: Context): String? {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.voiceMailNumber
        } catch (_: Exception) { null }
    }

    // ---------- Blocking (needs the default-dialer role) ----------

    fun isBlocked(context: Context, number: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            BlockedNumberContract.isBlocked(context, PhoneUtils.digitsOnly(number))
        } catch (_: Exception) { false }
    }

    fun setBlocked(context: Context, number: String, blocked: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            if (blocked) {
                val values = ContentValues().apply {
                    put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, PhoneUtils.digitsOnly(number))
                }
                context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
            } else {
                context.contentResolver.delete(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    "${BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER} = ?",
                    arrayOf(PhoneUtils.digitsOnly(number))
                ) > 0
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Block", "Failed to ${if (blocked) "block" else "unblock"} $number", e)
            false
        }
    }

    fun canBlockNumbers(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            BlockedNumberContract.canCurrentUserBlockNumbers(context)
        } catch (_: Exception) { false }
    }
}
