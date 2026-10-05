package com.openminis.app.provider

/**
 * [T-provider-custom-headers] Single chokepoint for the per-provider extra
 * headers override. The UI stores one raw "Key: Value" pair per line; this
 * parser turns that into the Map each provider applies on every outbound
 * request (chat / models / responses).
 *
 * Rules:
 * - blank / null input → empty map (no extra headers)
 * - empty lines and lines starting with '#' are skipped (allows commenting
 *   a header out without deleting the line)
 * - a line without ':' is skipped (malformed), not thrown — the UI is
 *   free-form text and we never want a typo to crash a request build
 * - first ':' splits key from value; both sides are trimmed; a header with
 *   an empty key is skipped
 * - duplicate keys: last occurrence wins (the later line replaces the
 *   earlier one), matching OkHttp's `.header(...)` replace semantics
 *
 * Callers apply the result AFTER the User-Agent override and after any
 * provider-default headers, so a custom header can deliberately replace
 * defaults (e.g. Authorization on a relay that needs a different scheme).
 */
fun parseCustomHeaders(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return@forEach
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
