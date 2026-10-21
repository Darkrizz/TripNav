package com.tripnav.app.search

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Text shared from another app, split into its link and its other lines.
 * Google Maps shares "Name\nAddress\nhttps://maps.app.goo.gl/…", or only the link; the link unwraps to either a URL
 * with coordinates or one that only names the place (`maps.google.com/?q=Name,+Address&ftid=…`).
 */
data class SharedText(val url: String?, val lines: List<String>) {
    /** What the person would call the place: the first line of the share, if it has one. */
    val label: String? get() = lines.firstOrNull()

    companion object {
        private val link = Regex("https?://\\S+")

        fun parse(text: String) = SharedText(
            url = link.find(text)?.value?.trimEnd('.', ',', ')', '"'),
            lines = text.replace(link, "\n").lines().map { it.trim().trim('"') }.filter { it.isNotBlank() },
        )
    }
}

object SharedPlace {
    private const val MAX_QUERIES = 5
    private val placePath = Regex("/maps/(?:place|search)/([^/@?]+)")
    private val postcode = Regex("\\b\\d{4,6}\\b")
    private val stopWords = setOf("the", "of", "and", "at", "in", "de", "la", "le", "el", "da", "do", "di")

    fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Coordinates anywhere in a (possibly encoded) URL, including a consent page's `continue=` target. */
    fun coordinates(url: String) = Geocoder.parseCoordinates(decode(url))

    /** The place a Maps URL names without giving coordinates: its `q=` text or its `/place/<name>` path. */
    fun query(url: String): String? {
        val u = url.toHttpUrlOrNull()
        u?.queryParameter("continue")?.let { inner -> query(inner)?.let { return it } }
        u?.queryParameter("q")?.trim()?.takeIf { it.isNotEmpty() && Geocoder.parseCoordinates(it) == null }?.let { return it }
        return placePath.find(url)?.groupValues?.get(1)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Searches to try, most specific first: the full "Name, Address", the name with its town (or state), the name alone and
     * finally the address alone. Postcodes are dropped: they confuse OSM geocoders more than they help.
     */
    fun queries(full: String): List<String> {
        val parts = full.split(',', '\n').map { postcode.replace(it, " ").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val name = parts.first()
        // The town is one of the last short parts: "…, Mumbai, Maharashtra" or "…, 43001 Tarragona".
        val towns = parts.drop(1).filter { p -> p.any { it.isLetter() } && p.split(' ').size <= 3 && !p.any { it.isDigit() } }.takeLast(2)
        val address = parts.drop(1).joinToString(", ")
        return (listOf(parts.joinToString(", ")) + towns.map { "$name, $it" } + listOfNotNull(name, address.ifBlank { null }))
            .distinct().take(MAX_QUERIES)
    }

    /** Share of [expected]'s words found in [candidate]'s name, 0..1. */
    fun nameScore(expected: String, candidate: String): Double {
        val want = words(expected)
        if (want.isEmpty()) return 0.0
        val have = words(candidate)
        return want.count { w -> have.any { it == w || (w.length >= 4 && (it.startsWith(w) || w.startsWith(it))) } }.toDouble() / want.size
    }

    /** The result that best matches [expected], renamed to it (the name the person shared), or null. */
    fun best(hits: List<Place>, expected: String, requireMatch: Boolean): Place? {
        val scored = hits.map { it to nameScore(expected, it.name) }
        val top = scored.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
            ?: if (requireMatch) return null else hits.firstOrNull() ?: return null
        return top.copy(name = expected)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in stopWords }
}
