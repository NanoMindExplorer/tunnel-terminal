# Tunnel Terminal — 2026 AI Terminal Competitive Analysis & Phase 22 Roadmap

**Author:** Research sub-agent
**Date:** 2026-07-02
**Purpose:** Define the next phase of Tunnel Terminal (currently v4.0.0 / Phase 21) against the 2025-2026 AI-native terminal & AI coding agent landscape.

---

## 0. Executive Summary

The 2025-2026 terminal/editor market has converged on a single paradigm: **agentic AI with structured tool use, diff-review safety nets, and project context awareness**. Every serious competitor (Claude Code, Codex CLI, Cursor, Amazon Q/Kiro CLI, Cline, Aider, Warp) now ships most of the same core features. The differentiators have shifted from "does it have AI?" to:

1. **Safety model** — can the user review/revert AI changes? (checkpointing + diff review)
2. **Context depth** — does the AI know your git state, file tree, dependencies?
3. **Tool-use protocol** — is the agent using structured function-calling or fragile text parsing?
4. **UX paradigm** — blocks (Warp), palettes, streaming reasoning, multi-file diffs.

**Crucially: NOT A SINGLE COMPETITOR HAS AN ANDROID APP.** Warp, Wave, Cursor, Zed, Claude Code, Codex CLI, Aider, Cline, Goose, Continue — all desktop-only (some have web/cloud variants, none are touch-first mobile). The only mobile-adjacent entries are generic SSH clients (Termius, JuiceSSH) plus two nascent AI-SSH apps ("Chaterm" and "Mobile Terminal - SSH & AI CLI" on iOS). **Tunnel Terminal's edge is structural: it is the only mobile-first, real-PTY, multi-provider AI terminal in the market.**

Phase 22 should therefore prioritize closing the **safety + tooling gap** (so we match the 2026 desktop paradigm) rather than chasing novelty. Recommended scope: 5 features — **structured tool use, diff-review workflow, checkpointing, mobile command palette, project-context awareness**. Voice, MCP servers, and block navigation are deferred to Phase 23+.

---

## 1. Competitive Matrix

Legend: ● = native / first-class · ◐ = partial / via extension · ○ = absent · ✗ = deprecated

