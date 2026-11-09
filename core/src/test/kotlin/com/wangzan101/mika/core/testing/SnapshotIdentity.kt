package com.wangzan101.mika.core.testing

/**
 * Keep upstream prompt goldens unchanged while testing TinyFox's real prompts.
 * Only the exact leading identity name is canonicalized, before rendering,
 * hashing or measuring it. User messages, tools and the rest of the prompt
 * remain byte-for-byte observable; production identity has its own assertion.
 */
internal object SnapshotIdentity {
    private const val PERSONAL = "You are TinyFox, "
    private const val UPSTREAM = "You are Mika, "

    fun canonical(system: String): String =
        if (system.startsWith(PERSONAL)) UPSTREAM + system.removePrefix(PERSONAL) else system
}
