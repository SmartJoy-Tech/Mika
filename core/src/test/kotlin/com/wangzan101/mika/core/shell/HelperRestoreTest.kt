package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRestoreTest {
    private val mika = PermissionCommands.accessibilityService("io.github.smartjoytech.tinyfox")
    private val other = "com.example.reader/.ReaderService"
    private val yes = RestoreWish(revive = true, accessibility = true)

    @Test
    fun wishFollowsCapsuleModeAndTasks() {
        assertTrue(RestoreWish.of(capsuleAlways = true, taskRunning = false, accessibilityWanted = true, accessibilityOn = true).revive)
        assertTrue(RestoreWish.of(capsuleAlways = false, taskRunning = true, accessibilityWanted = true, accessibilityOn = true).revive)
        // 胶囊只在任务时显示、也没有任务:用户强行停止就让它停着
        assertEquals(RestoreWish.NONE, RestoreWish.of(capsuleAlways = false, taskRunning = false, accessibilityWanted = true, accessibilityOn = true))
    }

    @Test
    fun accessibilityOnlyWhenWantedAndOn() {
        assertTrue(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = true).accessibility)
        // 用户在 Mika 里关掉了替我打开无障碍
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = false, accessibilityOn = true).accessibility)
        // 用户趁 Mika 活着在系统设置里关掉了
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = false).accessibility)
    }

    @Test
    fun flagsRoundTrip() {
        for (w in listOf(RestoreWish.NONE, yes, RestoreWish(true, false), RestoreWish(false, true))) {
            assertEquals(w, RestoreWish.fromFlags(w.flags))
        }
        assertEquals(0, RestoreWish.NONE.flags)
        assertEquals(RestoreWish.REVIVE or RestoreWish.ACCESSIBILITY, yes.flags)
    }

    @Test
    fun senderSendsOnNewShellOrChangedWish() {
        val sent = mutableListOf<Pair<String, RestoreWish>>()
        val sender = RestoreWishSender<String> { s, w -> sent += s to w; true }
        val a = "helper-a"
        sender.update(null, yes)
        assertTrue("没有助手就不交", sent.isEmpty())
        sender.update(a, yes)
        sender.update(a, yes)
        assertEquals(listOf(a to yes), sent)
        sender.update(a, RestoreWish.NONE)
        assertEquals(a to RestoreWish.NONE, sent.last())
        // 助手换了一个(新的 Binder):再交一次,哪怕心愿没变
        val b = String(charArrayOf('h'))
        sender.update(b, RestoreWish.NONE)
        assertEquals(3, sent.size)
        // 助手没了又回来:再交
        sender.update(null, RestoreWish.NONE)
        sender.update(b, RestoreWish.NONE)
        assertEquals(4, sent.size)
    }

    @Test
    fun senderRetriesAfterFailure() {
        var ok = false
        var calls = 0
        val sender = RestoreWishSender<String> { _, _ -> calls++; ok }
        sender.update("h", yes)
        ok = true
        sender.update("h", yes)
        sender.update("h", yes)
        assertEquals("失败的不算交过,下次再交;交到了就不重复", 2, calls)
    }

    @Test
    fun policyRestoresOnlyWhenWantedAndAppNotBack() {
        val p = HelperRestorePolicy()
        assertEquals(HelperRestorePolicy.Decision.NotWanted, p.decide(RestoreWish.NONE, appBack = false, now = 1_000))
        assertEquals(HelperRestorePolicy.Decision.AppBack, p.decide(yes, appBack = true, now = 1_000))
        assertTrue("没拉过", p.report(1_000).empty)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, appBack = false, now = 2_000))
        assertEquals(HelperRestoreReport(2_000, 1, 0), p.report(2_000))
    }

    @Test
    fun policyBacksOffWithinWindowThenAllowsAgain() {
        val p = HelperRestorePolicy(maxAttempts = 3, windowMs = 600_000)
        repeat(3) { assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 10_000L + it * 30_000)) }
        // 拉起来又马上死:窗口内第四次不拉
        assertEquals(HelperRestorePolicy.Decision.BackedOff, p.decide(yes, false, now = 100_000))
        assertEquals(HelperRestoreReport(70_000, 3, 100_000), p.report(100_000))
        // 第一次滑出窗口后又能拉一次
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 610_000))
        assertEquals(3, p.report(610_000).recent)
        assertEquals(0, p.report(10 * 600_000L).recent)
    }

    @Test
    fun policyIgnoresClockGoingBack() {
        val p = HelperRestorePolicy(maxAttempts = 1, windowMs = 600_000)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 500_000))
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 1_000))
    }

    @Test
    fun accessibilityCommandsAppendWithoutDuplicates() {
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$other:$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility(other, mika),
        )
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility("null", mika),
        )
        // 已经在列表里(哪种写法都算):不重写列表,只开总开关
        assertEquals(listOf(RestoreCommands.ENABLE_ACCESSIBILITY), RestoreCommands.accessibility("$mika:$other", mika))
        assertEquals(
            listOf(RestoreCommands.ENABLE_ACCESSIBILITY),
            RestoreCommands.accessibility("io.github.smartjoytech.tinyfox/io.github.smartjoytech.tinyfox.a11y.TinyFoxAccessibilityService", mika),
        )
    }

    @Test
    fun startCheckClassifiesLoss() {
        val removed = A11yObservation(enabled = false, connected = false)
        val unbound = A11yObservation(enabled = true, connected = false)
        val fine = A11yObservation(enabled = true, connected = true)
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = false, expected = true))
        // 起来时被拿掉,随后修好了:照样记一次被拿掉
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = true, expected = true))
        assertEquals(A11yLoss.Unbound, A11yStartCheck.loss(unbound, connectedSoon = false, expected = true))
        assertNull("几秒内系统自己绑上了", A11yStartCheck.loss(unbound, connectedSoon = true, expected = true))
        assertNull(A11yStartCheck.loss(fine, connectedSoon = true, expected = true))
        assertNull("从没连上过(还没设置)不算", A11yStartCheck.loss(removed, connectedSoon = false, expected = false))
    }
}
