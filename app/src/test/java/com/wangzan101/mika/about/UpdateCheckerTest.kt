package com.wangzan101.mika.about

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private var github: MockResponse = MockResponse().setResponseCode(500)
    private var releaseList: MockResponse = MockResponse().setResponseCode(500)
    private val latestPath = "/repos/SmartJoy-Tech/Mika/releases/latest"
    private val listPath = "/repos/SmartJoy-Tech/Mika/releases?per_page=1"
    private val hits = mutableListOf<String>()
    private var clock = 1_000_000_000L

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return if (request.path == listPath) releaseList else github
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun checker(
        current: String = "0.1.0",
        path: String = "/api",
        client: OkHttpClient = UpdateChecker.defaultClient(),
    ) = UpdateChecker(
        tmp.root.resolve("update.json"), current,
        client = client,
        releaseApi = server.url(path).toString(),
        now = { clock },
    )

    private fun release(tag: String) =
        MockResponse().setBody("""{"tag_name":"$tag","name":"Mika $tag","body":"- 新东西","html_url":"https://github.com/wangzan101/Mika/releases/tag/$tag","assets":[]}""")

    @Test
    fun newerReleaseFromGitHubShowsUpdate() = runTest {
        github = release("v0.2.0")
        val c = checker()
        c.check(manual = true)
        val s = c.state.value
        assertEquals(CheckStatus.Done, s.status)
        assertEquals("0.2.0", s.update?.version)
        assertTrue(s.hasUpdate)
        assertEquals(1, s.manualRounds)
        assertEquals(listOf("/api"), hits)
        val req = server.takeRequest()
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("TinyFox/"))
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        github = release("v0.1.0")
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertNull(c.state.value.update)
        assertEquals("0.1.0", c.state.value.latest?.version)
    }

    /** GitHub 限流(403)就是这次没查到:只问 GitHub 一次,不去别处找。 */
    @Test
    fun gitHubRateLimitIsAFailure() = runTest {
        github = MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}""")
        val c = checker()
        c.check(manual = true)
        assertEquals(listOf("/api"), hits)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.update)
    }

    @Test
    fun manualFailureIsReported() = runTest {
        val c = checker()
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertEquals(1, c.state.value.manualRounds)
        assertNull(c.state.value.update)
    }

    @Test
    fun latest404IsNoReleaseOnlyAfterTheSameRepositoryReturnsAnEmptyList() = runTest {
        github = MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
        releaseList = MockResponse().setBody(" \n[]\n ")
        val c = checker(path = latestPath)
        c.check(manual = true)
        assertEquals(listOf(latestPath, listPath), hits)
        assertEquals(CheckStatus.NoRelease, c.state.value.status)
        assertEquals(1, c.state.value.manualRounds)
        assertNull(c.state.value.latest)
        assertNull(c.state.value.update)
        server.takeRequest()
        val fallback = server.takeRequest()
        assertEquals("application/vnd.github+json", fallback.getHeader("Accept"))
        assertTrue(fallback.getHeader("User-Agent")!!.startsWith("TinyFox/"))
    }

    @Test
    fun confirmedEmptyReleaseListClearsTheCachedUpdateOnDisk() = runTest {
        github = release("v0.2.0")
        val c = checker(path = latestPath)
        c.check(manual = true)
        assertTrue(c.state.value.hasUpdate)

        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setBody("[]")
        c.check(manual = true)
        assertEquals(CheckStatus.NoRelease, c.state.value.status)
        assertEquals(2, c.state.value.manualRounds)
        assertFalse(c.state.value.hasUpdate)
        assertNull(c.state.value.latest)

        val reopened = checker(path = latestPath)
        reopened.load()
        assertNull(reopened.state.value.latest)
        assertNull(reopened.state.value.update)
    }

    @Test
    fun aFailedEmptyReleaseProbeKeepsThePreviouslyKnownUpdate() = runTest {
        github = release("v0.2.0")
        val c = checker(path = latestPath)
        c.check(manual = true)
        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setResponseCode(403).setBody("[]")
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertEquals("0.2.0", c.state.value.latest?.version)
        assertEquals("0.2.0", c.state.value.update?.version)
    }

    @Test
    fun inaccessibleNonemptyOrMalformedReleaseListsRemainFailures() = runTest {
        github = MockResponse().setResponseCode(404)
        val cases = listOf(
            200 to """[{"tag_name":"v0.2.0","prerelease":true}]""",
            200 to "{}", 200 to "null", 200 to "not json", 200 to "",
            204 to "", 403 to "[]", 404 to "[]", 500 to "[]",
        )
        for ((status, body) in cases) {
            hits.clear()
            releaseList = MockResponse().setResponseCode(status).setBody(body)
            val c = checker(path = latestPath)
            c.check(manual = true)
            assertEquals("HTTP $status body=$body", CheckStatus.Failed, c.state.value.status)
            assertNull(c.state.value.update)
            assertEquals(listOf(latestPath, listPath), hits)
        }
    }

    @Test
    fun aTimedOutReleaseListRemainsAFailure() = runTest {
        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setBody("[]").setHeadersDelay(1, TimeUnit.SECONDS)
        val client = OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS)
            .callTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val c = checker(path = latestPath, client = client)
        c.check(manual = true)
        assertEquals(CheckStatus.Failed, c.state.value.status)
        assertNull(c.state.value.latest)
        assertEquals(listOf(latestPath, listPath), hits)
    }

    @Test
    fun aRedirectCannotUseAnotherOriginToConfirmAnEmptyReleaseList() = runTest {
        val other = MockWebServer()
        other.enqueue(MockResponse().setBody("[]"))
        other.start()
        try {
            github = MockResponse().setResponseCode(404)
            releaseList = MockResponse().setResponseCode(302)
                .setHeader("Location", other.url("/releases"))
            val c = checker(path = latestPath)
            c.check(manual = true)
            assertEquals(CheckStatus.Failed, c.state.value.status)
            assertNull(c.state.value.latest)
            assertEquals(listOf(latestPath, listPath), hits)
            assertEquals("The empty-list confirmation must not leave the repository origin", 0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun otherLatestErrorsDoNotProbeOrPretendThereAreNoReleases() = runTest {
        releaseList = MockResponse().setBody("[]")
        for ((status, body) in listOf(403 to "[]", 429 to "[]", 500 to "[]", 200 to "{}")) {
            hits.clear()
            github = MockResponse().setResponseCode(status).setBody(body)
            val c = checker(path = latestPath)
            c.check(manual = true)
            assertEquals("HTTP $status", CheckStatus.Failed, c.state.value.status)
            assertEquals(listOf(latestPath), hits)
        }
    }

    @Test
    fun a404ForANoncanonicalApiAddressDoesNotGuessAnotherEndpoint() = runTest {
        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setBody("[]")
        val addresses = listOf(
            server.url("/api"),
            server.url("/other/SmartJoy-Tech/Mika/releases/latest"),
            server.url("/repos/SmartJoy-Tech/Mika/releases/latest/extra"),
            server.url(latestPath).newBuilder().addQueryParameter("extra", "1").build(),
            server.url(latestPath).newBuilder().fragment("extra").build(),
            server.url(latestPath).newBuilder().username("reader").build(),
        )
        for (address in addresses) {
            hits.clear()
            val c = UpdateChecker(tmp.root.resolve("update.json"), "0.1.0", releaseApi = address.toString())
            c.check(manual = true)
            assertEquals(address.toString(), CheckStatus.Failed, c.state.value.status)
            assertEquals(address.toString(), 1, hits.size)
        }
    }

    @Test
    fun automaticEmptyReleaseCheckRemainsSilent() = runTest {
        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setBody("[]")
        val c = checker(path = latestPath)
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Idle, c.state.value.status)
        assertEquals(0, c.state.value.manualRounds)
        assertNull(c.state.value.update)
        assertEquals(listOf(latestPath, listPath), hits)
    }

    @Test
    fun automaticEmptyReleaseCheckClearsCacheWithoutCompletingAManualRound() = runTest {
        github = release("v0.2.0")
        val c = checker(path = latestPath)
        c.check(manual = true)
        assertEquals(CheckStatus.Done, c.state.value.status)
        github = MockResponse().setResponseCode(404)
        releaseList = MockResponse().setBody("[]")
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Done, c.state.value.status)
        assertEquals(1, c.state.value.manualRounds)
        assertNull(c.state.value.latest)
        assertNull(c.state.value.update)
        val reopened = checker(path = latestPath)
        reopened.load()
        assertNull(reopened.state.value.latest)
        assertNull(reopened.state.value.update)
    }

    /** 自动检查失败不改界面上的状态;每次打开都查,只挡住两分钟内连着来的。 */
    @Test
    fun autoCheckIsSilentAndRunsOnEveryOpenButNotInBursts() = runTest {
        val c = checker()
        assertTrue(c.autoCheck())
        assertEquals(CheckStatus.Idle, c.state.value.status)
        assertEquals(0, c.state.value.manualRounds)
        val before = hits.size
        clock += UpdateSchedule.MIN_GAP_MS - 1
        assertFalse(c.autoCheck())
        assertEquals(before, hits.size)
        clock += 1
        github = release("v0.2.0")
        assertTrue(c.autoCheck())
        assertEquals("0.2.0", c.state.value.update?.version)
        assertEquals(CheckStatus.Idle, c.state.value.status)
    }

    /** 查到的版本存盘:重开 App 不联网也有红点;装上新版后红点消失;上次自动检查的时间也记着。 */
    @Test
    fun foundUpdateSurvivesRestart() = runTest {
        github = release("v0.2.0")
        checker().autoCheck()
        github = MockResponse().setResponseCode(500)

        val again = checker()
        again.load()
        assertEquals("0.2.0", again.state.value.update?.version)
        assertFalse("just checked", again.autoCheck())

        val upgraded = checker(current = "0.2.0")
        upgraded.load()
        assertNull(upgraded.state.value.update)
        assertNotNull(upgraded.state.value.latest)
    }

    /** 同时进来的两次自动检查(查还没完)只真的查一次;隔十分钟再打开照样查。 */
    @Test
    fun concurrentAutoChecksHitGitHubOnce() = runTest {
        github = release("v0.2.0")
        val c = checker()
        val ran = listOf(async { c.autoCheck() }, async { c.autoCheck() }).awaitAll()
        assertEquals(1, ran.count { it })
        assertEquals(1, hits.size)
        clock += 10 * 60 * 1000L
        assertTrue(c.autoCheck())
        assertEquals(2, hits.size)
    }

    @Test
    fun scheduleRules() {
        assertTrue(UpdateSchedule.due(null, 5))
        assertFalse(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS - 1))
        assertTrue(UpdateSchedule.due(1_000, 1_000 + UpdateSchedule.MIN_GAP_MS))
        assertTrue("clock moved back", UpdateSchedule.due(10_000, 5_000))
    }
}
