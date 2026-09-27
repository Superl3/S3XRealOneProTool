package com.raphael.handmouse.input

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserção de texto DITADO no campo focado (2026-07-23, spec
 * `2026-07-23-voice-control-design.md`). Exige `canRetrieveWindowContent="true"` +
 * `flagRetrieveInteractiveWindows` na config de acessibilidade (Task 7) — sem isso,
 * [AccessibilityService.findFocus] retorna null sempre.
 *
 * ## Estratégia de inserção (appendText) — PASTE primeiro (2ª rodada de tuning 2026-07-23)
 * Caminho primário: clipboard + `ACTION_PASTE` no cursor. Motivo da inversão ("'mensagem'
 * ainda aparece no começo"): o campo do WhatsApp/One UI reporta o PLACEHOLDER em `node.text`
 * de formas que escaparam de DUAS guardas (nem `isShowingHintText`, nem `hintText` igual ao
 * texto) — qualquer estratégia que LEIA o texto existente pra concatenar herda esse risco. O
 * paste opera no conteúdo REAL do campo (o hint não é conteúdo), então é imune por
 * construção; espaço-separador só quando o cursor não está no começo (`textSelectionEnd > 0`
 * e sem cara de hint). Fallback pra apps que recusam PASTE: `ACTION_SET_TEXT` com
 * concatenação, mantendo as guardas de hint (flag + comparação case-insensitive com
 * `hintText`). Estados do nó são logados por chamada (diagnóstico de hardware).
 *
 * "Enviar" usa `ACTION_IME_ENTER` (API 30+; minSdk 34) — o mesmo enter do teclado, que em
 * apps de chat envia a mensagem. "Apagar tudo" é SET_TEXT com string vazia.
 *
 * Não testável em JVM puro (AccessibilityNodeInfo real) — toda ação/falha é logada (brief).
 */
class TextInserter(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "TextInserter"
    }

    /** Anexa [text] ao campo focado. `false` se não há campo focado ou toda tentativa falhou.
     * Ver "Estratégia de inserção" no Javadoc da classe (PASTE primeiro desde a 2ª rodada de
     * tuning 2026-07-23). */
    fun appendText(text: String): Boolean {
        val node = focusedNode() ?: return false
        val rawText = node.text?.toString().orEmpty()
        val hint = node.hintText?.toString()
        // "Cara de hint": flag oficial OU texto igual ao hintText (trim + case-insensitive —
        // 1ª rodada comparava exato e o WhatsApp escapou; ver Javadoc da classe).
        val looksLikeHint = node.isShowingHintText ||
            (hint != null && rawText.trim().equals(hint.trim(), ignoreCase = true))

        // Diagnóstico de hardware (2ª rodada 2026-07-23): estados que decidem o caminho — SEM o
        // conteúdo (privacidade), só formas/tamanhos. Se o hint vazar de novo, é daqui que sai
        // a resposta de COMO o campo o reporta.
        Log.d(
            TAG,
            "appendText: hintShowing=${node.isShowingHintText} hintLen=${hint?.length ?: -1} " +
                "textLen=${rawText.length} sel=${node.textSelectionStart}..${node.textSelectionEnd} " +
                "looksLikeHint=$looksLikeHint",
        )

        // Caminho primário: PASTE no cursor (imune ao hint — ver Javadoc da classe). Espaço
        // separador só com o cursor além do começo E sem cara de hint (campo com conteúdo real
        // à esquerda do cursor).
        val needsLeadingSpace = !looksLikeHint && node.textSelectionEnd > 0
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ditado", if (needsLeadingSpace) " $text" else text))
        if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)) {
            Log.d(TAG, "Ditado inserido via PASTE (${text.length} chars, leadingSpace=$needsLeadingSpace)")
            return true
        }

        // Fallback: SET_TEXT com concatenação (apps que recusam PASTE), guardas de hint ativas.
        Log.d(TAG, "PASTE recusado — fallback SET_TEXT com concatenação")
        val existing = if (looksLikeHint) "" else rawText
        val combined = if (existing.isEmpty()) text else "$existing $text"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) Log.w(TAG, "SET_TEXT também recusado — ditado perdido")
        return ok
    }

    /** Enter do teclado (envia em apps de chat). `false` sem campo focado ou ação recusada. */
    fun pressEnter(): Boolean {
        val node = focusedNode() ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        Log.d(TAG, "IME_ENTER → $ok")
        return ok
    }

    /** Limpa o campo focado (SET_TEXT vazio). */
    fun clearAll(): Boolean {
        val node = focusedNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "clearAll → $ok")
        return ok
    }

    /** Campo com foco de INPUT em qualquer janela/display (exige flagRetrieveInteractiveWindows). */
    private fun focusedNode(): AccessibilityNodeInfo? {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) Log.w(TAG, "Nenhum campo de texto focado — comando de ditado ignorado")
        return node
    }
}
