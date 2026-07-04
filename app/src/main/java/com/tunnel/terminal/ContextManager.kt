package com.tunnel.terminal

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ContextManager - Manage @context mentions untuk AI chat.
 *
 * Phase 23: @context mentions (Warp-style).
 * User bisa mention di AI chat input:
 * - @file:path/to/file — attach file content sebagai context
 * - @block:N — attach command block N output
 * - @command:"cmd" — attach command output
 * - @terminal — attach current terminal output
 * - @snippet:name — attach saved snippet
 *
 * Mentions di-parse dari user input text, resolved ke content,
 * lalu dikirim sebagai additional context ke AI.
 *
 * @context mentions — attach files/blocks/commands as AI context.
 */
class ContextManager(private val context: Context) {
    private val tag = "ContextManager"

    /** Representasi satu mention yang di-resolve. */
    data class ResolvedMention(
        val mention: String,        // "@file:/sdcard/test.txt"
        val type: MentionType,
        val content: String,        // resolved content
        val displayName: String     // "test.txt" atau "block 3"
    )

    enum class MentionType { FILE, BLOCK, COMMAND, TERMINAL, SNIPPET, UNKNOWN }

    /** Parse mentions dari user input text.
     * Format: @file:path, @block:N, @command:"cmd", @terminal, @snippet:name
     */
    fun parseMentions(text: String): List<String> {
        val mentions = mutableListOf<String>()
        /* BUG-13 fix: Support quoted values ("...") dan paths dengan spasi.
         * Phase 40 fix (M3): Hapus alternatif "|@terminal" yang redundant —
         * bagian pertama regex sudah match @terminal (tanpa argumen).
         * Redundansi bisa menyebabkan double-match di edge case. */
        val regex = Regex("@(file|block|command|terminal|snippet)(?::(?:\"([^\"]+)\"|(\\S+)))?")
        regex.findAll(text).forEach { match ->
            mentions.add(match.value)
        }
        return mentions
    }

    /** Resolve semua mentions ke content.
     * @param text user input text
     * @param blockManager untuk @block resolution
     * @param terminalSession untuk @terminal resolution
     * @param snippetManager untuk @snippet resolution
     * @return list of resolved mentions + combined context string
     */
    fun resolveAll(
        text: String,
        blockManager: BlockManager,
        terminalSession: TerminalSession?,
        snippetManager: SnippetManager
    ): Pair<List<ResolvedMention>, String> {
        val mentions = parseMentions(text)
        val resolved = mutableListOf<ResolvedMention>()

        for (mention in mentions) {
            val resolvedMention = when {
                mention.startsWith("@file:") -> resolveFileMention(mention)
                mention.startsWith("@block:") -> resolveBlockMention(mention, blockManager)
                /* Phase 40 fix (M7): Skip @command: di resolveAll — akan di-resolve
                 * async via resolveCommandAsync di handleAIPrompt. */
                mention.startsWith("@command:") -> continue
                mention == "@terminal" -> resolveTerminalMention(terminalSession)
                mention.startsWith("@snippet:") -> resolveSnippetMention(mention, snippetManager)
                else -> ResolvedMention(mention, MentionType.UNKNOWN, "Unknown mention: $mention", mention)
            }
            resolved.add(resolvedMention)
        }

        /* Build combined context string untuk AI. */
        val contextStr = if (resolved.isEmpty()) "" else buildString {
            append("\n\n--- Context Mentions ---\n")
            resolved.forEach { m ->
                append("[${m.type.name}] ${m.displayName}:\n")
                append(m.content.take(2000))
                append("\n\n")
            }
            append("--- End Context ---\n")
        }

        return Pair(resolved, contextStr)
    }

    private fun resolveFileMention(mention: String): ResolvedMention {
        val path = mention.removePrefix("@file:").trim()
        val file = File(path)
        return if (file.exists() && file.canRead()) {
            ResolvedMention(
                mention = mention,
                type = MentionType.FILE,
                content = file.readText().take(5000),
                displayName = file.name
            )
        } else {
            ResolvedMention(mention, MentionType.FILE, "Error: file not found or unreadable: $path", path)
        }
    }

    private fun resolveBlockMention(mention: String, blockManager: BlockManager): ResolvedMention {
        val numStr = mention.removePrefix("@block:").trim()
        val num = numStr.toIntOrNull() ?: return ResolvedMention(mention, MentionType.BLOCK, "Invalid block number: $numStr", mention)
        val blocks = blockManager.blocks
        if (num < 1 || num > blocks.size) {
            return ResolvedMention(mention, MentionType.BLOCK, "Block $num not found (total: ${blocks.size})", mention)
        }
        val block = blocks[num - 1]
        return ResolvedMention(
            mention = mention,
            type = MentionType.BLOCK,
            content = "$ ${block.command}\n${block.output}",
            displayName = "block $num"
        )
    }

