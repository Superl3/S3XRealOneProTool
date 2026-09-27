package com.raphael.handmouse.input

/**
 * Casamento do nome de app FALADO com os labels dos apps instalados (2026-07-23, spec
 * `2026-07-23-voice-control-design.md`). PURO (testável em JVM) — quem consulta o
 * PackageManager e lança o intent é o [AppLauncher].
 *
 * Normalização = a mesma do parser ([VoiceCommandParser.normalize]) + descarte de artigo
 * inicial ("abre o YouTube" → query "o youtube" → "youtube"). Ranking: igualdade exata >
 * prefixo > contém; empate no mesmo nível → label mais curto (o mais específico pro que foi
 * dito: "youtu" casa YouTube, não YouTube Music).
 */
object AppNameMatcher {

    data class InstalledApp(val label: String, val packageName: String)

    private val LEADING_ARTICLES = setOf("o", "a", "os", "as", "the")

    fun match(query: String, apps: List<InstalledApp>): InstalledApp? {
        val q = stripLeadingArticle(VoiceCommandParser.normalize(query))
        if (q.isEmpty()) return null

        val normalized = apps.map { it to VoiceCommandParser.normalize(it.label) }

        normalized.filter { (_, label) -> label == q }
            .minByOrNull { (_, label) -> label.length }
            ?.let { return it.first }
        normalized.filter { (_, label) -> label.startsWith(q) }
            .minByOrNull { (_, label) -> label.length }
            ?.let { return it.first }
        return normalized.filter { (_, label) -> label.contains(q) }
            .minByOrNull { (_, label) -> label.length }
            ?.first
    }

    private fun stripLeadingArticle(q: String): String {
        val first = q.substringBefore(' ')
        return if (first in LEADING_ARTICLES) q.substringAfter(' ', "").trim() else q
    }
}
