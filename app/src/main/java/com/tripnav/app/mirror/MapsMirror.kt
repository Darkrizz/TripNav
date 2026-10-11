package com.tripnav.app.mirror

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tripnav.app.TripNavApp
import com.tripnav.app.data.LearnedIcon
import com.tripnav.app.data.LearnedIconDao
import com.tripnav.protocol.PodIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MirrorState(
    val icon: Int,
    val distanceM: Int,
    val instruction: String,
    val eta: String?,
    val iconHash: Long?,
    val iconKnown: Boolean,
    val updatedAt: Long,
)

/** Shared state between the notification listener and the rest of the app. */
class MirrorHub(private val scope: CoroutineScope, private val dao: LearnedIconDao) {
    private val _state = MutableStateFlow<MirrorState?>(null)
    val state: StateFlow<MirrorState?> = _state.asStateFlow()
    private val _lastBitmap = MutableStateFlow<Bitmap?>(null)
    /** Last maneuver bitmap Google Maps showed; used by the "teach icon" screen. */
    val lastBitmap: StateFlow<Bitmap?> = _lastBitmap.asStateFlow()
    @Volatile private var learned: Map<Long, Int> = emptyMap()

    init { scope.launch { learned = dao.all().associate { it.hash to it.podIcon } } }

    fun lookup(hash: Long): Int? = learned.entries.firstOrNull { java.lang.Long.bitCount(it.key xor hash) <= 6 }?.value

    fun publish(state: MirrorState?, bitmap: Bitmap?) {
        _state.value = state
        if (bitmap != null) _lastBitmap.value = bitmap
    }

    fun teach(hash: Long, podIcon: Int) {
        learned = learned + (hash to podIcon)
        scope.launch { dao.put(LearnedIcon(hash, podIcon)) }
        _state.value = _state.value?.let { if (it.iconHash == hash) it.copy(icon = podIcon, iconKnown = true) else it }
    }

    fun isFresh(now: Long = System.currentTimeMillis()) = _state.value?.let { now - it.updatedAt < 15_000 } == true
}

/** Reads Google Maps' turn-by-turn notification so a Maps-guided ride can be mirrored to the pod. */
class MapsMirrorService : NotificationListenerService() {
    private val hub get() = (application as TripNavApp).container.mirror

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in MAPS_PACKAGES) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val parsed = MirrorParser.parse(title, text, (application as TripNavApp).container.rightHandTraffic()) ?: return
        val bitmap = largeIconBitmap(n)
        val hash = bitmap?.let { IconHash.dHash(it) }
        val learned = hash?.let { hub.lookup(it) }
        hub.publish(
            MirrorState(
                icon = learned ?: parsed.icon, distanceM = parsed.distanceM, instruction = parsed.instruction,
                eta = sub, iconHash = hash, iconKnown = learned != null, updatedAt = System.currentTimeMillis(),
            ),
            bitmap,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in MAPS_PACKAGES && sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) hub.publish(null, null)
    }

    private fun largeIconBitmap(n: Notification): Bitmap? = runCatching {
        val icon: Icon = n.getLargeIcon() ?: return null
        val d = icon.loadDrawable(this) ?: return null
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        d.setBounds(0, 0, 64, 64); d.draw(c)
        bmp
    }.getOrNull()

    companion object {
        val MAPS_PACKAGES = setOf("com.google.android.apps.maps", "com.google.android.apps.mapslite")
    }
}

/** 64-bit difference hash: robust to scaling and colour changes of the maneuver arrow. */
object IconHash {
    fun dHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}

data class ParsedTurn(val icon: Int, val distanceM: Int, val instruction: String)

/** Extracts distance and maneuver from Maps' notification text (English plus PT/ES/DE/FR basics). */
object MirrorParser {
    private val distance = Regex("(\\d+(?:[.,]\\d+)?)\\s?(km|m|mi|ft|yd)\\b", RegexOption.IGNORE_CASE)
    private val ordinal = Regex("(\\d+)(?:st|nd|rd|th|ª|a|º|o|e|\\.)?\\s+(?:exit|saída|salida|ausfahrt|sortie)", RegexOption.IGNORE_CASE)
    private val wordOrdinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "primeira" to 1, "segunda" to 2, "terceira" to 3, "quarta" to 4, "primera" to 1, "tercera" to 3,
    )

    fun parse(title: String, text: String, rightHand: Boolean = true): ParsedTurn? {
        val all = "$title $text"
        val dm = distance.find(all) ?: return null
        val value = dm.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val meters = when (dm.groupValues[2].lowercase()) {
            "km" -> value * 1000; "mi" -> value * 1609.344; "ft" -> value / 3.281; "yd" -> value * 0.9144; else -> value
        }.toInt()
        val instruction = listOf(title, text).map { it.replace(dm.value, "").trim(' ', '·', '-', ',') }
            .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { all }
        return ParsedTurn(iconFor(instruction.lowercase(), rightHand), meters, instruction)
    }

    fun iconFor(s: String, rightHand: Boolean = true): Int {
        val left = has(s, "left", "esquerda", "izquierda", "links", "gauche")
        val right = has(s, "right", "direita", "derecha", "rechts", "droite")
        return when {
            has(s, "roundabout", "rotary", "rotatória", "rotunda", "rotonda", "kreisverkehr", "rond-point") -> {
                val exit = ordinal.find(s)?.groupValues?.get(1)?.toIntOrNull()
                    ?: wordOrdinals.entries.firstOrNull { s.contains(it.key) }?.value ?: 1
                PodIcon.roundabout(exit, rightHand)
            }
            has(s, "u-turn", "make a u", "retorno", "faça o retorno", "cambio de sentido", "wenden", "demi-tour") ->
                if (right) PodIcon.U_TURN_RIGHT else PodIcon.U_TURN_LEFT
            has(s, "destination", "arrive", "destino", "ziel", "arrivée") ->
                if (left) PodIcon.DESTINATION_LEFT else if (right) PodIcon.DESTINATION_RIGHT else PodIcon.DESTINATION
            has(s, "ferry", "balsa", "fähre", "ferri", "bac") -> PodIcon.FERRY
            has(s, "sharp", "acentuad", "fechada", "cerrada", "scharf") ->
                if (left) PodIcon.SHARP_LEFT else PodIcon.SHARP_RIGHT
            has(s, "slight", "bear", "ligeiramente", "levemente", "leicht", "légèrement") ->
                if (left) PodIcon.SLIGHT_LEFT else PodIcon.SLIGHT_RIGHT
            has(s, "exit", "saída", "salida", "ausfahrt", "sortie", "off-ramp") ->
                if (left) PodIcon.OFF_RAMP_LEFT else PodIcon.OFF_RAMP_RIGHT
            has(s, "ramp", "acesso", "auffahrt", "bretelle") ->
                if (left) PodIcon.ON_RAMP_LEFT else PodIcon.ON_RAMP_RIGHT
            has(s, "fork", "bifurca", "gabelung") -> if (left) PodIcon.FORK_LEFT else PodIcon.FORK_RIGHT
            has(s, "merge", "converge", "incorpor", "einfädeln") ->
                if (left) PodIcon.MERGE_LEFT else if (right) PodIcon.MERGE_RIGHT else PodIcon.MERGE
            has(s, "keep", "mantenha", "mantente", "halten", "serrez") -> if (left) PodIcon.KEEP_LEFT else PodIcon.KEEP_RIGHT
            left -> PodIcon.TURN_LEFT
            right -> PodIcon.TURN_RIGHT
            else -> PodIcon.STRAIGHT
        }
    }

    private fun has(s: String, vararg words: String) = words.any { s.contains(it) }
}