    /* Phase 40 fix (M7): resolveCommandMention dihapus dari resolveAll.
     * OLD BUG: Method ini return placeholder ("Command akan dieksekusi secara
     * real-time oleh MarkerExecutor.") yang ditambahkan ke context string.
     * Lalu resolveCommandAsync juga dipanggil, menumpuk output nyata.
     * Double context untuk command yang sama.
     * FIX: @command: hanya di-resolve via resolveCommandAsync (suspend),
     * tidak lagi via resolveAll (sync). Skip @command: di resolveAll. */

    /**
     * Phase 37: Resolve @command: dengan eksekusi nyata via MarkerExecutor.
     * Dipanggil async dari handleAIPrompt.
     */
    suspend fun resolveCommandAsync(
        mention: String,
        session: TerminalSession?,
        markerExecutor: MarkerExecutor
    ): ResolvedMention {
        val cmd = mention.removePrefix("@command:").trim().removeSurrounding("\"")
        if (session == null) {
            return ResolvedMention(mention, MentionType.COMMAND, "No active terminal session", mention)
        }
        val result = markerExecutor.executeWithMarker(session, cmd, timeoutMs = 15000)
        val resultText = markerExecutor.formatResultForAI(result)
        return ResolvedMention(
            mention = mention,
            type = MentionType.COMMAND,
            content = resultText,
            displayName = "command: $cmd (exit ${result.exitCode})"
        )
    }

    private fun resolveTerminalMention(session: TerminalSession?): ResolvedMention {
        if (session == null) {
            return ResolvedMention("@terminal", MentionType.TERMINAL, "No active terminal session", "@terminal")
        }
        return ResolvedMention(
            mention = "@terminal",
            type = MentionType.TERMINAL,
            content = session.getCleanOutput().take(3000),
            displayName = "terminal output"
        )
    }

    private fun resolveSnippetMention(mention: String, snippetManager: SnippetManager): ResolvedMention {
        val name = mention.removePrefix("@snippet:").trim()
        val snippet = snippetManager.snippets.firstOrNull { it.title.equals(name, ignoreCase = true) }
        return if (snippet != null) {
            ResolvedMention(mention, MentionType.SNIPPET, snippet.command, "snippet: $name")
        } else {
            ResolvedMention(mention, MentionType.SNIPPET, "Snippet not found: $name", mention)
        }
    }

    /** Hapus mentions dari text (untuk display user message yang clean).
     * Phase 40 fix (M12): Pakai regex yang sama dengan parseMentions untuk konsistensi.
     * OLD BUG: Regex [^\\s]+ tidak handle quoted values → @command:"ls -la" hanya
     * strip @command:"ls, sisanya " -la" tetap di text. */
    fun stripMentions(text: String): String {
        return Regex("@(file|block|command|terminal|snippet)(?::(?:\"([^\"]+)\"|(\\S+)))?").replace(text, "").trim()
    }
}

/**
 * MentionAutoComplete - Suggest mentions saat user type @ di chat input.
 *
 * Phase 23: Auto-complete @ mentions.
 */
object MentionAutoComplete {
    /** Get suggestions berdasarkan partial mention text. */
    fun getSuggestions(
        partial: String,
        blockManager: BlockManager,
        snippetManager: SnippetManager,
        recentFiles: List<String> = emptyList()
    ): List<String> {
        if (!partial.startsWith("@")) return emptyList()
        val lower = partial.lowercase()

        val suggestions = mutableListOf<String>()

        /* Static suggestions. */
        val staticSuggestions = listOf("@terminal", "@file:", "@block:", "@command:", "@snippet:")
        staticSuggestions.forEach { s ->
            if (s.startsWith(lower) || lower.length <= 2) suggestions.add(s)
        }

        /* File suggestions (recent files). */
        if (lower.startsWith("@file:")) {
            recentFiles.take(5).forEach { path ->
                suggestions.add("@file:$path")
            }
        }

        /* Block suggestions. */
        if (lower.startsWith("@block:")) {
            blockManager.blocks.forEachIndexed { idx, block ->
                suggestions.add("@block:${idx + 1}  # ${block.command.take(30)}")
            }
        }

        /* Snippet suggestions. */
        if (lower.startsWith("@snippet:")) {
            snippetManager.snippets.forEach { snippet ->
                suggestions.add("@snippet:${snippet.title}")
            }
        }

        return suggestions.filter { it.startsWith(partial) || partial.length <= 3 }.take(10)
    }
}
