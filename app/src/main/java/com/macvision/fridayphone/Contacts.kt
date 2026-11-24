package com.macvision.fridayphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the phone's own address book into a name -> number map so Friday can dial by voice
 * ("Friday call Chris"). Numbers stay on the device and are NEVER sent off-phone. Both the first
 * name ("chris") and the full display name ("chris jones") become keys pointing at the number;
 * the grammar only uses single-word (first-name) keys, which Vosk can recognize.
 *
 * Requires READ_CONTACTS; returns an empty map (no crash) if the permission isn't granted.
 */
object Contacts {

    private const val MAX = 250  // cap the grammar/map so a huge phonebook can't bloat recognition

    fun load(ctx: Context): Map<String, String> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(ListenerService.TAG, "contacts: READ_CONTACTS not granted")
            return emptyMap()
        }
        val map = LinkedHashMap<String, String>()
        try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cur?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numIdx < 0) return@use
                while (c.moveToNext() && map.size < MAX) {
                    val rawName = c.getString(nameIdx)?.trim()?.lowercase() ?: continue
                    val num = c.getString(numIdx)?.replace(Regex("[^+0-9]"), "") ?: continue
                    if (rawName.isBlank() || num.length < 5) continue
                    // only keep alphabetic names (skip "+1 555…" style display names)
                    val first = rawName.split(Regex("\\s+")).firstOrNull()
                        ?.filter { it.isLetter() } ?: continue
                    if (first.length in 2..15 && first !in map) map[first] = num
                    if (rawName != first && rawName.length <= 25 && rawName.all { it.isLetter() || it == ' ' }
                        && rawName !in map
                    ) map[rawName] = num
                }
            }
        } catch (e: Exception) {
            Log.w(ListenerService.TAG, "contacts load failed: ${e.message}")
        }
        Log.i(ListenerService.TAG, "contacts loaded: ${map.size} name keys")
        return map
    }
}
