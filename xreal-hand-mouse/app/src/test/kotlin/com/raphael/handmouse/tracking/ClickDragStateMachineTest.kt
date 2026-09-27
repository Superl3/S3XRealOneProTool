package com.raphael.handmouse.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD da máquina de estados clique-vs-drag (PLANO.md Fase 4/5, brief Tarefa 5): IDLE -> PRESSED
 * (pinchDown) -> Click se UP antes de HOLD_THRESHOLD_MS, ou DragStart+DRAGGING se ainda pinçado no
 * limiar; em DRAGGING, atualizações de posição emitem DragMove; UP emite DragEnd. Os testes usam a
 * constante HOLD_THRESHOLD_MS (não valores fixos) — robustos ao ajuste do limiar (700ms desde
 * 2026-07-22).
 *
 * ## Decisão de assinatura (documentada também no relatório da Tarefa 5)
 * O brief descreve a entrada como `pinchDown(t)`/`pinchUp(t)`/`positionUpdate(x,y,t)`. Como
 * `Click`/`DragStart` precisam carregar a posição CONGELADA (buscada via
 * [PositionHistory.positionAt] no momento do pinch-down — ver `CursorPipeline`), `pinchDown`
 * recebe `x,y` explicitamente: é o chamador (`CursorPipeline`) quem resolve o valor congelado
 * antes de invocar `pinchDown`, não a state machine. `pinchUp` não recebe posição — `DragEnd`
 * usa a última posição conhecida via `positionUpdate` (`lastX/lastY`), e o próprio `pinchUp`
 * também checa o limiar de 300ms de forma independente (não depende de `positionUpdate` ter
 * sido chamado antes) — mais robusto a chamadores que não tiquem a cada frame (ex.: dead-zone
 * suprimindo frames parados).
 */
class ClickDragStateMachineTest {

    @Test
    fun `clique rapido - UP antes do limiar emite Click na posicao congelada`() {
        val sm = ClickDragStateMachine()

        val downActions = sm.pinchDown(10f, 20f, 0L)
        val upActions = sm.pinchUp(ClickDragStateMachine.HOLD_THRESHOLD_MS - 1)

        assertTrue(downActions.isEmpty())
        assertEquals(listOf(ClickDragStateMachine.Action.Click(10f, 20f)), upActions)
    }

    @Test
    fun `hold vira drag exatamente no limiar`() {
        val t = ClickDragStateMachine.HOLD_THRESHOLD_MS
        val sm = ClickDragStateMachine()
        sm.pinchDown(10f, 20f, 0L)

        // ainda nao chegou ao limiar: nenhuma acao
        val antesDoLimiar = sm.positionUpdate(11f, 21f, t - 1)
        assertTrue(antesDoLimiar.isEmpty())

        // exatamente no limiar: DragStart na posicao CONGELADA (do down), nao na posicao atual
        val noLimiar = sm.positionUpdate(12f, 22f, t)
        assertEquals(listOf(ClickDragStateMachine.Action.DragStart(10f, 20f)), noLimiar)
    }

    @Test
    fun `drag emite moves e end`() {
        val t = ClickDragStateMachine.HOLD_THRESHOLD_MS
        val sm = ClickDragStateMachine()
        sm.pinchDown(10f, 20f, 0L)
        sm.positionUpdate(10f, 20f, t) // cruza o limiar -> DragStart

        val move1 = sm.positionUpdate(15f, 25f, t + 50)
        val move2 = sm.positionUpdate(18f, 28f, t + 100)
        val end = sm.pinchUp(t + 150)

        assertEquals(listOf(ClickDragStateMachine.Action.DragMove(15f, 25f)), move1)
        assertEquals(listOf(ClickDragStateMachine.Action.DragMove(18f, 28f)), move2)
        assertEquals(listOf(ClickDragStateMachine.Action.DragEnd(18f, 28f)), end)
    }

    @Test
    fun `UP sem DOWN e ignorado`() {
        val sm = ClickDragStateMachine()

        val actions = sm.pinchUp(100L)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `positionUpdate sem DOWN (estado IDLE) nao emite nada`() {
        val sm = ClickDragStateMachine()

        val actions = sm.positionUpdate(1f, 2f, 100L)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `reset volta para IDLE - proximo UP e ignorado`() {
        val sm = ClickDragStateMachine()
        sm.pinchDown(10f, 20f, 0L)

        sm.reset()
        val actions = sm.pinchUp(50L)

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `hold sem nenhum positionUpdate intermediario ainda promove a drag no UP tardio`() {
        // defensivo: se por algum motivo o pipeline nao tickar positionUpdate durante o hold
        // (ex.: dead-zone suprimindo, mao parada), o pinchUp por si so detecta que o limiar
        // passou e promove a Drag (inicio e fim na mesma posicao ja que a mao nao se moveu).
        val sm = ClickDragStateMachine()
        sm.pinchDown(10f, 20f, 0L)

        val upTardio = sm.pinchUp(ClickDragStateMachine.HOLD_THRESHOLD_MS + 100)

        assertEquals(
            listOf(
                ClickDragStateMachine.Action.DragStart(10f, 20f),
                ClickDragStateMachine.Action.DragEnd(10f, 20f),
            ),
            upTardio,
        )
    }
}
