package llm

import model.AgentAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for [LLMReasoner.ActionDto.toAction]'s type-string
 * normalization.
 *
 * Background: before this normalizer, LLMs that mirrored the prompt's
 * bold headers ("**MoveCaret**") would emit `"type": "MoveCaret"`, which
 * the dispatch table (snake_case only) silently downgraded to Observe —
 * stalling the agent for 6+ iterations. The normalizer collapses
 * PascalCase, camelCase, kebab-case, and space-separated variants back
 * to the canonical snake_case form.
 */
class ActionDtoNormalizationTest {
    @Test
    fun camelCaseTypesAreRecognized() {
        val a = LLMReasoner.ActionDto(type = "moveCaret", symbol = "bar").toAction()
        assertTrue(a is AgentAction.MoveCaret)
    }

    @Test
    fun pascalCaseTypesAreRecognized() {
        val a = LLMReasoner.ActionDto(type = "MoveCaret", symbol = "foo").toAction()
        assertTrue(a is AgentAction.MoveCaret, "expected MoveCaret, got $a")
        assertEquals("foo", (a as AgentAction.MoveCaret).symbol)
    }

    @Test
    fun snakeCaseStillWorks() {
        val a = LLMReasoner.ActionDto(type = "move_caret", symbol = "baz").toAction()
        assertTrue(a is AgentAction.MoveCaret)
    }

    @Test
    fun multiWordPascalCaseCollapses() {
        val a =
            LLMReasoner.ActionDto(
                type = "OpenContextMenu",
            ).toAction()
        assertTrue(a is AgentAction.OpenContextMenu, "expected OpenContextMenu, got $a")
    }

    @Test
    fun kebabAndSpaceVariantsCollapse() {
        val a1 = LLMReasoner.ActionDto(type = "close-all-popups").toAction()
        val a2 = LLMReasoner.ActionDto(type = "Close All Popups").toAction()
        assertTrue(a1 is AgentAction.CloseAllPopups)
        assertTrue(a2 is AgentAction.CloseAllPopups)
    }

    @Test
    fun clickMenuItemCarriesTarget() {
        val a =
            LLMReasoner.ActionDto(
                type = "ClickMenuItem",
                target = "Rename...",
            ).toAction()
        assertTrue(a is AgentAction.ClickMenuItem)
        assertEquals("Rename...", (a as AgentAction.ClickMenuItem).target)
    }

    @Test
    fun unknownTypeFallsBackToObserveButWarns() {
        val a = LLMReasoner.ActionDto(type = "TotallyMadeUp").toAction()
        assertTrue(a is AgentAction.Observe)
    }

    @Test
    fun emptyTypeFallsBackToObserve() {
        val a = LLMReasoner.ActionDto(type = "").toAction()
        assertTrue(a is AgentAction.Observe)
    }
}
