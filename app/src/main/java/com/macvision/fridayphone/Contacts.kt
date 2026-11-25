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

    // Hard pins: a spoken short name is FORCED to the exact contact (by full display name), no matter
    // how many duplicates exist and with no starring needed. Resolves to the number from the phone's
    // own contacts at runtime (no number stored in code). Les's sons:
    private val PINNED: Map<String, String> = mapOf(
        "justin" to "justin moody",
        "chris" to "chris moody"
    )

    // Common nickname aliases: a contact's FORMAL first name also answers to these short forms, so
    // "call Chris" finds "Christopher Moody". Keys are the formal first name (lowercase).
    private val NICKNAMES: Map<String, List<String>> = mapOf(
        "christopher" to listOf("chris"), "kenneth" to listOf("ken", "kenny"),
        "robert" to listOf("rob", "bob", "bobby"), "william" to listOf("will", "bill", "billy"),
        "james" to listOf("jim", "jimmy"), "michael" to listOf("mike", "mikey"),
        "richard" to listOf("rick", "rich", "richie"), "john" to listOf("jack", "johnny"),
        "thomas" to listOf("tom", "tommy"), "charles" to listOf("charlie", "chuck"),
        "joseph" to listOf("joe", "joey"), "daniel" to listOf("dan", "danny"),
        "matthew" to listOf("matt"), "anthony" to listOf("tony"), "david" to listOf("dave"),
        "edward" to listOf("ed", "eddie"), "steven" to listOf("steve"), "stephen" to listOf("steve"),
        "andrew" to listOf("andy", "drew"), "joshua" to listOf("josh"), "nicholas" to listOf("nick"),
        "benjamin" to listOf("ben"), "samuel" to listOf("sam"), "timothy" to listOf("tim"),
        "jonathan" to listOf("jon", "johnny"), "patrick" to listOf("pat"), "gregory" to listOf("greg"),
        "jeffrey" to listOf("jeff"), "ronald" to listOf("ron"), "zachary" to listOf("zach"),
        "elizabeth" to listOf("liz", "beth", "lizzie"), "jennifer" to listOf("jen", "jenny"),
        "katherine" to listOf("kate", "katie", "kathy"), "catherine" to listOf("kate", "katie", "cathy"),
        "margaret" to listOf("maggie", "peggy"), "patricia" to listOf("pat", "patty", "trish"),
        "deborah" to listOf("deb", "debbie"), "rebecca" to listOf("becca", "becky"),
        "jessica" to listOf("jess"), "alexander" to listOf("alex"), "alexandra" to listOf("alex"),
        "victoria" to listOf("vicky"), "veronica" to listOf("vero"), "cynthia" to listOf("cindy")
    )

    fun load(ctx: Context): Map<String, String> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(ListenerService.TAG, "contacts: READ_CONTACTS not granted")
            return emptyMap()
        }
        val map = LinkedHashMap<String, String>()
        try {
            // STARRED DESC first → a favorite contact wins a first-name tie (so "Justin" = the son you
            // starred, not the first Justin alphabetically). Then name, then most-recently-contacted.
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, " +
                    ContactsContract.CommonDataKinds.Phone.LAST_TIME_CONTACTED + " DESC, " +
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
                    // nickname aliases: "Christopher" also answers to "chris" (starred contact wins the tie
                    // because the query is ordered STARRED DESC, so the first to claim a nick is kept).
                    NICKNAMES[first]?.forEach { nick -> if (nick !in map) map[nick] = num }
                    // hard pins override everything: force the short name to THIS exact contact.
                    PINNED.forEach { (short, full) -> if (rawName == full) { map[short] = num; Log.i(ListenerService.TAG, "pinned '$short' -> $full") } }
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
