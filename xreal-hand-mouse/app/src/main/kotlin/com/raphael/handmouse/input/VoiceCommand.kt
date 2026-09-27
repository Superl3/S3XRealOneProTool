package com.raphael.handmouse.input

import java.text.Normalizer
import java.util.Locale

/**
 * Comando de voz reconhecido (2026-07-23, spec `2026-07-23-voice-control-design.md`). Quem
 * EXECUTA é o `VoiceCommandController` (global actions, AppLauncher, TextInserter) — este
 * arquivo é a fronteira PURA (testável em JVM) entre "texto reconhecido" e "ação".
 */
sealed class VoiceCommand {
    object Back : VoiceCommand()
    object Home : VoiceCommand()
    object Recents : VoiceCommand()
    object SendEnter : VoiceCommand()
    object ClearText : VoiceCommand()
    data class OpenApp(val query: String) : VoiceCommand()
    data class Dictate(val text: String) : VoiceCommand()
    object NoMatch : VoiceCommand()
}

/**
 * Texto reconhecido → [VoiceCommand]. Regras, nesta ordem:
 * 1. Frase EXATA na tabela bilíngue (ko + en sempre aceitos, independente do idioma escolhido
 *    no reconhecimento — a tabela é pequena e sinônimo não colide).
 * 2. Primeira palavra é um verbo de ABRIR ("abrir"/"abre"/"open") → resto é nome de app.
 * 3. Primeira palavra é um verbo de ESCREVER ("escrever"/"escreve"/"write") → resto é ditado.
 * 4. [VoiceCommand.NoMatch].
 *
 * O roteamento compara a forma NORMALIZADA (minúsculas, sem acento — [normalize]); o payload
 * (nome de app, texto de ditado) preserva a forma ORIGINAL do reconhecedor (caixa, acentos,
 * pontuação) — remove só a primeira palavra do texto cru.
 */
object VoiceCommandParser {

    private val EXACT: Map<String, VoiceCommand> = mapOf(
        "뒤로" to VoiceCommand.Back,
        "back" to VoiceCommand.Back,
        "홈" to VoiceCommand.Home,
        "닫기" to VoiceCommand.Home,
        "home" to VoiceCommand.Home,
        "최근 앱" to VoiceCommand.Recents,
        "최근앱" to VoiceCommand.Recents,
        "recents" to VoiceCommand.Recents,
        // "Close" and "닫기" return Home; Android cannot close another app directly.
        "close" to VoiceCommand.Home,
        "전송" to VoiceCommand.SendEnter,
        "send" to VoiceCommand.SendEnter,
        "모두 지우기" to VoiceCommand.ClearText,
        "clear" to VoiceCommand.ClearText,
    )

    private val OPEN_VERBS = setOf("열기", "open")
    private val WRITE_VERBS = setOf("쓰기", "write")

    fun parse(raw: String): VoiceCommand {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return VoiceCommand.NoMatch

        val normalized = normalize(trimmed)
        EXACT[normalized]?.let { return it }

        val firstWord = normalized.substringBefore(' ')
        // Payload = texto ORIGINAL sem a primeira palavra (preserva caixa/acento do ditado).
        val rest = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
        return when {
            firstWord in OPEN_VERBS && rest.isNotEmpty() -> VoiceCommand.OpenApp(rest)
            firstWord in WRITE_VERBS && rest.isNotEmpty() -> VoiceCommand.Dictate(rest)
            else -> VoiceCommand.NoMatch
        }
    }

    /** Minúsculas + remoção de acentos (NFD, descarta combining marks) + PONTUAÇÃO vira espaço
     * + espaços colapsados. A pontuação entrou na 3ª rodada de tuning (2026-07-23): o
     * recognizer ON-DEVICE com formatting pontua até comandos — "abrir WhatsApp." não casava
     * app nenhum, "Apagar tudo." não casava a tabela exata. Só a forma normalizada perde a
     * pontuação (roteamento/matching); o payload do DITADO segue cru, com a pontuação que o
     * formatter deu (é exatamente o que queremos inserir). */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .replace(Regex("[\\p{Punct}…]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
