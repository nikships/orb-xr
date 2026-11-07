package com.ghosttyxr.app.terminal.session

import android.util.Log
import com.ghosttyxr.app.qr.PairingPayload
import com.ghosttyxr.app.terminal.ssh.EstablishedShell
import com.ghosttyxr.app.terminal.ssh.SshKey
import com.ghosttyxr.app.terminal.ssh.VerifiedHostAccess
import com.ghosttyxr.app.terminal.ssh.withShell

private const val TAG = "AgentSessionProbe"

/**
 * Queries the Mac over a short-lived SSH shell for authoritative agent session
 * metadata. The probe identifies the process running in each named tmux pane,
 * then reads that provider's local transcript index. Nothing is inferred from
 * terminal titles and no command is injected into the user's pane.
 */
class AgentSessionProbe(
    private val hostAccess: VerifiedHostAccess,
) {
    fun probe(
        payload: PairingPayload,
        key: SshKey?,
        panes: Map<Long, String>,
    ): Map<Long, AgentResume> {
        if (panes.isEmpty()) return emptyMap()
        return runCatching {
            hostAccess.withShell(
                host = payload.host,
                port = payload.port,
                username = payload.user,
                expectedFingerprint = payload.fingerprint,
                password = payload.token,
                key = key,
            ) { shell ->
                shell.write(buildCommand(panes).encodeToByteArray())
                readResult(shell)
            }
        }.onFailure {
            Log.w(TAG, "agent metadata probe failed: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun readResult(shell: EstablishedShell): Map<Long, AgentResume> {
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val chunk = shell.read() ?: break
            if (chunk.isEmpty()) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            output.append(chunk.toString(Charsets.UTF_8))
            if (output.contains(DONE_MARKER)) break
        }
        return parseOutput(output.toString())
    }

    companion object {
        internal const val RESULT_MARKER = "__GXR_AGENT_SESSION__"
        internal const val DONE_MARKER = "__GXR_AGENT_PROBE_DONE__"
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val IDLE_POLL_MS = 16L

        internal fun buildCommand(panes: Map<Long, String>): String {
            val paneLiteral = panes.entries.joinToString(",") { (paneKey, sessionName) ->
                require(sessionName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                    "invalid host session name"
                }
                "'$sessionName':$paneKey"
            }
            return """
                stty -echo 2>/dev/null
                python3 - <<'__GXR_PY__'
                import glob
                import heapq
                import json
                import os
                import re
                import subprocess

                PANES = {$paneLiteral}
                MARKER = "$RESULT_MARKER"
                MAX_FILES = 300

                def normalized(path):
                    return os.path.realpath(os.path.expanduser(path or ""))

                def recent_files(pattern):
                    candidates = glob.iglob(os.path.expanduser(pattern), recursive=True)
                    ranked = []
                    for path in candidates:
                        try:
                            ranked.append((os.path.getmtime(path), path))
                        except OSError:
                            pass
                    return [path for _, path in heapq.nlargest(MAX_FILES, ranked)]

                def process_tree_agent(pane_pid, pane_command):
                    commands = [(0, pane_command)]
                    try:
                        rows = subprocess.check_output(
                            ["ps", "-axo", "ppid=,pid=,command="],
                            text=True,
                            stderr=subprocess.DEVNULL,
                        ).splitlines()
                        children = {}
                        command_by_pid = {}
                        for row in rows:
                            match = re.match(r"\s*(\d+)\s+(\d+)\s+(.*)", row)
                            if not match:
                                continue
                            ppid, pid, command = match.groups()
                            children.setdefault(int(ppid), []).append(int(pid))
                            command_by_pid[int(pid)] = command
                        pending = [(int(pane_pid), 0)]
                        seen = set()
                        while pending:
                            pid, depth = pending.pop(0)
                            if pid in seen:
                                continue
                            seen.add(pid)
                            commands.append((depth, command_by_pid.get(pid, "")))
                            pending.extend((child, depth + 1) for child in children.get(pid, ()))
                    except Exception:
                        pass
                    for _, command in sorted(commands):
                        lowered = command.lower()
                        for agent in ("claude", "codex", "droid", "grok"):
                            if re.search(r"(^|[/\s])" + agent + r"([\s]|${'$'})", lowered):
                                return agent
                    return None

                def descendant_pids(root_pid):
                    pids = set()
                    try:
                        rows = subprocess.check_output(
                            ["ps", "-axo", "ppid=,pid="],
                            text=True,
                            stderr=subprocess.DEVNULL,
                        ).splitlines()
                        children = {}
                        for row in rows:
                            match = re.match(r"\s*(\d+)\s+(\d+)", row)
                            if not match:
                                continue
                            ppid, pid = int(match.group(1)), int(match.group(2))
                            children.setdefault(ppid, []).append(pid)
                        pending = [int(root_pid)]
                        while pending:
                            pid = pending.pop()
                            if pid in pids:
                                continue
                            pids.add(pid)
                            pending.extend(children.get(pid, ()))
                    except Exception:
                        pids.add(int(root_pid))
                    return pids

                def claude_session(cwd, pane_pid):
                    for path in recent_files("~/.claude/projects/**/*.jsonl"):
                        try:
                            with open(path, encoding="utf-8", errors="replace") as handle:
                                for _, line in zip(range(80), handle):
                                    record = json.loads(line)
                                    if normalized(record.get("cwd")) != cwd:
                                        continue
                                    session_id = record.get("sessionId") or record.get("session_id")
                                    if session_id:
                                        return str(session_id)
                        except Exception:
                            pass
                    return None

                def codex_session(cwd, pane_pid):
                    for path in recent_files("~/.codex/sessions/**/*.jsonl"):
                        try:
                            with open(path, encoding="utf-8", errors="replace") as handle:
                                for _, line in zip(range(40), handle):
                                    record = json.loads(line)
                                    payload = record.get("payload") or {}
                                    if record.get("type") != "session_meta":
                                        continue
                                    if normalized(payload.get("cwd")) == cwd and payload.get("id"):
                                        return str(payload["id"])
                        except Exception:
                            pass
                    return None

                def droid_session(cwd, pane_pid):
                    try:
                        with open(os.path.expanduser("~/.factory/sessions-index.json"), encoding="utf-8") as handle:
                            entries = json.load(handle).get("entries", [])
                        matches = [
                            entry for entry in entries
                            if normalized(entry.get("cwd")) == cwd and entry.get("sessionId")
                        ]
                        if matches:
                            return str(max(matches, key=lambda entry: entry.get("mtime", 0))["sessionId"])
                    except Exception:
                        pass
                    return None

                def grok_session(cwd, pane_pid):
                    # Prefer live sessions whose pid is under this pane (multi-pane safe).
                    try:
                        with open(os.path.expanduser("~/.grok/active_sessions.json"), encoding="utf-8") as handle:
                            entries = json.load(handle)
                        if not isinstance(entries, list):
                            entries = []
                        tree = descendant_pids(pane_pid)
                        by_pid = [
                            entry for entry in entries
                            if entry.get("session_id") and int(entry.get("pid", -1)) in tree
                        ]
                        if by_pid:
                            return str(max(by_pid, key=lambda e: e.get("opened_at") or "")["session_id"])
                        by_cwd = [
                            entry for entry in entries
                            if entry.get("session_id") and normalized(entry.get("cwd")) == cwd
                        ]
                        if by_cwd:
                            return str(max(by_cwd, key=lambda e: e.get("opened_at") or "")["session_id"])
                    except Exception:
                        pass
                    # Fall back to on-disk session summaries for this cwd.
                    try:
                        from urllib.parse import quote
                        encoded = quote(cwd, safe="")
                        base = os.path.expanduser(os.path.join("~/.grok/sessions", encoded))
                        best_id, best_key = None, ""
                        if os.path.isdir(base):
                            for name in os.listdir(base):
                                summary = os.path.join(base, name, "summary.json")
                                if not os.path.isfile(summary):
                                    continue
                                try:
                                    with open(summary, encoding="utf-8") as handle:
                                        data = json.load(handle)
                                    info = data.get("info") or {}
                                    if normalized(info.get("cwd")) != cwd:
                                        continue
                                    sid = info.get("id") or name
                                    key = data.get("updated_at") or data.get("created_at") or ""
                                    if sid and key >= best_key:
                                        best_id, best_key = str(sid), key
                                except Exception:
                                    pass
                        if best_id:
                            return best_id
                    except Exception:
                        pass
                    return None

                finders = {
                    "claude": claude_session,
                    "codex": codex_session,
                    "droid": droid_session,
                    "grok": grok_session,
                }
                for session_name, pane_key in PANES.items():
                    try:
                        details = subprocess.check_output(
                            [
                                "tmux", "-L", "${HostSessionBootstrap.TMUX_SOCKET}",
                                "display-message", "-p", "-t", session_name,
                                "#{pane_pid}\t#{pane_current_command}\t#{pane_current_path}",
                            ],
                            text=True,
                            stderr=subprocess.DEVNULL,
                        ).strip().split("\t", 2)
                        if len(details) != 3:
                            continue
                        pane_pid, pane_command, cwd = details
                        agent = process_tree_agent(pane_pid, pane_command)
                        session_id = finders[agent](normalized(cwd), int(pane_pid)) if agent else None
                        if session_id and re.fullmatch(r"[A-Za-z0-9._-]{1,128}", session_id):
                            print(f"{MARKER}\t{pane_key}\t{agent}\t{session_id}")
                    except Exception:
                        pass
                print("$DONE_MARKER")
                __GXR_PY__
                stty echo 2>/dev/null
            """.trimIndent() + "\n"
        }

        internal fun parseOutput(output: String): Map<Long, AgentResume> {
            return output.lineSequence().mapNotNull { line ->
                val fields = line.trim().split('\t')
                if (fields.size != 4 || fields[0] != RESULT_MARKER) return@mapNotNull null
                val paneKey = fields[1].toLongOrNull() ?: return@mapNotNull null
                val kind = AGENT_KIND_BY_NAME[fields[2]] ?: return@mapNotNull null
                runCatching { paneKey to AgentResume(kind, fields[3]) }.getOrNull()
            }.toMap()
        }

        private val AGENT_KIND_BY_NAME = mapOf(
            "claude" to AgentResume.Kind.Claude,
            "codex" to AgentResume.Kind.Codex,
            "droid" to AgentResume.Kind.Droid,
            "grok" to AgentResume.Kind.Grok,
        )
    }
}
