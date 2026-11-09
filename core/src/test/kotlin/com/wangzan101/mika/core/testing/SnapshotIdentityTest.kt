package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotIdentityTest {
    private val upstream = "You are Mika, the assistant.\nKeep the user's files."
    private val personal = "You are TinyFox, the assistant.\nKeep the user's files."
    private val tools = listOf(ToolSpec("inspect", "You are TinyFox, in a tool description.", "{}"))
    private val userText = "You are TinyFox, in the user's message."
    private val assistantText = "You are TinyFox, in the assistant's message."

    private fun loop(system: String, text: String = userText, reply: String = assistantText, specs: List<ToolSpec> = tools): String {
        val model = fakeModel { }
        model.requests += FakeRequest(system, listOf(
            Message(Role.User, listOf(Part.Text(text)), Author.User),
            Message(Role.Assistant, listOf(Part.Text(reply)), Author.Mika),
        ), specs)
        return LoopSnapshot.render("done", listOf("main" to model))
    }

    @Test fun productionIdentityRemainsPersonalWithoutSnapshotNormalization() {
        assertTrue(Prompts.system("identity").startsWith("You are TinyFox, "))
        assertTrue(SystemPrompt.identity().startsWith("You are TinyFox, "))
    }

    @Test fun onlyTheExactLeadingIdentityNameIsCanonicalized() {
        val suffix = "assistant.\nYou are TinyFox, quoted in the body."
        assertEquals("You are Mika, $suffix", SnapshotIdentity.canonical("You are TinyFox, $suffix"))
        for (text in listOf(
            "Before: You are TinyFox, assistant.", "\nYou are TinyFox, assistant.",
            "You are TinyFoxPlus, assistant.", "You are TinyFox. assistant.",
            "You are TinyFox,\nassistant.", "You are tinyfox, assistant.", upstream,
        )) assertEquals(text, text, SnapshotIdentity.canonical(text))
    }

    @Test fun renderersCanonicalizeIdentityBeforeLengthsAndHashesButLeaveOtherContent() {
        assertEquals(loop(upstream), loop(personal))
        assertEquals(PromptSurface.system(upstream, tools), PromptSurface.system(personal, tools))
        assertEquals(PromptSurface.layer(upstream, tools), PromptSurface.layer(personal, tools))
        assertTrue(loop(personal).contains(userText))
        assertTrue(loop(personal).contains(assistantText))
        assertTrue(PromptSurface.layer(personal, tools).contains(tools.single().description))
    }

    @Test fun unrelatedPromptToolAndMessageChangesStillChangeSnapshots() {
        // A same-length change below the first line must still change the digest.
        val changedPrompt = personal.replace("Keep", "Lose")
        val changedTools = listOf(tools.single().copy(description = "Changed tool behavior."))
        assertNotEquals(loop(personal), loop(changedPrompt))
        assertNotEquals(loop(personal), loop(personal, text = "A different user request."))
        assertNotEquals(loop(personal), loop(personal, reply = "A different assistant reply."))
        assertNotEquals(loop(personal), loop(personal, specs = changedTools))
        assertNotEquals(PromptSurface.system(personal, tools), PromptSurface.system(changedPrompt, tools))
        assertNotEquals(PromptSurface.layer(personal, tools), PromptSurface.layer(changedPrompt, tools))
        assertNotEquals(PromptSurface.system(personal, tools), PromptSurface.system(personal, changedTools))
        assertNotEquals(PromptSurface.layer(personal, tools), PromptSurface.layer(personal, changedTools))
    }
}
