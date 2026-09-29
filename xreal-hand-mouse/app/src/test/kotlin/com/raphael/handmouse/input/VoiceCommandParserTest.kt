package com.raphael.handmouse.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TDD do parser de comandos de voz (2026-07-23, spec `2026-07-23-voice-control-design.md`):
 * frase exata (tabela bilíngue pt/en, normalizada: minúsculas + sem acento) → prefixo
 * "abrir/open" → prefixo "escrever/write" → NoMatch. O texto do ditado e o nome do app
 * preservam a forma ORIGINAL (caixa/acentos) — só o roteamento é normalizado.
 */
class VoiceCommandParserTest {

    @Test
    fun `Korean commands are recognized`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("뒤로"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("홈"))
        assertEquals(VoiceCommand.CloseApp, VoiceCommandParser.parse("닫기"))
        assertEquals(VoiceCommand.CloseApp, VoiceCommandParser.parse("창 닫기"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("최근 앱"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("전송"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("모두 지우기"))
    }

    @Test
    fun `comandos exatos em ingles`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("back"))
        assertEquals(VoiceCommand.Home, VoiceCommandParser.parse("home"))
        assertEquals(VoiceCommand.CloseApp, VoiceCommandParser.parse("close"))
        assertEquals(VoiceCommand.Recents, VoiceCommandParser.parse("recents"))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("send"))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("clear"))
    }

    @Test
    fun `abrir app extrai o nome original`() {
        assertEquals(VoiceCommand.OpenApp("YouTube"), VoiceCommandParser.parse("열기 YouTube"))
        assertEquals(VoiceCommand.OpenApp("chrome"), VoiceCommandParser.parse("open chrome"))
    }

    @Test
    fun `ditado preserva o texto original apos o prefixo`() {
        assertEquals(
            VoiceCommand.Dictate("안녕하세요. 곧 도착합니다"),
            VoiceCommandParser.parse("쓰기 안녕하세요. 곧 도착합니다"),
        )
        assertEquals(VoiceCommand.Dictate("hello there"), VoiceCommandParser.parse("write hello there"))
    }

    @Test
    fun `prefixo sem resto nao vira comando`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("열기"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("쓰기 "))
    }

    @Test
    fun `frases desconhecidas viram NoMatch`() {
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("날씨 알려줘"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("voltar"))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse(""))
        assertEquals(VoiceCommand.NoMatch, VoiceCommandParser.parse("   "))
    }

    @Test
    fun `pontuacao do formatter on-device nao quebra comandos`() {
        // 3ª rodada de tuning (2026-07-23): o recognizer on-device com EXTRA_ENABLE_FORMATTING
        // pontua ATÉ comandos ("abrir WhatsApp." em vez de "abrir WhatsApp") — a normalização
        // descarta pontuação no roteamento; o payload segue cru (o matcher re-normaliza).
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("뒤로."))
        assertEquals(VoiceCommand.ClearText, VoiceCommandParser.parse("모두 지우기."))
        assertEquals(VoiceCommand.SendEnter, VoiceCommandParser.parse("전송!"))
        assertEquals(VoiceCommand.OpenApp("WhatsApp."), VoiceCommandParser.parse("열기 WhatsApp."))
    }

    @Test
    fun `espacos extras sao tolerados`() {
        assertEquals(VoiceCommand.Back, VoiceCommandParser.parse("  뒤로  "))
        assertEquals(VoiceCommand.OpenApp("maps"), VoiceCommandParser.parse("  열기   maps"))
    }
}
