package agent

import model.AgentAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the stagnation / loop-detection logic used by [UiAgent].
 *
 * We can't easily exercise [UiAgent.execute] from a unit test (it requires an
 * LLM and a running IDE), so this file encodes the same algorithm as a pure
 * function and asserts the thresholds agreed in the design doc:
 *
 *  - "same fingerprint" for >=2 consecutive iterations => warn + force Observe
 *  - "same fingerprint AND same action" for >=3 consecutive iterations => fail
 *
 * If the algorithm in [UiAgent] ever diverges from this spec, update **both**
 * places — the cross-reference is the contract.
 */
class StagnationDetectionTest {
    /**
     * Mirrors the relevant bookkeeping from [UiAgent.execute]. Returns the
     * updated streaks given a fresh observation + the LLM's action choice.
     */
    private data class Streaks(val sameFingerprint: Int, val sameAction: Int)

    private fun step(
        prevFingerprint: String,
        prevAction: String?,
        currentFingerprint: String,
        currentAction: String,
        streaks: Streaks,
    ): Streaks {
        val sameFp = prevFingerprint.isNotEmpty() && prevFingerprint == currentFingerprint
        val sameAct = prevAction != null && prevAction == currentAction
        val newFp = if (sameFp) streaks.sameFingerprint + 1 else 0
        val newAct = if (sameFp && sameAct) streaks.sameAction + 1 else 0
        return Streaks(newFp, newAct)
    }

    private val WARN = 2
    private val FAIL = 3

    @Test
    @DisplayName("Fresh state: no streak, no stagnation")
    fun fresh() {
        val s = step("", null, "fp_a", "Click('X')", Streaks(0, 0))
        assertEquals(0, s.sameFingerprint)
        assertEquals(0, s.sameAction)
    }

    @Test
    @DisplayName("Different fingerprints reset both streaks")
    fun differentFingerprintsReset() {
        var s = Streaks(3, 2)
        s = step("fp_a", "Click('X')", "fp_b", "Click('X')", s)
        assertEquals(0, s.sameFingerprint)
        assertEquals(0, s.sameAction)
    }

    @Test
    @DisplayName("Same fingerprint, different action: fingerprint streak grows, action streak stays 0")
    fun sameFpDifferentAction() {
        val s = step("fp_a", "Click('X')", "fp_a", "Observe", Streaks(1, 1))
        assertEquals(2, s.sameFingerprint)
        assertEquals(0, s.sameAction)
        assertTrue(s.sameFingerprint >= WARN, "Should trip the WARN threshold")
        assertFalse(s.sameAction >= FAIL, "Should not trip FAIL yet")
    }

    @Test
    @DisplayName("Three iterations of same fingerprint + same action triggers FAIL")
    fun threeSameActionsFail() {
        // Priming the state with two consecutive no-ops.
        var s = step("", null, "fp_a", "Click('X')", Streaks(0, 0))
        s = step("fp_a", "Click('X')", "fp_a", "Click('X')", s)
        s = step("fp_a", "Click('X')", "fp_a", "Click('X')", s)
        s = step("fp_a", "Click('X')", "fp_a", "Click('X')", s)

        assertTrue(s.sameAction >= FAIL, "Expected FAIL threshold, got ${s.sameAction}")
    }

    @Test
    @DisplayName("Intervening UI change breaks the streak even if action repeats")
    fun uiChangeBreaksStreak() {
        var s = step("", null, "fp_a", "Click('X')", Streaks(0, 0))
        s = step("fp_a", "Click('X')", "fp_a", "Click('X')", s)
        // UI actually changed this turn -> streak resets
        s = step("fp_a", "Click('X')", "fp_b", "Click('X')", s)
        assertEquals(0, s.sameFingerprint)
        assertEquals(0, s.sameAction)
    }

    @Test
    @DisplayName("Overriding stuck action with Observe (effective-action) is encoded by caller")
    fun effectiveActionOverride() {
        val streak = Streaks(sameFingerprint = 2, sameAction = 2)
        val effective =
            if (streak.sameFingerprint >= WARN && streak.sameAction >= 1) {
                AgentAction.Observe
            } else {
                AgentAction.Click("X")
            }
        assertEquals(AgentAction.Observe, effective)
    }
}
