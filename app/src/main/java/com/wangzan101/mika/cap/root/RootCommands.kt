package com.wangzan101.mika.cap.root

import android.os.SystemClock
import android.util.Base64
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.shell.SuAnswer
import com.wangzan101.mika.core.shell.SuProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Agent 的 root 命令:经 `su -c` 以 root 跑,标准输出、标准错误分开收(各最多 1 MB),到时限结束。
 * 命令先编成 base64 再在 root 的 sh 里解开交给 `sh -c`:外层那一行总是合法的 sh,命令里的引号、换行原样到达,
 * 写错的命令由里层 sh 报错,和「su 拒绝了」分得开(外层先打一行标记,拿不到标记就是 su 没跑)。
 * 有 toybox 的 timeout 就用它在 root 那边掐断,没有就只靠这一侧到点结束 su。
 * [allowed] 每次执行前都重新检查:任务装着端口时用户关掉设置,后面的调用也不能再启动 su。
 * [su] 给出 su 的完整路径(找不到为 null);su 没跑这条命令时经 [refused] 报出去(null 是 su 不在了),由设置那边记下并关掉这一项。
 */
class RootCommands(
    private val su: () -> String?,
    private val allowed: () -> Boolean,
    private val refused: (SuAnswer?) -> Unit,
) : RootPort {
    override suspend fun run(command: String, timeoutMs: Long): RootRun = runInterruptible(Dispatchers.IO) {
        // 设置是任务运行期间也能改的;端口只是装配结果,不是长期有效的授权凭证
        if (!allowed()) return@runInterruptible RootRun.Denied
        val su = su() ?: return@runInterruptible RootRun.Unavailable.also { refused(null) }
        val started = SystemClock.elapsedRealtime()
        val p = runCatching { ProcessBuilder(SuProbe.rootArgs(su, script(command, timeoutMs))).start() }
            .getOrElse { return@runInterruptible RootRun.Denied.also { refused(SuAnswer.Denied) } }
        runCatching { p.outputStream.close() }
        val out = onThread { drain(p.inputStream) }
        val err = onThread { drain(p.errorStream) }
        // 这一侧多等一会儿:root 那边的 timeout 先掐,su 本身卡住(授权框没人点)才由这里结束
        val exited = try {
            p.waitFor(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            throw e
        }
        if (!exited) p.destroyForcibly()
        val duration = SystemClock.elapsedRealtime() - started
        val (stdout, outDropped) = runCatching { out.get(2, TimeUnit.SECONDS) }.getOrDefault("" to 0L)
        val (stderr, errDropped) = runCatching { err.get(2, TimeUnit.SECONDS) }.getOrDefault("" to 0L)
        if (!stdout.startsWith(MARK)) {
            // 标记都没打出来:su 没跑这条命令
            val a = if (!exited) SuAnswer.NoAnswer else SuAnswer.Denied
            refused(a)
            return@runInterruptible if (a == SuAnswer.NoAnswer) RootRun.NoAnswer else RootRun.Denied
        }
        val exit = if (exited) p.exitValue() else -1
        // timeout -s KILL 掐断时退出码是 137(或 124)
        val timedOut = !exited || (exit == 137 || exit == 124) && duration >= timeoutMs - 500
        RootRun.Done(exit, timedOut, stdout.removePrefix(MARK).removePrefix("\n"), stderr, outDropped + errDropped, duration)
    }

    private fun script(command: String, timeoutMs: Long): String {
        val b64 = Base64.encodeToString(command.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val sec = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        return "echo $MARK; c=\"\$(echo $b64 | base64 -d)\"; " +
            "if command -v timeout >/dev/null 2>&1; then exec timeout -s KILL $sec sh -c \"\$c\"; else exec sh -c \"\$c\"; fi"
    }

    /** 在单独的线程上读(不占公共线程池)。 */
    private fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "mika-root").apply { isDaemon = true }.start()
    }

    /** 读完一个流,最多留 [MAX_BYTES],多的数着丢掉。 */
    private fun drain(stream: InputStream): Pair<String, Long> {
        val buf = ByteArrayOutputStream()
        var dropped = 0L
        val chunk = ByteArray(8192)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                val keep = minOf(n, MAX_BYTES - buf.size())
                if (keep > 0) buf.write(chunk, 0, keep)
                dropped += n - keep
            }
        }
        return buf.toString(Charsets.UTF_8.name()) to dropped
    }

    private companion object {
        const val MARK = "__mika_root_ok__"
        const val MAX_BYTES = 1 shl 20
        const val GRACE_MS = 5_000L
    }
}
