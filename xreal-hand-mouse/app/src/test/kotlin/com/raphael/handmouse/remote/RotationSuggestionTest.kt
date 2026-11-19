package com.raphael.handmouse.remote

import com.raphael.handmouse.remote.RotationSuggestion.Hide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationSuggestionTest {
    /** An expanded panel drawn for [panelHold], never touched, air mouse as given. */
    private fun model(panelHold: Int = 0, air: Boolean = false) = RotationSuggestion(panelHold).apply {
        onExpanded(true, 0)
        if (air) onAirMouse(true, 0)
    }

    /** A model showing [target] since 800 ms (sensed at 0). */
    private fun showing(target: Int = 1, panelHold: Int = 0, air: Boolean = false) = model(panelHold, air).apply {
        onSensedHold(target, 0)
        onTime(800)
        assertEquals(target, shown)
    }

    @Test
    fun withoutAReadingNothingIsOffered() {
        val m = model()
        m.onTime(100_000)
        assertNull(m.shown)
        assertNull(m.nextDeadline())
    }

    @Test
    fun theHoldThePanelIsDrawnForIsNotOffered() {
        val m = model(panelHold = 1)
        m.onSensedHold(1, 1000)
        m.onTime(100_000)
        assertNull(m.shown)
        assertNull(m.nextDeadline())
    }

    @Test
    fun aQuarterTurnShowsOnceStableFor800Ms() {
        val m = model()
        m.onSensedHold(1, 1000)
        m.onTime(1799)
        assertNull(m.shown)
        assertEquals(1800L, m.nextDeadline())
        m.onTime(1800)
        assertEquals(1, m.shown)
        assertEquals(1800L, m.shownAtMs)
    }

    @Test
    fun turningBackBeforeItIsStableOffersNothing() {
        val m = model()
        m.onSensedHold(1, 1000)
        m.onSensedHold(0, 1500)
        m.onTime(5000)
        assertNull(m.shown)
        assertNull(m.nextDeadline())
    }

    @Test
    fun anotherHoldRestartsTheStableTime() {
        val m = model()
        m.onSensedHold(1, 1000)
        m.onSensedHold(3, 1500)
        m.onTime(2299)
        assertNull(m.shown)
        m.onTime(2300)
        assertEquals(3, m.shown)
    }

    @Test
    fun reversePortraitIsNeverOffered() {
        for (panelHold in listOf(0, 1, 3)) {
            val m = model(panelHold)
            m.onSensedHold(RotationSuggestion.REVERSE_PORTRAIT, 0)
            m.onTime(100_000)
            assertNull("panelHold $panelHold", m.shown)
            assertNull("panelHold $panelHold", m.nextDeadline())
        }
    }

    @Test
    fun aHalfTurnFromThePanelHoldIsNeverOffered() {
        // A low aim with a landscape panel reads as the other landscape: no chip.
        for ((panelHold, sensed) in listOf(1 to 3, 3 to 1, 2 to 0)) {
            val m = model(panelHold)
            m.onSensedHold(sensed, 0)
            m.onTime(100_000)
            assertNull("$panelHold -> $sensed", m.shown)
        }
    }

    @Test
    fun quarterTurnsIncludingReverseLandscapeAreOffered() {
        for ((panelHold, sensed) in listOf(0 to 1, 0 to 3, 1 to 0, 3 to 0, 2 to 1, 2 to 3)) {
            val m = model(panelHold)
            m.onSensedHold(sensed, 0)
            m.onTime(800)
            assertEquals("$panelHold -> $sensed", sensed, m.shown)
        }
    }

    @Test
    fun aCollapsedPanelOffersNothingAndOpeningShowsAStableHoldAtOnce() {
        val m = RotationSuggestion(0)
        m.onSensedHold(1, 0)
        m.onTime(5000)
        assertNull(m.shown)
        assertNull(m.nextDeadline())
        m.onExpanded(true, 5000)
        assertEquals(1, m.shown)
        assertEquals(5000L, m.shownAtMs)
    }

    @Test
    fun collapsingHidesWithoutDismissing() {
        val m = showing()
        m.onExpanded(false, 1000)
        assertNull(m.shown)
        assertEquals(Hide.COLLAPSED, m.lastHide)
        assertNull(m.dismissed)
        m.onExpanded(true, 2000)
        assertEquals(1, m.shown)
    }

    @Test
    fun aChipWaitsAQuietSecondAfterTheLastLift() {
        val m = model()
        m.onSensedHold(1, 0)
        m.onTouch(true, 100)
        m.onTime(900)
        assertNull("not under a finger", m.shown)
        assertNull(m.nextDeadline())
        m.onTouch(false, 1000)
        m.onTime(1999)
        assertNull("not right after the lift", m.shown)
        assertEquals(2000L, m.nextDeadline())
        m.onTime(2000)
        assertEquals(1, m.shown)
        assertEquals(2000L, m.shownAtMs)
    }

    @Test
    fun aShownChipStaysUnderAFingerAndSettlesAtTheLift() {
        val m = showing()
        m.onTouch(true, 900)
        m.onSensedHold(0, 1000)
        m.onTime(5000)
        assertEquals("frozen while touched", 1, m.shown)
        m.onTouch(false, 5000)
        assertNull(m.shown)
        assertEquals(Hide.RETURNED, m.lastHide)
    }

    @Test
    fun aTimeoutDuringATouchHidesAtTheLift() {
        val m = showing()
        m.onTouch(true, 1000)
        m.onTime(20_000)
        assertEquals(1, m.shown)
        m.onTouch(false, 20_000)
        assertNull(m.shown)
        assertEquals(Hide.TIMEOUT, m.lastHide)
        assertEquals(1, m.dismissed)
    }

    @Test
    fun theChipTimesOutAndThatHoldIsDismissed() {
        val m = showing()
        assertEquals(8800L, m.nextDeadline())
        m.onTime(8799)
        assertEquals(1, m.shown)
        m.onTime(8800)
        assertNull(m.shown)
        assertEquals(Hide.TIMEOUT, m.lastHide)
        assertEquals(1, m.dismissed)
        assertNull(m.nextDeadline())
        m.onTime(100_000)
        assertNull(m.shown)
    }

    @Test
    fun aDismissedHoldReturnsOnlyAfterTheHoldChanges() {
        val m = showing()
        m.onTime(8800)
        // A stale sensor (screen on, panel shown again) and a fresh reading of the same hold: still dismissed.
        m.onSensedHold(null, 9000)
        m.onSensedHold(1, 9500)
        m.onTime(30_000)
        assertNull(m.shown)
        assertEquals(1, m.dismissed)
        // Turning away and back offers it again.
        m.onSensedHold(0, 31_000)
        assertNull(m.dismissed)
        m.onSensedHold(1, 32_000)
        m.onTime(32_800)
        assertEquals(1, m.shown)
    }

    @Test
    fun reopeningAndScreenWakeDoNotClearADismissal() {
        val m = showing()
        m.onTime(8800)
        m.onExpanded(false, 9000)
        m.onExpanded(true, 10_000)
        m.onTime(30_000)
        assertNull(m.shown)
        assertEquals(1, m.dismissed)
    }

    @Test
    fun aRestoredDismissalHoldsInTheNextPanel() {
        val m = model()
        m.restoreDismissed(5)
        assertEquals(1, m.dismissed)
        m.onSensedHold(1, 0)
        m.onTime(30_000)
        assertNull(m.shown)
    }

    @Test
    fun withTheAirMouseOnTheChipTimesOut_andReturnsAQuietSecondAfterTheNextLift() {
        val m = showing(air = true)
        assertEquals(8800L, m.nextDeadline())
        m.onTime(8800)
        // A flat phone reads nothing new, so a false hold must not keep the keyboard button covered.
        assertNull(m.shown)
        assertEquals(Hide.TIMEOUT, m.lastHide)
        assertEquals(1, m.dismissed)
        assertNull(m.nextDeadline())
        m.onTime(100_000)
        assertNull("not without a touch", m.shown)
        // The panel is used again: the mismatch that makes the air mouse aim wrong is offered again.
        m.onTouch(true, 100_000)
        m.onTouch(false, 100_100)
        assertEquals(101_100L, m.nextDeadline())
        m.onTime(101_100)
        assertEquals(1, m.shown)
        assertEquals(109_100L, m.nextDeadline())
    }

    @Test
    fun withTheAirMouseOnATimeoutAtTheLiftIsNotOfferedAgainByThatLift() {
        val m = showing(air = true)
        m.onTouch(true, 1000)
        m.onTouch(false, 20_000)
        assertNull(m.shown)
        assertEquals(Hide.TIMEOUT, m.lastHide)
        m.onTime(100_000)
        assertNull(m.shown)
    }

    @Test
    fun withTheAirMouseOffATimedOutHoldStaysDismissedAfterLifts() {
        val m = showing()
        m.onTime(8800)
        m.onTouch(true, 9000)
        m.onTouch(false, 9100)
        m.onTime(100_000)
        assertNull(m.shown)
        assertEquals(1, m.dismissed)
    }

    @Test
    fun theAirMouseOffersADismissedQuarterTurnAgain() {
        val m = showing()
        m.onTime(8800)
        assertNull(m.shown)
        m.onAirMouse(true, 9000)
        assertEquals(1, m.shown)
        assertEquals(9000L, m.shownAtMs)
    }

    @Test
    fun aPressCountsOnlyFromArmMsAfterTheChipAppeared() {
        val m = showing()
        assertNull(m.accept(800 + RotationSuggestion.ARM_MS - 1))
        assertEquals("an early press leaves the chip", 1, m.shown)
        assertEquals(1, m.accept(800 + RotationSuggestion.ARM_MS))
        assertNull(model().accept(5000))
    }

    @Test
    fun applyingTheOfferedHoldHidesTheChipForGood() {
        val m = showing()
        val target = m.accept(2000)!!
        m.onPanelHold(target, 2000)
        assertNull(m.shown)
        assertEquals(Hide.APPLIED, m.lastHide)
        assertNull(m.dismissed)
        assertNull(m.nextDeadline())
        m.onTime(100_000)
        assertNull(m.shown)
    }

    @Test
    fun aManualTurnAwayFromTheHeldHoldIsNotNaggedBack() {
        val m = model()
        m.onSensedHold(0, 0)
        m.onPanelHold(1, 1000)
        assertEquals(0, m.dismissed)
        m.onTime(30_000)
        assertNull(m.shown)
        // Turning the phone to the new hold and back again offers portrait.
        m.onSensedHold(1, 31_000)
        assertNull(m.dismissed)
        m.onSensedHold(0, 32_000)
        m.onTime(32_800)
        assertEquals(0, m.shown)
    }

    @Test
    fun turningThePanelToTheHeldHoldEndsAnOlderDismissal() {
        val m = model()
        m.restoreDismissed(1)
        m.onSensedHold(1, 0)
        m.onPanelHold(1, 100)
        assertNull(m.dismissed)
        assertEquals(1, m.panelHold)
    }

    @Test
    fun aStaleReadingHidesTheChipUntilAFreshStableOne() {
        val m = showing()
        m.onSensedHold(null, 2000)
        assertNull(m.shown)
        assertEquals(Hide.CHANGED, m.lastHide)
        assertNull(m.nextDeadline())
        m.onSensedHold(1, 3000)
        m.onTime(3799)
        assertNull(m.shown)
        m.onTime(3800)
        assertEquals(1, m.shown)
    }

    @Test
    fun anotherHoldWhileShownHidesItAndBecomesPending() {
        val m = showing()
        m.onSensedHold(3, 2000)
        assertNull(m.shown)
        assertEquals(Hide.CHANGED, m.lastHide)
        assertEquals(2800L, m.nextDeadline())
        m.onTime(2800)
        assertEquals(3, m.shown)
    }

    @Test
    fun holdsAreNormalisedModFour() {
        val m = RotationSuggestion(4)
        assertEquals(0, m.panelHold)
        m.onExpanded(true, 0)
        m.onSensedHold(5, 0)
        m.onTime(800)
        assertEquals(1, m.shown)
        m.onPanelHold(-1, 900)
        assertEquals(3, m.panelHold)
    }
}
