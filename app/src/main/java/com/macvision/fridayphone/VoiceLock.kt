package com.macvision.fridayphone

import android.content.Context
import kotlin.math.sqrt

/**
 * On-device VOICEPRINT lock — only Les's enrolled voice may trigger a command. Uses Vosk's speaker
 * x-vectors (the "spk" field on a final result). The TV, a bystander, or JARVIS on the phone/laptop
 * fail the match and are ignored.
 *
 * FALLS OPEN until enrolled (so Friday works before you enroll), then LOCKS to Les's voice.
 * Enroll by voice: "Friday, enroll my voice" (captures a few utterances, averages, stores).
 */
object VoiceLock {
    private const val PREFS = "friday_voicelock"
    private const val KEY = "les_voiceprint"
    const val ENROLL_SAMPLES = 3                  // utterances to average into a stable print
    // Tuned from live data 2026-11-25: Les's "friday …" utterances scored 0.37–0.45, the
    // TV/far-end/background topped out at ~0.27. 0.32 sits in that gap → accepts Les, rejects noise.
    @Volatile var threshold = 0.32f               // cosine-sim floor for "this is Les" (Vosk x-vectors)

    @Volatile private var enrolled: FloatArray? = null
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        enrolled = s.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.isNotEmpty() }
    }

    fun isEnrolled(): Boolean = enrolled != null

    fun save(ctx: Context, v: FloatArray) {
        if (v.isEmpty()) return
        enrolled = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, v.joinToString(",")).apply()
    }

    fun clear(ctx: Context) {
        enrolled = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** true = allowed to act. Not enrolled → always true. Enrolled → only if the voiceprint matches Les. */
    fun isLes(spk: FloatArray?): Boolean {
        val e = enrolled ?: return true                     // not enrolled → fall open
        if (spk == null || spk.isEmpty()) return false      // enrolled but utterance carried no voiceprint → reject
        return cosine(e, spk) >= threshold
    }

    /** The match score (for logging/tuning); -1 if nothing to compare. */
    fun score(spk: FloatArray?): Float {
        val e = enrolled ?: return -1f
        if (spk == null || spk.isEmpty()) return -1f
        return cosine(e, spk)
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    fun average(vs: List<FloatArray>): FloatArray {
        val valid = vs.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return FloatArray(0)
        val n = valid.first().size
        val out = FloatArray(n)
        for (v in valid) {
            val m = minOf(n, v.size)
            for (i in 0 until m) out[i] = out[i] + v[i]
        }
        val c = valid.size.toFloat()
        for (i in 0 until n) out[i] = out[i] / c
        return out
    }
}