| Product | UX Paradigm | Agent file edits | Runs commands | Diff review | Checkpoint/Undo | Project context | MCP | Mobile app |
|---|---|---|---|---|---|---|---|---|
| **Warp** | Blocks + Cmd-Palette + Agent Mode (Oz) | ● (Code Review panel, per-file, inline comments) | ● | ● | ◐ (via git) | ● | ◐ | ✗ |
| **Wave Terminal** | Blocks + embedded webviews + Wave AI sidebar | ● (file ops) | ● | ◐ | ◐ | ● (reads terminal output, widgets) | ◐ | ✗ |
| **Cursor** | Chat sidebar + Agent mode + Composer + Debug Mode | ● (multi-file, apply patches) | ● (run in agent) | ● | ● (built-in) | ● (codebase index) | ● | ✗ |
| **Fig** (AWS) | Autocomplete + scripts | ✗ | ✗ | ✗ | ✗ | ◐ | ✗ | ✗ — **SUNSET Sep 2024** |
| **Zed** | Inline AI Assistant + Agentic mode + collaborative | ● (multi-file, edit history visible) | ● | ● | ● (edit history + thinking visible) | ● (native git) | ● | ✗ |
| **GitHub Copilot CLI** | `gh copilot suggest` / `explain` (one-shot NL) | ○ | ○ (suggests only, doesn't execute) | ○ | ○ | ○ | ○ | ✗ |
| **Amazon Q Dev CLI → Kiro CLI** (Nov 2025) | NL chat → agentic shell | ● (reads/writes files, code diffs) | ● | ◐ | ◐ | ● (CLI env context, AWS resources) | ◐ | ✗ |
| **Aider** | Chat in terminal, git-integrated | ● (diff-edit format, token-efficient) | ◐ (runs commands) | ● (uses git diff) | ● (git auto-commit + undo) | ● (repo map) | ◐ | ✗ |
| **Claude Code** | Agentic CLI loop: read file → run bash → iterate | ● (file tools) | ● (persistent bash tool) | ● | ● (V2.0 checkpoints, edits only) | ● (codebase) | ● | ✗ |
| **OpenAI Codex CLI** | Agentic CLI + cloud Codex | ● (multi-file) | ● | ● (granular accept/reject, community-driven) | ◐ (session telemetry/replay) | ● (repo) | ◐ | ✗ |
| **Continue.dev** | Chat + Autocomplete + Edit (CLI / VS Code / JetBrains) | ● | ● | ● | ◐ | ● (codebase) | ● | ✗ — **acquired by Cursor** |
| **Goose** (Block) | Chat UI + CLI, general-purpose agent | ● | ● | ◐ | ◐ | ◐ (extensible) | ● (first-class) | ✗ |
| **Tabby** | Self-hosted code completion + codebase chat | ◐ (chat suggestions) | ○ | ○ | ○ | ● (codebase index) | ○ | ✗ |
| **Cline / Roo Code** | VS Code agent — Plan/Act modes | ● (read/write files directly) | ● (terminal) | ● | ● (checkpoints) | ● | ● (first-class) | ✗ |
| **MCP** (Anthropic) | Protocol, not a product | — | — | — | — | — | ● (the standard itself) | — |
| **Tunnel Terminal** *(current, Phase 21)* | Auto-Pilot sequential + chat sidebar + streaming | ◐ (via `open <file>` heuristic) | ● (real PTY) | ○ | ○ | ◐ (workspace sessions, no git) | ○ | **● ← THE EDGE** |

**Key takeaways from the matrix:**
- The "modern 2026" feature cluster is: **tool-use agent + diff review + checkpointing + project context + MCP**. Cursor, Claude Code, Cline, Zed hit all five. Tunnel Terminal hits zero of five natively.
- Mobile presence is a **green field**. Zero competitors.
- Aider's "git auto-commit per AI change" is a brilliant, lightweight checkpointing pattern perfectly suited to mobile (no extra infrastructure).
- Continue.dev being acquired by Cursor (2025) signals consolidation — open-source agentic infra is being absorbed by commercial IDEs. Tunnel Terminal stays relevant by owning the mobile niche.

---

## 2. Per-Product Deep Dive (what makes each genuinely modern)

### Warp (warp.dev) — the UX pioneer
- **Blocks** are the headline feature: every command+output is a collapsible, navigable, AI-queryable unit. Cursor moves between blocks like an IDE document. This is the single biggest terminal UX rethink in 20 years.
- **Command palette (Cmd+P)** for fuzzy-finding past commands, saved workflows, AI actions.
- **IDE-style input** — multi-line editor at bottom, not single-line readline.
- Now an **"agentic development environment"**: runs Claude Code, Codex, Gemini CLI, and its own "Oz" agent with fine-grained control, locally or cloud.
- **Code Review panel**: review agent diffs by file, leave inline comments — proper PR-grade review inside the terminal.
- *Modern because:* it treats terminal history as a navigable document, not a scrollback buffer.

### Wave Terminal (waveterm.dev) — the embedded-everything terminal
- **Embedded webviews**: render markdown, images, SVG, web pages inline next to terminal output.
- **Wave AI**: context-aware — reads your terminal output, analyzes widgets, performs file operations.
- **Durable SSH sessions** with seamless restore + searchable universal history across all commands.
- **Secret management** built in (v12.3, Nov 2025).
- Open source, privacy-first.
- *Modern because:* it dissolves the boundary between terminal, browser, and editor.

### Cursor (cursor.com) — the agentic IDE benchmark
- **Agent mode is the default** (since 2.0). Multi-file edits in one turn, apply patches.
- **Multiple agents in parallel** (Cursor 2.0).
- **Debug Mode** (2.2, Dec 2025): autonomous bug fixing with runtime instrumentation.
- **Visual Editor** for direct manipulation of agent output.
- **Codebase indexing** for deep project context.
- Acquired Continue.dev (open-source consolidation).
- *Modern because:* it made "review-then-approve agent edits" the default workflow, not a power-user toggle.

### Fig (fig.io) — deprecated, lessons only
- Sunset Sep 1, 2024 → migrated users to Amazon Q Developer CLI (free tier).
- Was the original terminal-autocomplete pioneer; its visual autocomplete pattern is now table stakes.
- *Lesson:* autocomplete alone isn't a moat. The moat is agentic workflow.

### Zed (zed.dev) — speed + collaboration
- Rust + GPU-accelerated; "AI coding at native speed."
- **Native agentic editing**: fluent human-AI collaboration; tracks changes and exposes edit history + AI's thinking process to the user.
- **Real-time collaborative editing** (multiplayer cursors) — serendipitous foundation for agentic transparency.
- Native Git support, multi-file edits.
- *Modern because:* it makes the AI's reasoning visible as a first-class editing surface.

### GitHub Copilot CLI (`gh copilot`)
- Two verbs: `suggest` (NL → command) and `explain` (command → NL).
- Chat-like interface in terminal.
- **Not agentic**: does not execute, does not edit files, no tool use.
- *Modern because:* it normalized NL→shell, but it's now the floor, not the ceiling.

### Amazon Q Developer CLI → Kiro CLI (Nov 17, 2025)
- Agentic CLI: NL conversation → multi-step tasks. Reads/writes files locally, generates code diffs, runs commands.
- Multi-turn conversations; queries AWS resources.
- **Now closed-source as "Kiro CLI"** (the Fig lineage continues but locked down).
- *Modern because:* it proved a CLI-only agentic experience (no editor) is viable — validates Tunnel Terminal's pure-terminal thesis.

### Aider (aider.chat) — the git-native pattern
- Terminal-based, **git-integrated**: auto-commits every AI change with a sensible message.
- Undo AI = `git revert`. Manage AI changes = familiar git diff/branch tools.
- **Diff-edit format** (only sends diffs, not whole files) — saves tokens vs Cline-style full-file replacement.
- Repo map for codebase context.
- *Modern because:* it uses git AS the checkpointing system. Zero infra. Perfect mobile pattern.

### Claude Code (Anthropic) — the agentic loop archetype
- "A loop that lets an AI read files, run commands, and iterate until a task is done" — the canonical agent definition.
- **Tool use**: bash tool (persistent session), file tools.
- **Checkpointing** (V2.0): auto-tracks Claude's file edits; rewind to any prior state. Applies to Claude's edits only (not user edits or bash commands) — used in combination with version control.
- Integrates with dev tools; MCP-native.
- *Modern because:* it's the reference implementation of "tool-use agent + checkpointing" that everyone else benchmarks against.

### OpenAI Codex CLI → OpenAI Agent
- Launched April 16, 2025. Agentic coding in terminal.
- Reads repo, edits files, runs commands.
- Cloud Codex (autonomous, end-to-end) + local CLI.
- Telemetry for session replay/inspection.
- Community-driven push for **granular accept/reject diff workflow**.
- *Modern because:* it proved cloud-delegated autonomous coding tasks are real (not just local agents).

### Continue.dev — acquired by Cursor
- Open-source coding agent: CLI + VS Code + JetBrains plugins.
- Model-agnostic (GPT-4, Claude, Mistral, local LLMs), deployable anywhere.
- Mission: "developers amplified, not automated."
- **Acquired by Cursor (2025)** — open-source agentic infra consolidating into commercial IDEs.
- *Modern because:* it was the canonical "bring-your-own-model" agent; its acquisition signals the BYO-model pattern is now expected by devs.

### Goose (Block) — beyond-code general agent
- Open source, general-purpose (research, writing, automation, data analysis — not just code).
- GUI chat + CLI. Model-agnostic.
- **Extensible via MCP and custom tools** — first-class MCP adoption.
- *Modern because:* it shows the agent pattern generalizes beyond coding; MCP is the extensibility primitive.

### Tabby (tabbyml.com) — self-hosted enterprise
- Self-hosted code completion + codebase chat.
- Self-contained (no DBMS or cloud service needed).
- Team/enterprise focus (SSO, access control).
- OpenAPI interface for integration.
- *Modern because:* it's the privacy/on-prem answer to Copilot — relevant for Tunnel Terminal's local-Ollama user base.

### Cline / Roo Code — autonomous VS Code agents
- **Cline**: Plan/Act modes, MCP integration, terminal-first workflows. 8M+ developers, 5M+ VS Code installs (most-installed open-source coding agent). Plan-then-Act architecture.
- **Roo Code**: speed, automation, multi-agent workflows, custom modes for different tasks.
- Both: read/write files directly, NL communication, checkpoints.
- *Modern because:* Plan/Act separation is the cleanest answer to "don't just do — plan first, then act" — directly applicable to mobile where screen real estate forces clarity.

### MCP (Model Context Protocol) — the standard
- Open Anthropic standard for secure two-way connections between data sources and AI tools.
- Reference servers: filesystem, git, slack, postgres, browser, etc.
- TypeScript + Python SDKs.
- **Adopted by**: Claude Code, Cursor, Cline, Goose, Continue, Zed, and others. It's the lingua franca.
- *Modern because:* one protocol = any tool. Without MCP support, an agent is sandboxed; with it, the agent is extensible by the user.

---

## 3. 2025-2026 AI Agent Concepts Analysis

| Concept | What it is | Where it's proven | Mobile-feasibility | Priority for Tunnel |
|---|---|---|---|---|
| **Agent blocks** | Each cmd+output is a navigable/AI-queryable unit | Warp | ◐ — adapt as scrollable timeline, tap-to-expand | Medium — Phase 24+ |
| **Command palette** | Cmd+K fuzzy search: commands, AI actions, files | Warp, Cursor, Zed, VS Code | ◐ — replace Cmd+K with bottom-sheet FAB | **High — Phase 22** |
| **Inline AI suggestions** | Copilot-for-shell: suggest next cmd from context | Warp (autocomplete), Tabby | ● — one-tap accept; perfect for touch | Medium — Phase 23 |
| **Diff review workflow** | AI proposes edits → user reviews unified diff → accept/reject per hunk | Cursor, Cline, Aider, Codex, Claude Code | ● — full-screen vertical diff, swipe between hunks | **High — Phase 22** |
| **MCP server support** | Connect filesystem/git/slack/etc via standard protocol | Claude Code, Cline, Goose, Cursor | ◐ — mobile should connect to remote MCP via HTTP/SSE only | Medium — Phase 24 |
| **Tool use / function calling** | AI calls structured tools: read_file, write_file, run_bash, search_web | Claude Code, Codex, Cline (all) | ● — works as-is | **High — Phase 22** |
| **Streaming agent reasoning** | Show AI's "thinking" steps as it plans | Zed, Claude Code, Cursor | ● — compact stream, perfect for mobile | Medium — Phase 23 |
| **Multi-file edits** | AI edits multiple files in one turn | Cursor, Cline, Codex, Zed | ◐ — show one file diff at a time, swipe | High — Phase 23 |
| **Checkpointing / undo** | Snapshot before AI changes; revert | Claude Code, Cline, Replit, VS Code | ● — critical on mobile (no Ctrl+Z) | **High — Phase 22** |
| **Project context** | AI knows package.json, git status, recent files | Cursor, Aider, Claude Code | ● — already have workspaces; add git/detect | **High — Phase 22** |
| **Voice input** | Whisper-to-command | (no major terminal ships this) | ● — huge mobile advantage; typing is painful | Medium — Phase 23 |
| **Cross-session memory** | Vector DB of past commands/conversations | (mostly absent; some experimental) | ◐ — feasible with on-device embeddings | Low — Phase 25+ |

---

## 4. Top 10 Features That Define a "Modern 2026 AI Terminal" (ranked by impact)

| Rank | Feature | Why it defines "modern" in 2026 | Mobile adaptation |
|---:|---|---|---|
| 1 | **Structured tool use / function calling** | Replaces fragile text parsing with reliable tool dispatch. Every serious agent (Claude Code, Codex, Cline, Cursor) uses it. Without this, you have autocomplete, not an agent. | Works as-is. JSON tool-call schema is platform-agnostic. |
| 2 | **Diff-review workflow** | The safety primitive that makes agentic edits trustworthy. User sees exactly what changed before it lands. Cursor/Cline/Aider all converge here. | Full-screen vertical diff modal; per-hunk accept/reject buttons; swipe between files. |
| 3 | **Checkpointing / undo** | Reversibility is what separates a tool from a footgun. Claude Code V2.0, Cline, Replit, VS Code all ship it. On mobile (no Ctrl+Z) it's even more critical. | One-tap "rewind to before this AI turn"; timeline UI; reuse Aider's git-commit-per-change pattern. |
| 4 | **Project context awareness** | An AI that doesn't know your git state/package.json/recent files is glorified chat. Cursor's codebase index, Aider's repo map, Claude Code's codebase read — all prove this. | Auto-detect git repo in cwd, parse package.json/manifest, inject file tree into system prompt. Already have workspace sessions — extend them. |
| 5 | **Mobile command palette** | The 2026 UX entry point for "anything is one search away." Warp/Cursor/VS Code/Zed all live or die by it. | Replace Cmd+K with a thumb-reachable bottom sheet triggered by FAB or swipe-up gesture. Fuzzy search commands, AI actions, files, recent commands, snippets. |
| 6 | **Streaming agent reasoning** | Shows the AI's plan/thinking live — turns a black box into a transparent collaborator. Zed and Claude Code lead here. | Compact reasoning stream above the action; auto-collapse when action starts. |
| 7 | **MCP server support** | The extensibility standard. Without it, your agent is a closed box. With it, the user connects filesystem/git/slack/browser/postgres. | Connect to remote MCP servers via HTTP/SSE; ship 2-3 built-in adapters (filesystem, git, web-search). |
| 8 | **Agent blocks / navigable history** | Warp's signature: history as a document, not a scrollback. AI can query specific past outputs. | Vertical timeline of (cmd, output) cards; tap to expand/collapse; long-press to send to AI as context. |
| 9 | **Multi-file edits in one agent turn** | Cursor/Cline/Codex all do it. Real refactors span files. Mobile shows one diff at a time with swipe. | One-file-at-a-time diff viewer with file counter ("2 of 5"); batch accept-all option. |
| 10 | **Voice input (whisper-to-command)** | No major terminal ships this. On mobile, it's a category-defining differentiator — typing shell on a phone is painful. | Tap mic → Whisper STT → fill AI input; one-tap send. Works offline with on-device Whisper.cpp. |

---

## 5. Mobile-Specific Considerations (per feature)

### What makes sense on mobile (small screen, touch)

| Feature | Mobile adaptation |
|---|---|
| Voice input | **Huge advantage.** Mic FAB → Whisper → command. No competitor does this. |
| Diff review | Full-screen modal, vertical scroll, per-hunk Accept/Reject chips, swipe-between-files. Better than desktop for review (focused). |
| Checkpointing | Critical — no easy undo on touch. One-tap "rewind" button. Use Aider's git-commit-per-AI-change pattern. |
| Command palette | Bottom sheet (Material 3 `ModalBottomSheet`), one-thumb reachable. Replace Cmd+K with FAB or swipe-up. |
| Streaming reasoning | Compact collapsible "thinking…" card above the action. Tap to expand. |
| Project context | Auto-detect on session start. No UX cost — just better AI. |
| Tool use | Invisible to user — pure protocol upgrade. Zero mobile UX cost. |
| Block navigation | Vertical timeline cards. Long-press = "send this block to AI." Already half-built (volume-key history). |
| Inline suggestions | One-tap "accept" chip below input line. No keyboard needed. |

### What should be skipped or heavily adapted

| Feature | Why it's weak on mobile |
|---|---|
| Cmd+K / Ctrl+K trigger | No Cmd/Ctrl key on Android. Use FAB / gesture / long-press instead. |
| Multi-pane split view | Already have it (Phase 21) but phone screens too small for >2 panes. Keep 2-pane max; default to 1. |
| Local MCP server hosting | Too infra-heavy for a phone. Connect to remote MCP servers only. |
| Codebase-wide vector index | Battery + storage cost. Defer; instead inject file tree + git diff as text context. |
| Real-time collaborative editing (Zed-style) | Not a mobile use case. Skip entirely. |
| Embedded webviews (Wave-style) | Possible but bloated. Skip; render markdown/images inline only. |

---

## 6. Tunnel Terminal — Current State Assessment (Phase 21 baseline)

**Already strong (matches or beats desktop competitors):**
- ✅ Real C++ NDK PTY engine (not `Runtime.exec()`) — only mobile terminal with this
- ✅ AI Auto-Pilot agentic loop (sequential command execution with prompt detection)
- ✅ Streaming SSE responses, token-by-token
- ✅ Multi-turn memory (max 20 messages)
- ✅ Multi-provider (OpenAI, DeepSeek, Groq, OpenRouter, Gemini, Claude, Ollama, LM Studio) — best-in-class
- ✅ AI image vision (multi-modal messages)
- ✅ SSH client (JSch) + local PTY in unified tab bar
- ✅ File explorer drawer + workspace sessions
- ✅ Syntax highlighting (regex, 8 languages)
- ✅ Split pane (tmux-style)
- ✅ Storage Access Framework bridge
- ✅ Theme picker, syntax themes
- ✅ **Mobile-first UX** (volume-key history, pinch-zoom, smart modifier keys, extra keys bar)
- ✅ **Android app exists** — zero competitors have this

**Gaps vs 2026 paradigm (what Phase 22+ must close):**
- ❌ **No structured tool use / function calling** — currently uses fragile ` ```bash ` block parsing heuristic
- ❌ **No diff review workflow** — AI file edits go straight in via `open <file>` heuristic, no review
- ❌ **No checkpointing / undo** — once AI edits, no rewind (only manual git if user committed)
- ❌ **No project context** — AI doesn't know git state, package.json, or file tree (only terminal scrollback)
- ❌ **No command palette** — no fuzzy search across commands/actions/files
- ❌ **No MCP support**
- ❌ **No streaming reasoning display** — reasoning hidden inside response text
- ❌ **No voice input** — missed mobile-defining opportunity
- ❌ **No agent blocks / navigable history** — output is a flat scrollback

---

## 7. Recommended Phase 22 Scope

**Theme:** "From Auto-Pilot to Agent — close the safety + tooling gap with the 2026 desktop paradigm, mobile-first."

### Phase 22 = 5 features (ranked by dependency / impact)

#### 22.1 — Structured Tool Use / Function Calling (foundation) ★ must-have
**Why:** The current ` ```bash ` block parser is the single biggest fragility in the app. It breaks on markdown nesting, non-bash langs, multi-line commands, and any model that wraps commands in prose. Every modern agent uses structured tool calls.

**Implementation:**
- Add `tools` array to the OpenAI-compatible request body (works with OpenAI, DeepSeek, Groq, OpenRouter, Gemini-via-compat, Claude-via-compat, Ollama with `llama3.1+`).
- Define 4 core tools: `run_command(cmd: string)`, `read_file(path: string)`, `write_file(path: string, content: string)`, `search_web(query: string)` (optional).
- Add `tool_choice: "auto"`.
- Parse `tool_calls` from streaming response (deltas); dispatch to tool executor; return `tool` role messages; loop.
- Keep Auto-Pilot's prompt-detection (wait for shell prompt) and per-step reporting — that's already good.
- **Fallback:** if model doesn't support tool-calling (rare), fall back to current ` ```bash ` parsing. Don't break existing users.

**Files:** `AIAgent.kt` (major rewrite of `buildRequestBody` + new tool-dispatch loop), new `ToolExecutor.kt`.

#### 22.2 — Diff Review Workflow (safety net) ★ must-have
**Why:** With tool use (22.1), the AI will call `write_file`. Users must see what's about to change before it lands. On mobile there's no Ctrl+Z — review-before-apply is the only safe model. This is the Cursor/Cline/Aider convergence point.

**Implementation:**
- When `write_file` tool is called, intercept: don't write yet. Compute unified diff against current file content (or "new file" if absent).
- Show full-screen `DiffReviewSheet` (Material 3): file path header, line-numbered diff (red -, green +), per-hunk Accept/Reject chips, "Accept all" / "Reject all" buttons.
- On Accept: apply edit, return success to AI. On Reject: return "user rejected edit" as tool result; AI adapts.
- For multi-file turns: queue diffs; show file counter ("File 2 of 5"); swipe between.
- Use a tiny unified-diff library (or write one — ~150 lines).
- Reuse `SyntaxHighlighter` (Phase 21) for diff rendering.

**Files:** new `DiffReviewSheet.kt`, new `DiffEngine.kt` (unified diff), modified `ToolExecutor.kt` (intercept write_file), modified `MainActivity.kt` (compose dialog hosting).

#### 22.3 — Checkpointing / Undo (reversibility) ★ must-have
**Why:** On mobile, the cost of an AI mistake is higher (harder to manually fix). Claude Code V2.0, Cline, Replit, VS Code all ship this. Aider's pattern (git auto-commit per AI change) is the lightweight mobile-perfect version.

**Implementation (Aider-pattern, minimal infra):**
- Before each AI `write_file` (post-22.2 accept), snapshot: copy affected file's pre-edit content to `~/.tunnel/checkpoints/<session>/<turn>/<filepath>`.
- Maintain a per-session `checkpoint_manifest.json` mapping turn → list of affected files.
- Add a "Rewind" button in the AI panel: shows timeline of recent AI turns; tap one → restore all files to that pre-state.
- Optional (Phase 22.5): if cwd is a git repo, also `git stash`-style snapshot for non-committed changes.
- No DB, no vector store — just file copies + JSON manifest. Mobile-perfect.

**Files:** new `CheckpointManager.kt`, modified `ToolExecutor.kt` (snapshot before write), new `RewindDialog.kt`.

#### 22.4 — Mobile Command Palette (UX entry point) ★ high-impact
**Why:** The 2026 terminal's front door. Warp/Cursor/Zed/VS Code all converge here. On mobile, Cmd+K → FAB + bottom sheet. This replaces the current `help` command and scattered buttons with one unified search surface.

**Implementation:**
- Add a FAB (or swipe-up from input bar) that opens a `ModalBottomSheet`.
- Fuzzy search across: built-in commands (`help`, `clear`, `setup-storage`, `open`, `system-info`), AI actions ("Ask AI to…", "Explain last output", "Fix last error"), recent commands (per-tab history), saved snippets (already in `SnippetManager`), files in current workspace.
- Ranked results; tap to execute.
- Reuses existing `SnippetManager` and per-tab history.

**Files:** new `CommandPaletteSheet.kt`, modified `TerminalUI.kt` (FAB), modified `MainActivity.kt` (sheet host).

#### 22.5 — Project Context Awareness (AI quality multiplier) ★ high-impact
**Why:** An AI that doesn't know your project is glorified chat. Cursor's codebase index, Aider's repo map, Claude Code's codebase read — all prove this. Tunnel Terminal already has workspace sessions; extend them with project metadata. Zero UX cost, huge AI quality gain.

**Implementation:**
- On session start (or workspace switch), detect: git repo (`.git`), package files (`package.json`, `build.gradle*`, `Cargo.toml`, `pyproject.toml`, `go.mod`, `pom.xml`, `requirements.txt`), file tree (top 2 levels, max 200 entries).
- Inject as a `system` message: "Project context: git branch=main, 3 modified files. Package: Node.js 18, dependencies=express,react. File tree: …"
- For git repos: also inject `git status --short` output and last commit message.
- Cache per-workspace; refresh on demand (command: `refresh-context`).
- Token budget: cap at ~2k tokens for context (tree truncated if larger).

**Files:** new `ProjectContext.kt`, modified `AIAgent.kt` (inject context system message), new built-in command `refresh-context`.

---

### Phase 22 Dependency Graph
```
22.1 Tool Use  ──┬──► 22.2 Diff Review  ──► 22.3 Checkpointing
                 │
                 └──► 22.5 Project Context (independent, but enriched by tool use)

22.4 Command Palette (fully independent — can ship in parallel)
```

**Recommended shipping order:** 22.4 (palette) first (visible win, no AI changes), then 22.1 (tool use) + 22.5 (context) together (AI quality), then 22.2 (diff review) + 22.3 (checkpointing) together (safety). All five = one Phase 22 release.

---

## 8. Deferred to Phase 23+ (and why)

| Feature | Target Phase | Why deferred |
|---|---|---|
| Voice input (Whisper-to-command) | Phase 23 | High mobile-defining value but adds native binary weight (Whisper.cpp ~40MB); ship after Phase 22 stabilizes the agent core. |
| Streaming reasoning display | Phase 23 | Easy add once tool use lands (reasoning is a tool-call field); bundle with agent UX polish. |
| Inline AI suggestions (Copilot-for-shell) | Phase 23 | Requires debounced background calls; UX tuning heavy. |
| Multi-file edits UI polish | Phase 23 | 22.2 already supports it minimally; Phase 23 = swipe + batch-accept polish. |
| MCP server support | Phase 24 | Infra-heavy; ship 2-3 adapters (filesystem, git, web-search). Mobile = remote MCP via HTTP/SSE only. |
| Agent blocks / navigable timeline | Phase 24 | Pure UX rework of scrollback; high value but no agent-core dependency. |
| Cross-session vector memory | Phase 25+ | Battery/storage cost; needs on-device embeddings; lowest ROI. |
| Local MCP server hosting | Phase 25+ | Skip; phones aren't MCP hosts. |

---

## 9. Strategic Positioning

After Phase 22, Tunnel Terminal's positioning statement becomes:

> **"The only mobile-first, real-PTY AI terminal with structured tool use, diff-review safety, and project context — bringing the 2026 Claude Code / Cursor agentic paradigm to your pocket."**

This is defensible because:
1. **No competitor has an Android app.** Warp/Wave/Cursor/Zed are desktop-only. Claude Code/Codex CLI/Aider/Cline are CLI-on-desktop. Termius/JuiceSSH are dumb SSH. Chaterm and "Mobile Terminal - SSH & AI CLI" are early-stage AI-SSH wrappers without real PTY or tool use.
2. **Real PTY engine** is a 6-month moat (C++ NDK + forkpty + SIGWINCH + thread safety — already built in Phases 7-21).
3. **Multi-provider + local Ollama** matches the BYO-model pattern Continue.dev pioneered (now expected in 2026).
4. **Phase 22 closes the safety/tooling gap** with desktop agents — Tunnel Terminal becomes feature-comparable to Claude Code on mobile, which no one else offers.

---

## 10. Sources (web research, 2026-07-02)

- warp.dev — "The Agentic Development Environment"; docs.warp.dev — blocks, Code Review panel, Oz agent
- waveterm.dev + docs.waveterm.dev (v12.3, Nov 17 2025) — Wave AI, durable SSH, secret management
- cursor.com + usama.codes (Cursor 2.2 Dec 2025) — Agent mode default, Debug Mode, Visual Editor, multi-agent
- zed.dev — agentic editing, edit history, native git, GPU-accelerated
- github.com/gh-copilot — `gh copilot suggest` / `explain`
- aws.amazon.com/q-developer + github.com/aws/amazon-q-developer-cli (Nov 17 2025) — now Kiro CLI (closed source)
- aider.chat — git-integrated, diff-edit format, repo map
- code.claude.com + anthropic.com — Claude Code agentic loop, V2.0 checkpointing, bash tool
- openai.com/codex + medium.com/unicodeveloper — Codex CLI launched Apr 16 2025, agentic terminal
- continue.dev + github.com/continuedev/continue — **acquired by Cursor (2025)**
- goose-docs.ai + block.xyz + github.com/aaif-goose/goose — open-source general agent, MCP-first
- tabbyml.com — self-hosted, enterprise (SSO, access control)
- cline.bot + kilo.ai — Plan/Act modes, MCP integration, 8M+ devs, 5M+ VS Code installs
- modelcontextprotocol/servers + anthropic.com — MCP open standard, TS + Python SDKs
- uxpatterns.dev + mobbin.com — command palette UX pattern
- docs.warp.dev (Code Review) + community.openai.com + youtrack.jetbrains.com — diff review workflow convergence
- code.claude.com + kilo.ai + github.com/mohshomis/ckpt + docs.replit.com — checkpointing patterns
- play.google.com (Chaterm) + apps.apple.com (Mobile Terminal - SSH & AI CLI) — only mobile AI-SSH entrants (both early-stage, no real PTY)
- reddit.com/r/TermuxAI — local LLM agent on Android (validates the local-Ollama use case Tunnel Terminal already supports)

---

*End of report. Phase 22 scope is ready for implementation planning.*
