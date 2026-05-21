#!/usr/bin/env python3
"""Textual TUI dashboard for the Site Manager application."""

import sqlite3
import os
import sys
import time
import signal
import subprocess
import threading
import json
import hashlib
import re
import pty
import select
import errno
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

from textual.app import App, ComposeResult
from textual.widgets import (
    Header, Footer, TabbedContent, TabPane, DataTable,
    Static, Input, Label, RichLog, Button, ProgressBar,
)
from textual.containers import Horizontal, Vertical, ScrollableContainer
from textual.screen import Screen, ModalScreen
from textual.reactive import reactive
from textual import on, work
from textual.binding import Binding
from rich.text import Text
from rich.table import Table
from rich import box

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "sitemanager.db")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PID_FILE = "/tmp/site-manager-auto-update.pid"
CLAUDE_LOG_FILE = os.path.join(SCRIPT_DIR, "claude-output.log")
CLAUDE_CREDS_PATH = os.path.expanduser("~/.claude/.credentials.json")

STATUS_COLORS = {
    "MERGED": "green",
    "DEV_COMPLETE": "cyan",
    "FINAL_REVIEW": "yellow",
    "IN_PROGRESS": "blue",
    "PENDING": "white",
    "DENIED": "red",
    "TIMED_OUT": "magenta",
    "DRAFT": "dim white",
}

PRIORITY_COLORS = {
    "HIGH": "red bold",
    "MEDIUM": "yellow",
    "LOW": "dim white",
}

SERVICES = {
    "app": {
        "name": "Spring Boot App (auto-update)",
        "pid_file": PID_FILE,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/auto-update.sh > {SCRIPT_DIR}/app.log 2>&1 & echo $!",
        "grep_pattern": "auto-update.sh",
        "log_file": os.path.join(SCRIPT_DIR, "app.log"),
    },
    "extractor": {
        "name": "Error Extractor",
        "pid_file": None,
        "start_cmd": f"nohup bash {SCRIPT_DIR}/extract-errors.sh > /dev/null 2>&1 & echo $!",
        "grep_pattern": "extract-errors.sh",
        "log_file": os.path.join(SCRIPT_DIR, "error.log"),
    },
}

ERROR_LOG_FILE = os.path.join(SCRIPT_DIR, "error.log")
ERROR_CHECK_INTERVAL = 10  # seconds between error log checks
ERROR_FIX_LOG_FILE = os.path.join(SCRIPT_DIR, "error-fix.log")


@dataclass
class TrackedError:
    error_hash: str
    first_seen: float
    last_seen: float
    count: int
    message: str
    full_text: str
    status: str = "NEW"  # NEW, FIXING, FIXED, FAILED, IGNORED
    fix_attempts: int = 0
    fix_output: str = ""
    claude_pid: Optional[int] = None


FIX_STAGES = [
    ("INIT", "Initializing"),
    ("ANALYZING", "Analyzing error"),
    ("READING", "Reading source files"),
    ("DIAGNOSING", "Diagnosing root cause"),
    ("FIXING", "Applying fix"),
    ("TESTING", "Testing changes"),
    ("COMMITTING", "Committing & pushing"),
    ("DONE", "Fix complete"),
    ("FAILED", "Fix failed"),
]

FIX_STAGE_NAMES = [s[0] for s in FIX_STAGES]
FIX_STAGE_PROGRESS = {name: i / (len(FIX_STAGES) - 1) for i, (name, _) in enumerate(FIX_STAGES)}
FIX_STAGE_LABELS = {name: label for name, label in FIX_STAGES}


@dataclass
class FixSession:
    """Tracks the progress of a single Claude CLI error-fix session."""
    error_hash: str
    error_message: str
    stage: str = "INIT"
    start_time: float = 0.0
    files_read: list = field(default_factory=list)
    files_modified: list = field(default_factory=list)
    tools_used: list = field(default_factory=list)
    tool_call_count: int = 0
    current_action: str = ""
    thinking_snippet: str = ""
    response_snippet: str = ""
    turns: int = 0
    cost: Optional[float] = None
    finished: bool = False

    @property
    def progress(self) -> float:
        return FIX_STAGE_PROGRESS.get(self.stage, 0.0)

    @property
    def elapsed(self) -> float:
        return time.time() - self.start_time if self.start_time else 0.0

    @property
    def stage_label(self) -> str:
        return FIX_STAGE_LABELS.get(self.stage, self.stage)


class ErrorManager:
    """Tracks unique errors from error.log and manages auto-fix sessions."""

    def __init__(self):
        self.errors: dict[str, TrackedError] = {}
        self.last_log_position: int = 0
        self.last_log_inode: int = 0
        self.fix_in_progress: bool = False
        self.total_fixes_attempted: int = 0
        self.total_fixes_succeeded: int = 0
        self.current_fix: Optional[FixSession] = None
        self.fix_history: list[FixSession] = []
        self.lock = threading.RLock()

    def _hash_error(self, message: str) -> str:
        cleaned = re.sub(r'\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}', '', message)
        cleaned = re.sub(r'0x[0-9a-fA-F]+', '', cleaned)
        cleaned = re.sub(r'\b\d{3,}\b', 'N', cleaned)
        return hashlib.sha256(cleaned.strip().encode()).hexdigest()[:16]

    def check_for_new_errors(self) -> list[TrackedError]:
        """Read error.log for new entries since last check."""
        new_errors = []
        if not os.path.exists(ERROR_LOG_FILE):
            return new_errors

        try:
            stat = os.stat(ERROR_LOG_FILE)
            if stat.st_ino != self.last_log_inode:
                self.last_log_position = 0
                self.last_log_inode = stat.st_ino

            if stat.st_size < self.last_log_position:
                self.last_log_position = 0

            with open(ERROR_LOG_FILE, "r") as f:
                f.seek(self.last_log_position)
                new_lines = f.readlines()
                self.last_log_position = f.tell()

            current_block = []
            for line in new_lines:
                stripped = line.strip()
                if not stripped:
                    continue
                if re.match(r'^\d{4}-\d{2}-\d{2}', stripped) and current_block:
                    self._process_error_block(current_block, new_errors)
                    current_block = [stripped]
                else:
                    current_block.append(stripped)

            if current_block:
                self._process_error_block(current_block, new_errors)

        except (OSError, IOError):
            pass

        return new_errors

    def _process_error_block(self, lines: list[str], new_errors: list[TrackedError]):
        full_text = "\n".join(lines)
        first_line = lines[0] if lines else ""
        error_hash = self._hash_error(first_line)
        now = time.time()

        with self.lock:
            if error_hash in self.errors:
                existing = self.errors[error_hash]
                existing.last_seen = now
                existing.count += 1
                if existing.status == "FIXED":
                    existing.status = "NEW"
                    new_errors.append(existing)
            else:
                tracked = TrackedError(
                    error_hash=error_hash,
                    first_seen=now,
                    last_seen=now,
                    count=1,
                    message=first_line[:200],
                    full_text=full_text[:2000],
                )
                self.errors[error_hash] = tracked
                new_errors.append(tracked)

    def get_unfixed_errors(self) -> list[TrackedError]:
        with self.lock:
            return [e for e in self.errors.values() if e.status in ("NEW", "FAILED")]

    def get_all_errors(self) -> list[TrackedError]:
        with self.lock:
            return sorted(self.errors.values(), key=lambda e: e.last_seen, reverse=True)

    def mark_fixing(self, error_hash: str, pid: int):
        with self.lock:
            if error_hash in self.errors:
                self.errors[error_hash].status = "FIXING"
                self.errors[error_hash].fix_attempts += 1
                self.errors[error_hash].claude_pid = pid
                self.fix_in_progress = True
                self.total_fixes_attempted += 1

    def mark_fixed(self, error_hash: str, output: str):
        with self.lock:
            if error_hash in self.errors:
                self.errors[error_hash].status = "FIXED"
                self.errors[error_hash].fix_output = output[:2000]
                self.errors[error_hash].claude_pid = None
            self.fix_in_progress = False
            self.total_fixes_succeeded += 1

    def mark_failed(self, error_hash: str, output: str):
        with self.lock:
            if error_hash in self.errors:
                self.errors[error_hash].status = "FAILED"
                self.errors[error_hash].fix_output = output[:2000]
                self.errors[error_hash].claude_pid = None
            self.fix_in_progress = False

    def mark_ignored(self, error_hash: str):
        with self.lock:
            if error_hash in self.errors:
                self.errors[error_hash].status = "IGNORED"

    def start_fix_session(self, error_hash: str) -> FixSession:
        with self.lock:
            msg = self.errors[error_hash].message if error_hash in self.errors else ""
            session = FixSession(
                error_hash=error_hash,
                error_message=msg,
                start_time=time.time(),
            )
            self.current_fix = session
            return session

    def finish_fix_session(self, success: bool):
        with self.lock:
            if self.current_fix:
                self.current_fix.finished = True
                self.current_fix.stage = "DONE" if success else "FAILED"
                self.fix_history.append(self.current_fix)
                if len(self.fix_history) > 20:
                    self.fix_history = self.fix_history[-20:]
                self.current_fix = None

    def update_fix_stage(self, stage: str, action: str = ""):
        with self.lock:
            if self.current_fix:
                self.current_fix.stage = stage
                if action:
                    self.current_fix.current_action = action


# ── DB helpers ──────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def ts_to_str(ts):
    if not ts:
        return "N/A"
    try:
        dt = datetime.fromtimestamp(ts / 1000, tz=timezone.utc)
        return dt.strftime("%Y-%m-%d %H:%M")
    except (OSError, ValueError):
        return "N/A"


def time_ago(ts):
    if not ts:
        return "N/A"
    try:
        now = time.time() * 1000
        diff_sec = (now - ts) / 1000
        if diff_sec < 60:
            return f"{int(diff_sec)}s ago"
        elif diff_sec < 3600:
            return f"{int(diff_sec / 60)}m ago"
        elif diff_sec < 86400:
            return f"{int(diff_sec / 3600)}h ago"
        else:
            return f"{int(diff_sec / 86400)}d ago"
    except (OSError, ValueError):
        return "N/A"


def get_settings(conn):
    c = conn.cursor()
    c.execute("SELECT * FROM site_settings LIMIT 1")
    row = c.fetchone()
    if row:
        return dict(row)
    return {"site_name": "Site Manager", "claude_model": "unknown", "target_repo_url": ""}


# ── Claude CLI auth persistence ─────────────────────────────────────

def _ensure_claude_auth_table(conn):
    conn.execute(
        "CREATE TABLE IF NOT EXISTS claude_auth ("
        "  id INTEGER PRIMARY KEY CHECK (id = 1),"
        "  credentials_json TEXT NOT NULL,"
        "  updated_at INTEGER NOT NULL"
        ")"
    )
    conn.commit()


def save_claude_credentials_from_disk() -> bool:
    """Read ~/.claude/.credentials.json and persist it into sqlite."""
    if not os.path.exists(CLAUDE_CREDS_PATH):
        return False
    with open(CLAUDE_CREDS_PATH, "r") as f:
        creds = f.read()
    if not creds.strip():
        return False
    conn = get_db()
    try:
        _ensure_claude_auth_table(conn)
        conn.execute(
            "INSERT OR REPLACE INTO claude_auth (id, credentials_json, updated_at) VALUES (1, ?, ?)",
            (creds, int(time.time() * 1000)),
        )
        conn.commit()
    finally:
        conn.close()
    return True


def restore_claude_credentials_to_disk() -> bool:
    """Write stored credentials back to ~/.claude/.credentials.json if needed."""
    try:
        conn = get_db()
    except sqlite3.Error:
        return False
    try:
        _ensure_claude_auth_table(conn)
        c = conn.cursor()
        c.execute("SELECT credentials_json FROM claude_auth WHERE id = 1")
        row = c.fetchone()
        if not row:
            return False
        creds = row[0]
    finally:
        conn.close()
    existing = None
    try:
        if os.path.exists(CLAUDE_CREDS_PATH):
            with open(CLAUDE_CREDS_PATH, "r") as f:
                existing = f.read()
    except OSError:
        pass
    if existing == creds:
        return True
    os.makedirs(os.path.dirname(CLAUDE_CREDS_PATH), exist_ok=True)
    with open(CLAUDE_CREDS_PATH, "w") as f:
        f.write(creds)
    try:
        os.chmod(CLAUDE_CREDS_PATH, 0o600)
    except OSError:
        pass
    return True


def has_stored_claude_credentials() -> bool:
    try:
        conn = get_db()
    except sqlite3.Error:
        return False
    try:
        _ensure_claude_auth_table(conn)
        c = conn.cursor()
        c.execute("SELECT 1 FROM claude_auth WHERE id = 1")
        return c.fetchone() is not None
    finally:
        conn.close()


# ── Service management ───────────────────────────────────────────────

def get_service_pid(svc_key):
    svc = SERVICES[svc_key]
    if svc["pid_file"] and os.path.exists(svc["pid_file"]):
        try:
            pid = int(open(svc["pid_file"]).read().strip())
            os.kill(pid, 0)
            return pid
        except (ValueError, OSError, ProcessLookupError):
            pass
    try:
        result = subprocess.run(
            ["pgrep", "-f", svc["grep_pattern"]],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            pids = result.stdout.strip().split("\n")
            return int(pids[0]) if pids[0] else None
    except (subprocess.TimeoutExpired, ValueError):
        pass
    return None


def get_service_status(svc_key):
    pid = get_service_pid(svc_key)
    if pid is None:
        return False, None, {}
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "%cpu,%mem,rss,etime", "--no-headers"],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split()
            if len(parts) >= 4:
                return True, pid, {
                    "cpu": parts[0], "mem": parts[1],
                    "rss": parts[2], "uptime": parts[3],
                }
        return True, pid, {}
    except subprocess.TimeoutExpired:
        return True, pid, {}


def start_service(svc_key):
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if running:
        return False, f"{svc['name']} is already running (PID {pid})"
    try:
        for f in [os.path.join(SCRIPT_DIR, "app.log"), os.path.join(SCRIPT_DIR, "error.log")]:
            Path(f).touch(exist_ok=True)
        result = subprocess.run(
            svc["start_cmd"], shell=True, capture_output=True, text=True,
            cwd=SCRIPT_DIR,
        )
        new_pid = result.stdout.strip().split("\n")[-1].strip()
        return True, f"{svc['name']} started (PID {new_pid})"
    except Exception as e:
        return False, f"Failed to start {svc['name']}: {e}"


def stop_service(svc_key):
    svc = SERVICES[svc_key]
    running, pid, _ = get_service_status(svc_key)
    if not running:
        return False, f"{svc['name']} is not running"
    try:
        os.kill(pid, signal.SIGTERM)
        for _ in range(20):
            time.sleep(0.5)
            try:
                os.kill(pid, 0)
            except OSError:
                if svc["pid_file"] and os.path.exists(svc["pid_file"]):
                    os.remove(svc["pid_file"])
                return True, f"{svc['name']} stopped (was PID {pid})"
        os.kill(pid, signal.SIGKILL)
        if svc["pid_file"] and os.path.exists(svc["pid_file"]):
            os.remove(svc["pid_file"])
        return True, f"{svc['name']} force-killed (was PID {pid})"
    except Exception as e:
        return False, f"Failed to stop {svc['name']}: {e}"


def restart_service(svc_key):
    running, _, _ = get_service_status(svc_key)
    if running:
        ok, msg = stop_service(svc_key)
        if not ok:
            return False, msg
        time.sleep(1)
    ok, msg = start_service(svc_key)
    return ok, msg


# ── Screens ──────────────────────────────────────────────────────────

class DetailScreen(ModalScreen):
    """Modal screen showing suggestion detail."""

    BINDINGS = [Binding("escape,q", "dismiss", "Close")]

    def __init__(self, suggestion_id: int):
        super().__init__()
        self.suggestion_id = suggestion_id

    def compose(self) -> ComposeResult:
        with Vertical(id="detail-container"):
            yield Static(id="detail-content")
            yield Button("Close [Esc]", id="close-btn", variant="primary")

    def on_mount(self) -> None:
        self.query_one("#detail-content", Static).update(self._build_detail())

    def _build_detail(self) -> Text:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT * FROM suggestions WHERE id = ?", (self.suggestion_id,))
            row = c.fetchone()
            if not row:
                return Text(f"Suggestion #{self.suggestion_id} not found", style="red")

            t = Text()
            t.append(f"#{row['id']} ", style="bold dim")
            t.append(f"{row['title']}\n", style="bold white")
            t.append(f"\nStatus: ", style="dim")
            t.append(f"{row['status']}\n", style=STATUS_COLORS.get(row["status"], "white"))
            t.append(f"Priority: ", style="dim")
            t.append(f"{row['priority'] or 'N/A'}\n", style=PRIORITY_COLORS.get(row["priority"] or "", "dim"))
            t.append(f"Author: ", style="dim")
            t.append(f"{row['author_name'] or 'N/A'}\n", style="white")
            t.append(f"Created: ", style="dim")
            t.append(f"{ts_to_str(row['created_at'])}\n", style="white")
            t.append(f"Last Activity: ", style="dim")
            t.append(f"{time_ago(row['last_activity_at'])}\n", style="white")

            if row["pr_url"]:
                t.append(f"PR: ", style="dim")
                t.append(f"{row['pr_url']}\n", style="cyan underline")

            if row["description"]:
                desc = row["description"]
                if len(desc) > 400:
                    desc = desc[:397] + "..."
                t.append(f"\n{desc}\n", style="white")

            if row["plan_display_summary"]:
                summary = row["plan_display_summary"]
                if len(summary) > 500:
                    summary = summary[:497] + "..."
                t.append(f"\nPlan Summary:\n{summary}\n", style="dim")

            c.execute(
                "SELECT title, status FROM plan_tasks WHERE suggestion_id = ? ORDER BY task_order",
                (self.suggestion_id,)
            )
            tasks = c.fetchall()
            if tasks:
                t.append(f"\nTasks ({len(tasks)}):\n", style="bold")
                icons = {"COMPLETED": "✓", "PENDING": "○", "IN_PROGRESS": "►", "REVIEWING": "◎", "FAILED": "✗"}
                icon_styles = {"COMPLETED": "green", "PENDING": "yellow", "IN_PROGRESS": "blue", "REVIEWING": "cyan", "FAILED": "red"}
                for task in tasks:
                    icon = icons.get(task["status"], "?")
                    style = icon_styles.get(task["status"], "white")
                    title = (task["title"] or "(untitled)")[:60]
                    t.append(f"  {icon} ", style=style)
                    t.append(f"{title}\n")

            c.execute(
                "SELECT sender_name, sender_type, content, created_at FROM suggestion_messages "
                "WHERE suggestion_id = ? ORDER BY created_at DESC LIMIT 5",
                (self.suggestion_id,)
            )
            msgs = c.fetchall()
            if msgs:
                t.append(f"\nRecent Messages:\n", style="bold")
                for m in reversed(msgs):
                    sender = m["sender_name"] or m["sender_type"]
                    content = (m["content"] or "")[:120]
                    sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
                    t.append(f"  {sender}: ", style=sender_style)
                    t.append(f"{content}\n")

            return t
        finally:
            conn.close()

    @on(Button.Pressed, "#close-btn")
    def close(self) -> None:
        self.dismiss()


class ClaudeLoginScreen(ModalScreen):
    """Drives `claude login` interactively: surfaces the OAuth URL,
    captures the user-supplied code, pipes it back into the live
    subprocess, and persists the resulting credentials to sqlite."""

    BINDINGS = [Binding("escape", "cancel", "Cancel")]

    URL_RE = re.compile(r"https?://[^\s\x1b]+")
    ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07")

    def __init__(self) -> None:
        super().__init__()
        self.master_fd: Optional[int] = None
        self.proc: Optional[subprocess.Popen] = None
        self.url: Optional[str] = None
        self.output_buffer = ""
        self.state = "starting"  # starting, awaiting_code, submitting, done, failed
        self._reader_thread: Optional[threading.Thread] = None

    def compose(self) -> ComposeResult:
        with Vertical(id="login-container"):
            yield Static("Claude CLI Login", id="login-title")
            yield Static("Starting `claude login`...", id="login-status")
            yield Static("", id="login-url")
            yield Input(placeholder="Paste authorization code here", id="login-code-input", disabled=True)
            with Horizontal(id="login-buttons"):
                yield Button("Submit Code", id="login-submit-btn", variant="primary", disabled=True)
                yield Button("Cancel [Esc]", id="login-cancel-btn")
            yield RichLog(id="login-log", highlight=False, markup=False, max_lines=200)

    def on_mount(self) -> None:
        self._start_login()

    @work(thread=True)
    def _start_login(self) -> None:
        try:
            master_fd, slave_fd = pty.openpty()
            env = os.environ.copy()
            env["TERM"] = "dumb"
            env["NO_COLOR"] = "1"
            self.proc = subprocess.Popen(
                ["claude", "login"],
                stdin=slave_fd, stdout=slave_fd, stderr=slave_fd,
                close_fds=True, env=env, cwd=SCRIPT_DIR,
            )
            os.close(slave_fd)
            self.master_fd = master_fd
        except FileNotFoundError:
            self.call_from_thread(self._set_failed, "`claude` CLI not found on PATH")
            return
        except Exception as e:
            self.call_from_thread(self._set_failed, f"Failed to start: {e}")
            return
        self._read_pty_loop()

    def _read_pty_loop(self) -> None:
        assert self.master_fd is not None and self.proc is not None
        while True:
            try:
                rlist, _, _ = select.select([self.master_fd], [], [], 0.5)
            except (OSError, ValueError):
                break
            if rlist:
                try:
                    data = os.read(self.master_fd, 4096)
                except OSError as e:
                    if e.errno == errno.EIO:
                        break
                    continue
                if not data:
                    break
                self._handle_pty_chunk(data)
            if self.proc.poll() is not None and not rlist:
                break

        try:
            rc = self.proc.wait(timeout=2)
        except Exception:
            rc = self.proc.returncode if self.proc.returncode is not None else -1

        if rc == 0:
            self.call_from_thread(self._on_login_exit_success)
        else:
            self.call_from_thread(
                self._set_failed,
                f"`claude login` exited with code {rc}. See log below.",
            )

    def _handle_pty_chunk(self, data: bytes) -> None:
        try:
            text = data.decode("utf-8", errors="replace")
        except Exception:
            return
        clean = self.ANSI_RE.sub("", text)
        self.output_buffer += clean
        self.call_from_thread(self._append_log, clean)
        if self.url is None:
            m = self.URL_RE.search(self.output_buffer)
            if m:
                self.url = m.group(0).rstrip(".,)\"'")
                self.call_from_thread(self._on_url_found, self.url)

    def _append_log(self, text: str) -> None:
        try:
            log = self.query_one("#login-log", RichLog)
        except Exception:
            return
        for line in text.splitlines():
            line = line.rstrip()
            if line:
                log.write(line)

    def _on_url_found(self, url: str) -> None:
        self.state = "awaiting_code"
        try:
            self.query_one("#login-url", Static).update(
                Text.from_markup(
                    "[bold cyan]1. Open this URL in your browser:[/]\n"
                    f"[underline]{url}[/]\n"
                    "[bold cyan]2. Sign in and copy the code shown.[/]"
                )
            )
            self.query_one("#login-status", Static).update(
                Text.from_markup("[yellow]Paste the code below, then press Enter or Submit.[/]")
            )
            inp = self.query_one("#login-code-input", Input)
            inp.disabled = False
            inp.focus()
            self.query_one("#login-submit-btn", Button).disabled = False
        except Exception:
            pass

    @on(Button.Pressed, "#login-submit-btn")
    def on_submit_button(self) -> None:
        self._submit_code()

    @on(Input.Submitted, "#login-code-input")
    def on_code_submit(self, event: Input.Submitted) -> None:
        self._submit_code()

    def _submit_code(self) -> None:
        if self.master_fd is None or self.state != "awaiting_code":
            return
        inp = self.query_one("#login-code-input", Input)
        code = inp.value.strip()
        if not code:
            return
        try:
            os.write(self.master_fd, (code + "\n").encode())
        except OSError as e:
            self._set_failed(f"Failed to send code: {e}")
            return
        self.state = "submitting"
        inp.disabled = True
        self.query_one("#login-submit-btn", Button).disabled = True
        self.query_one("#login-status", Static).update(
            Text.from_markup("[yellow]Submitting code, waiting for `claude login` to finish...[/]")
        )

    def _on_login_exit_success(self) -> None:
        try:
            stored = save_claude_credentials_from_disk()
        except Exception as e:
            self._set_failed(f"Login finished but failed to read credentials: {e}")
            return
        if not stored:
            self._set_failed(
                f"Login finished but no credentials at {CLAUDE_CREDS_PATH}"
            )
            return
        self.state = "done"
        self.query_one("#login-status", Static).update(
            Text.from_markup("[green]Login complete. Credentials saved to sqlite.[/]")
        )
        self.set_timer(2.0, self.dismiss)

    def _set_failed(self, msg: str) -> None:
        self.state = "failed"
        try:
            self.query_one("#login-status", Static).update(
                Text.from_markup(f"[red]{msg}[/]")
            )
        except Exception:
            pass

    def action_cancel(self) -> None:
        self._cleanup()
        self.dismiss()

    @on(Button.Pressed, "#login-cancel-btn")
    def on_cancel_button(self) -> None:
        self.action_cancel()

    def _cleanup(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.terminate()
            except OSError:
                pass
            try:
                self.proc.wait(timeout=2)
            except Exception:
                try:
                    self.proc.kill()
                except OSError:
                    pass
        if self.master_fd is not None:
            try:
                os.close(self.master_fd)
            except OSError:
                pass
            self.master_fd = None


CSS = """
#detail-container {
    background: $surface;
    border: thick $primary;
    padding: 1 2;
    margin: 2 4;
    height: auto;
    max-height: 90vh;
}

#detail-content {
    height: auto;
}

#status-bar {
    height: 1;
    background: $panel;
    color: $text-muted;
    padding: 0 1;
}

#command-input {
    dock: bottom;
    height: 3;
}

.stat-panel {
    border: round $primary;
    height: auto;
    min-height: 12;
    padding: 0 1;
}

.stat-label {
    color: $text-muted;
    width: 16;
}

.stat-value {
    text-style: bold;
}

#services-tab {
    height: 1fr;
}

#claude-log {
    border: round $accent;
    height: 1fr;
    min-height: 8;
}

#claude-input-row {
    height: 3;
    dock: bottom;
}

#claude-msg-input {
    width: 1fr;
}

#claude-send-btn {
    width: 10;
}

#progress-bar {
    width: 20;
}

.tab-content {
    height: 1fr;
}

SuggestionsStats, TaskStats, ActivityStats {
    width: 1fr;
}

DataTable {
    height: 1fr;
}

#error-fix-log {
    border: round $error;
    height: 1fr;
    min-height: 8;
}

#error-stats-panel {
    border: round $error;
    height: auto;
    min-height: 10;
    padding: 0 1;
    width: 1fr;
}

#error-table {
    height: 1fr;
}

.error-status-new {
    color: red;
    text-style: bold;
}

#login-container {
    background: $surface;
    border: thick $primary;
    padding: 1 2;
    margin: 2 4;
    height: auto;
    max-height: 90vh;
}

#login-title {
    text-style: bold;
    color: $accent;
    height: 1;
    margin-bottom: 1;
}

#login-status {
    height: auto;
    margin-bottom: 1;
}

#login-url {
    height: auto;
    margin-bottom: 1;
}

#login-code-input {
    margin-bottom: 1;
}

#login-buttons {
    height: 3;
    margin-bottom: 1;
}

#login-buttons Button {
    margin-right: 1;
}

#login-log {
    border: round $accent;
    height: 12;
    min-height: 6;
}

#login-claude-btn, #login-claude-btn2, #login-claude-btn-dash {
    width: 22;
}

#dashboard-actions-row {
    height: 3;
    margin-top: 1;
}

.login-status-inline {
    padding: 1 1 0 2;
    height: auto;
    width: 1fr;
}

.error-status-fixing {
    color: yellow;
    text-style: bold;
}

.error-status-fixed {
    color: green;
}

.error-status-failed {
    color: red;
}

#fix-progress-panel {
    border: round $warning;
    height: auto;
    min-height: 10;
    padding: 0 1;
    width: 1fr;
}
"""


class SuggestionsStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT status, COUNT(*) FROM suggestions GROUP BY status")
            counts = dict(c.fetchall())
        finally:
            conn.close()

        total = sum(counts.values())
        merged = counts.get("MERGED", 0)
        in_prog = counts.get("IN_PROGRESS", 0)
        denied = counts.get("DENIED", 0)
        dev_complete = counts.get("DEV_COMPLETE", 0)
        final_review = counts.get("FINAL_REVIEW", 0)
        timed_out = counts.get("TIMED_OUT", 0)
        rate = f"{merged / total * 100:.0f}%" if total > 0 else "0%"

        t = Text()
        t.append("Suggestions\n\n", style="bold")
        rows = [
            ("Total", str(total), "bold white"),
            ("Merged", str(merged), "green"),
            ("In Progress", str(in_prog), "blue"),
            ("Dev Complete", str(dev_complete), "cyan"),
            ("Final Review", str(final_review), "yellow"),
            ("Denied", str(denied), "red"),
            ("Timed Out", str(timed_out), "magenta"),
            ("Success Rate", rate, "bold green"),
        ]
        for label, value, style in rows:
            t.append(f"  {label:<14}", style="dim")
            t.append(f"{value}\n", style=style)
        self.update(t)


class TaskStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT status, COUNT(*) FROM plan_tasks GROUP BY status")
            counts = dict(c.fetchall())
        finally:
            conn.close()

        total = sum(counts.values())
        completed = counts.get("COMPLETED", 0)
        pending = counts.get("PENDING", 0)
        reviewing = counts.get("REVIEWING", 0)
        in_prog = counts.get("IN_PROGRESS", 0)
        pct = int(completed / total * 100) if total > 0 else 0
        bar_w = 18
        filled = int(bar_w * pct / 100)
        bar = "█" * filled + "░" * (bar_w - filled)

        t = Text()
        t.append("Plan Tasks\n\n", style="bold")
        t.append(f"  {'Total':<14}", style="dim")
        t.append(f"{total}\n", style="bold white")
        t.append(f"  {'Completed':<14}", style="dim")
        t.append(f"{completed}\n", style="green")
        t.append(f"  {'Pending':<14}", style="dim")
        t.append(f"{pending}\n", style="yellow")
        t.append(f"  {'Reviewing':<14}", style="dim")
        t.append(f"{reviewing}\n", style="cyan")
        t.append(f"  {'In Progress':<14}", style="dim")
        t.append(f"{in_prog}\n", style="blue")
        t.append(f"\n  ")
        t.append(bar, style="green")
        t.append(f" {pct}%\n")
        self.update(t)


class ActivityStats(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("SELECT COUNT(*) FROM suggestion_messages")
            total_msgs = c.fetchone()[0]
            c.execute("SELECT COUNT(DISTINCT suggestion_id) FROM suggestion_messages")
            active_threads = c.fetchone()[0]
            c.execute("SELECT COUNT(*) FROM app_users")
            user_count = c.fetchone()[0]
            c.execute("SELECT COUNT(*) FROM suggestions WHERE pr_url IS NOT NULL")
            pr_count = c.fetchone()[0]
        finally:
            conn.close()

        db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
        db_size_mb = db_size / (1024 * 1024)

        t = Text()
        t.append("Activity\n\n", style="bold")
        rows = [
            ("Messages", str(total_msgs), "bold white"),
            ("Active Threads", str(active_threads), "cyan"),
            ("Users", str(user_count), "yellow"),
            ("PRs Created", str(pr_count), "green"),
            ("DB Size", f"{db_size_mb:.1f} MB", "dim"),
        ]
        for label, value, style in rows:
            t.append(f"  {label:<14}", style="dim")
            t.append(f"{value}\n", style=style)
        self.update(t)


class ServicesWidget(Static):
    def on_mount(self) -> None:
        self.refresh_data()

    def refresh_data(self) -> None:
        t = Table(box=box.ROUNDED, show_lines=False, padding=(0, 1))
        t.add_column("Service", min_width=28, no_wrap=True)
        t.add_column("Status", width=10)
        t.add_column("PID", width=8, justify="right")
        t.add_column("CPU%", width=6, justify="right")
        t.add_column("MEM%", width=6, justify="right")
        t.add_column("RSS", width=10, justify="right")
        t.add_column("Uptime", width=14)

        for key, svc in SERVICES.items():
            running, pid, info = get_service_status(key)
            status_str = "[green]RUNNING[/]" if running else "[red]DOWN[/]"
            pid_str = str(pid) if pid else "-"
            cpu = info.get("cpu", "-")
            mem = info.get("mem", "-")
            rss_kb = info.get("rss", "0")
            try:
                rss_val = int(rss_kb)
                rss_str = f"{rss_val // 1024} MB" if rss_val > 1024 else f"{rss_val} KB"
            except (ValueError, TypeError):
                rss_str = "-"
            uptime = info.get("uptime", "-")
            t.add_row(svc["name"], status_str, pid_str, cpu, mem, rss_str, uptime)

        try:
            result = subprocess.run(["free", "-m"], capture_output=True, text=True)
            for line in result.stdout.splitlines():
                if line.startswith("Mem:"):
                    parts = line.split()
                    total_mem, used = int(parts[1]), int(parts[2])
                    pct = used * 100 // total_mem if total_mem else 0
                    t.add_row(
                        "[dim]System Memory[/]", f"[dim]{pct}%[/]", "-",
                        "-", f"[dim]{pct}[/]", f"[dim]{used}MB[/]", f"[dim]{total_mem}MB total[/]",
                    )
        except Exception:
            pass

        try:
            result = subprocess.run(["df", "-h", "/home/claude"], capture_output=True, text=True)
            lines = result.stdout.strip().splitlines()
            if len(lines) >= 2:
                parts = lines[1].split()
                t.add_row(
                    "[dim]Disk (/home/claude)[/]", f"[dim]{parts[4]}[/]", "-",
                    "-", "-", f"[dim]{parts[2]}[/]", f"[dim]{parts[1]} total[/]",
                )
        except Exception:
            pass

        self.update(t)


class ErrorStatsWidget(Static):
    """Displays error monitoring statistics."""

    def __init__(self, error_manager: Optional["ErrorManager"] = None, **kwargs):
        super().__init__(**kwargs)
        self._error_manager = error_manager

    def refresh_data(self, error_manager: Optional["ErrorManager"] = None) -> None:
        em = error_manager or self._error_manager
        if not em:
            self.update(Text("Error monitor not initialized", style="dim"))
            return

        all_errors = em.get_all_errors()
        total = len(all_errors)
        new = sum(1 for e in all_errors if e.status == "NEW")
        fixing = sum(1 for e in all_errors if e.status == "FIXING")
        fixed = sum(1 for e in all_errors if e.status == "FIXED")
        failed = sum(1 for e in all_errors if e.status == "FAILED")
        ignored = sum(1 for e in all_errors if e.status == "IGNORED")

        t = Text()
        t.append("Error Monitor\n\n", style="bold")

        if em.fix_in_progress:
            t.append("  STATUS  ", style="bold black on yellow")
            t.append(" FIXING ERROR\n\n", style="bold yellow")
        elif new > 0:
            t.append("  STATUS  ", style="bold black on red")
            t.append(f" {new} NEW ERROR{'S' if new > 1 else ''}\n\n", style="bold red")
        else:
            t.append("  STATUS  ", style="bold black on green")
            t.append(" MONITORING\n\n", style="bold green")

        rows = [
            ("Total Tracked", str(total), "bold white"),
            ("New", str(new), "red bold" if new > 0 else "dim"),
            ("Fixing", str(fixing), "yellow bold" if fixing > 0 else "dim"),
            ("Fixed", str(fixed), "green"),
            ("Failed", str(failed), "red" if failed > 0 else "dim"),
            ("Ignored", str(ignored), "dim"),
            ("", "", ""),
            ("Fixes Tried", str(em.total_fixes_attempted), "bold white"),
            ("Fixes OK", str(em.total_fixes_succeeded), "green"),
        ]
        for label, value, style in rows:
            if not label:
                t.append("\n")
                continue
            t.append(f"  {label:<14}", style="dim")
            t.append(f"{value}\n", style=style)

        log_size = 0
        if os.path.exists(ERROR_LOG_FILE):
            log_size = os.path.getsize(ERROR_LOG_FILE)
        t.append(f"\n  {'Error Log':<14}", style="dim")
        t.append(f"{log_size / 1024:.1f} KB\n", style="dim")

        self.update(t)


class FixProgressWidget(Static):
    """Displays the progress of the current Claude CLI error-fix session."""

    def __init__(self, error_manager: Optional["ErrorManager"] = None, **kwargs):
        super().__init__(**kwargs)
        self._error_manager = error_manager

    def refresh_data(self, error_manager: Optional["ErrorManager"] = None) -> None:
        em = error_manager or self._error_manager
        if not em:
            self.update(Text("Fix progress not available", style="dim"))
            return

        t = Text()
        session = em.current_fix

        if session and not session.finished:
            t.append("  Fix In Progress  \n\n", style="bold black on yellow")
            t.append("  Error: ", style="dim")
            t.append(f"{session.error_hash[:12]}\n", style="bold white")
            msg_preview = session.error_message[:70]
            if len(session.error_message) > 70:
                msg_preview += "..."
            t.append(f"  {msg_preview}\n\n", style="dim")

            elapsed = session.elapsed
            t.append(f"  Elapsed: ", style="dim")
            t.append(f"{elapsed:.0f}s\n", style="bold white")

            # Stage progress bar
            pct = int(session.progress * 100)
            bar_w = 30
            filled = int(bar_w * session.progress)
            bar = "█" * filled + "░" * (bar_w - filled)
            t.append(f"\n  ")
            t.append(bar, style="yellow")
            t.append(f" {pct}%\n\n", style="bold yellow")

            # Stage pipeline
            for stage_name, stage_label in FIX_STAGES:
                if stage_name in ("DONE", "FAILED"):
                    continue
                if stage_name == session.stage:
                    t.append("  ► ", style="bold yellow")
                    t.append(f"{stage_label}", style="bold yellow")
                    if session.current_action:
                        t.append(f"  {session.current_action}", style="dim yellow")
                    t.append("\n")
                elif FIX_STAGE_PROGRESS.get(stage_name, 0) < session.progress:
                    t.append("  ✓ ", style="green")
                    t.append(f"{stage_label}\n", style="dim green")
                else:
                    t.append("  ○ ", style="dim")
                    t.append(f"{stage_label}\n", style="dim")

            t.append("\n")

            if session.thinking_snippet:
                t.append("  Thinking: ", style="dim cyan")
                snippet = session.thinking_snippet[:80]
                t.append(f"{snippet}\n", style="cyan")

            if session.response_snippet:
                t.append("  Output: ", style="dim green")
                snippet = session.response_snippet[:80]
                t.append(f"{snippet}\n", style="green")

            t.append("\n")

            if session.files_read:
                t.append("  Files Read:\n", style="bold")
                for f in session.files_read[-6:]:
                    t.append(f"    {f}\n", style="dim")
                if len(session.files_read) > 6:
                    t.append(f"    ... and {len(session.files_read) - 6} more\n", style="dim")

            if session.files_modified:
                t.append("  Files Modified:\n", style="bold green")
                for f in session.files_modified[-6:]:
                    t.append(f"    {f}\n", style="green")

            if session.tools_used:
                t.append(f"\n  Tool Calls: ", style="dim")
                t.append(f"{session.tool_call_count}\n", style="bold white")
                unique_tools = list(dict.fromkeys(session.tools_used[-10:]))
                tools_str = ", ".join(unique_tools)
                t.append(f"  Recent: {tools_str}\n", style="dim")

        elif em.fix_history:
            last = em.fix_history[-1]
            t.append("  Last Fix Session  \n\n", style="bold black on green" if last.stage == "DONE" else "bold black on red")

            status_style = "bold green" if last.stage == "DONE" else "bold red"
            t.append("  Status: ", style="dim")
            t.append(f"{last.stage_label}\n", style=status_style)
            t.append("  Error: ", style="dim")
            t.append(f"{last.error_hash[:12]}\n", style="white")
            msg_preview = last.error_message[:70]
            t.append(f"  {msg_preview}\n\n", style="dim")

            elapsed = last.elapsed
            t.append(f"  Duration: ", style="dim")
            t.append(f"{elapsed:.1f}s\n", style="white")
            t.append(f"  Tools Used: ", style="dim")
            t.append(f"{last.tool_call_count}\n", style="white")

            if last.files_modified:
                t.append("  Files Changed:\n", style="bold")
                for f in last.files_modified:
                    t.append(f"    {f}\n", style="green" if last.stage == "DONE" else "red")

            if last.cost is not None:
                t.append(f"  Cost: ", style="dim")
                t.append(f"${last.cost:.4f}\n", style="dim")

            # Show recent history
            if len(em.fix_history) > 1:
                t.append(f"\n  Fix History ({len(em.fix_history)} sessions):\n", style="bold")
                for h in em.fix_history[-5:]:
                    icon = "✓" if h.stage == "DONE" else "✗"
                    icon_style = "green" if h.stage == "DONE" else "red"
                    t.append(f"    {icon} ", style=icon_style)
                    t.append(f"{h.error_hash[:8]} ", style="dim")
                    t.append(f"{h.elapsed:.0f}s ", style="dim")
                    t.append(f"{h.stage_label}\n", style=icon_style)

        else:
            t.append("  Fix Progress  \n\n", style="bold black on green")
            t.append("  No fix sessions yet.\n", style="dim")
            t.append("  Errors will be auto-fixed when detected,\n", style="dim")
            t.append("  or use: ", style="dim")
            t.append("fix <hash>", style="cyan")
            t.append(" to trigger manually.\n\n", style="dim")

            t.append("  Fix Stages:\n", style="bold")
            for stage_name, stage_label in FIX_STAGES:
                if stage_name in ("DONE", "FAILED"):
                    continue
                t.append(f"    ○ {stage_label}\n", style="dim")

        self.update(t)


# ── Main App ─────────────────────────────────────────────────────────

class SiteManagerApp(App):
    CSS = CSS
    TITLE = "Site Manager Dashboard"
    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit"),
        Binding("r", "refresh", "Refresh"),
        Binding("escape", "focus_command", "Command"),
    ]

    def __init__(self):
        super().__init__()
        self.claude_history = []  # list of (role, text, timestamp)
        self.claude_working = False
        self.claude_current_prompt = ""
        self.claude_start_time = None
        self.claude_thinking_snippet = ""
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self.status_message = ""
        self.list_page = 0
        self.list_filter = None
        self.list_sort = "id"
        self.error_manager = ErrorManager()
        self.error_auto_fix = True

    def compose(self) -> ComposeResult:
        conn = get_db()
        settings = get_settings(conn)
        conn.close()
        site_name = settings.get("site_name") or "Site Manager"
        model = settings.get("claude_model") or "unknown"
        repo = (settings.get("target_repo_url") or "").split("/")[-1].replace(".git", "")

        yield Header(show_clock=True)
        yield Label(f" {site_name}  |  Model: {model}  |  Repo: {repo}", id="subtitle")

        with TabbedContent(initial="dashboard"):
            with TabPane("Dashboard", id="dashboard"):
                with Horizontal():
                    yield SuggestionsStats(classes="stat-panel")
                    yield TaskStats(classes="stat-panel")
                    yield ActivityStats(classes="stat-panel")
                with Horizontal():
                    yield Static(id="recent-activity", classes="stat-panel")
                    yield ServicesWidget(id="services-widget", classes="stat-panel")
                    yield Static(id="claude-panel", classes="stat-panel")
                with Horizontal():
                    yield ErrorStatsWidget(self.error_manager, id="error-dashboard-panel", classes="stat-panel")
                    yield Static(id="error-recent-panel", classes="stat-panel")
                with Horizontal(id="dashboard-actions-row"):
                    yield Button("Login to Claude CLI", id="login-claude-btn-dash", variant="primary")
                    yield Static(id="login-claude-status", classes="login-status-inline")

            with TabPane("Suggestions", id="suggestions"):
                yield DataTable(id="suggestions-table", cursor_type="row")

            with TabPane("Activity", id="activity"):
                yield DataTable(id="activity-table", cursor_type="row")

            with TabPane("Services", id="services"):
                yield ServicesWidget(id="services-full")
                with Horizontal(id="claude-input-row"):
                    yield Input(placeholder='Claude command (e.g. "check app logs")', id="claude-msg-input")
                    yield Button("Send", id="claude-send-btn", variant="primary")
                    yield Button("Login to Claude", id="login-claude-btn")
                yield RichLog(id="claude-log", highlight=True, markup=True)

            with TabPane("Claude", id="claude"):
                with Horizontal(id="claude-input-row"):
                    yield Input(placeholder="Send message to Claude...", id="claude-msg-input2")
                    yield Button("Send", id="claude-send-btn2", variant="primary")
                    yield Button("Login to Claude", id="login-claude-btn2")
                yield RichLog(id="claude-log2", highlight=True, markup=True)

            with TabPane("Errors", id="errors"):
                with Horizontal():
                    yield ErrorStatsWidget(self.error_manager, id="error-stats-panel")
                    yield FixProgressWidget(self.error_manager, id="fix-progress-panel")
                    yield Static(id="error-actions-panel", classes="stat-panel")
                with Horizontal():
                    yield DataTable(id="error-table", cursor_type="row")
                yield RichLog(id="error-fix-log", highlight=True, markup=True)

            with TabPane("Help", id="help"):
                yield Static(self._build_help(), id="help-content")

        yield Label("", id="status-bar")
        yield Input(
            placeholder="[c] Claude  [v <id>] Detail  [start/stop/restart <svc>]  [fix <hash>] Fix error  [autofix on/off]  [r] Refresh  [q] Quit",
            id="command-input",
        )
        yield Footer()

    def _build_help(self) -> Text:
        t = Text()
        t.append("Navigation\n", style="bold underline")
        t.append("  Ctrl+Q    ", style="bold cyan"); t.append("Quit\n")
        t.append("  R         ", style="bold cyan"); t.append("Refresh\n")
        t.append("  Tab       ", style="bold cyan"); t.append("Switch tabs\n")

        t.append("\nCommand Bar (press Escape to focus)\n", style="bold underline")
        t.append("  c <msg>   ", style="bold cyan"); t.append("Send message to Claude\n")
        t.append("  login     ", style="bold cyan"); t.append("Configure Claude CLI (OAuth login)\n")
        t.append("  v <id>    ", style="bold cyan"); t.append("View suggestion detail\n")
        t.append("  start <svc>    ", style="bold cyan"); t.append("Start service (app, extractor)\n")
        t.append("  stop <svc>     ", style="bold cyan"); t.append("Stop service\n")
        t.append("  restart <svc>  ", style="bold cyan"); t.append("Restart service\n")
        t.append("  restart all    ", style="bold cyan"); t.append("Restart all services\n")
        t.append("  f <status>     ", style="bold cyan"); t.append("Filter suggestions list\n")
        t.append("  f clear        ", style="bold cyan"); t.append("Clear filter\n")
        t.append("  s <field>      ", style="bold cyan"); t.append("Sort: id, votes, activity, created, status\n")
        t.append("  n / p          ", style="bold cyan"); t.append("Next/prev page in suggestions\n")
        t.append("  q              ", style="bold cyan"); t.append("Quit\n")

        t.append("\nError Management\n", style="bold underline")
        t.append("  fix <hash>     ", style="bold cyan"); t.append("Manually trigger Claude fix for error hash\n")
        t.append("  ignore <hash>  ", style="bold cyan"); t.append("Ignore an error (don't auto-fix)\n")
        t.append("  autofix on/off ", style="bold cyan"); t.append("Toggle automatic error fixing\n")
        t.append("  errors         ", style="bold cyan"); t.append("Show error summary\n")

        t.append("\nStatus Values\n", style="bold underline")
        for status, color in STATUS_COLORS.items():
            t.append(f"  {status:<16}", style=color)
        t.append("\n")
        return t

    def on_mount(self) -> None:
        try:
            restore_claude_credentials_to_disk()
        except Exception:
            pass
        self.set_interval(1, self._auto_refresh)
        self.set_interval(2, self._tick_claude_panel)
        self.set_interval(2, self._tick_fix_progress)
        self.set_interval(ERROR_CHECK_INTERVAL, self._check_errors)
        self.set_interval(5, self._refresh_error_displays)
        self._populate_suggestions_table()
        self._populate_activity_table()
        self._populate_error_table()
        self._refresh_recent_activity()
        self._refresh_claude_panel()
        self._refresh_error_displays()
        self._refresh_login_status()
        self.query_one("#command-input", Input).focus()

    def _auto_refresh(self) -> None:
        self._refresh_stats()
        self._refresh_recent_activity()
        self._refresh_claude_panel()

    def action_refresh(self) -> None:
        self._refresh_stats()
        self._populate_suggestions_table()
        self._populate_activity_table()
        self._populate_error_table()
        self._refresh_recent_activity()
        self._refresh_claude_panel()
        self._refresh_error_displays()
        svc = self.query_one("#services-widget", ServicesWidget)
        svc.refresh_data()
        try:
            svc2 = self.query_one("#services-full", ServicesWidget)
            svc2.refresh_data()
        except Exception:
            pass
        self.set_status("[green]Refreshed[/]")

    def action_focus_command(self) -> None:
        self.query_one("#command-input", Input).focus()

    def _refresh_stats(self) -> None:
        for widget_type in (SuggestionsStats, TaskStats, ActivityStats):
            for w in self.query(widget_type):
                w.refresh_data()

    def _refresh_recent_activity(self) -> None:
        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("""
                SELECT sm.sender_name, sm.sender_type, sm.content, sm.created_at, sm.suggestion_id
                FROM suggestion_messages sm
                ORDER BY sm.created_at DESC
                LIMIT 10
            """)
            msgs = c.fetchall()
        finally:
            conn.close()

        t = Text()
        t.append("Recent Activity\n\n", style="bold")
        for m in msgs:
            when = time_ago(m["created_at"])
            sid = f"#{m['suggestion_id']}"
            sender = m["sender_name"] or m["sender_type"] or "?"
            content = (m["content"] or "")[:80]
            sender_style = "cyan" if m["sender_type"] == "SYSTEM" else "yellow"
            t.append(f"  {when:<8}", style="dim")
            t.append(f"{sid:<6}", style="dim")
            t.append(f"{sender}: ", style=sender_style)
            t.append(f"{content}\n")

        try:
            self.query_one("#recent-activity", Static).update(t)
        except Exception:
            pass

    def _tick_claude_panel(self) -> None:
        if self.claude_working:
            self._refresh_claude_panel()

    def _tick_fix_progress(self) -> None:
        if self.error_manager.fix_in_progress:
            self._refresh_fix_progress()

    def _refresh_claude_panel(self) -> None:
        t = Text()
        t.append("Claude Activity\n\n", style="bold white")

        # Live status block
        if self.claude_working:
            elapsed = time.time() - self.claude_start_time if self.claude_start_time else 0
            prompt_preview = (self.claude_current_prompt or "")[:60]
            if len(self.claude_current_prompt) > 60:
                prompt_preview += "..."
            t.append("  STATUS  ", style="bold black on yellow")
            if self.claude_is_thinking:
                t.append(f" THINKING  ({elapsed:.0f}s)\n", style="bold yellow")
                t.append("  Prompt:  ", style="dim")
                t.append(f"{prompt_preview}\n", style="white")
                if self.claude_thinking_snippet:
                    snippet = self.claude_thinking_snippet[:90]
                    t.append("  Thought: ", style="dim cyan")
                    t.append(f"{snippet}...\n", style="cyan")
            else:
                t.append(f" RESPONDING  ({elapsed:.0f}s)\n", style="bold green")
                t.append("  Prompt:  ", style="dim")
                t.append(f"{prompt_preview}\n", style="white")
                if self.claude_response_so_far:
                    preview = self.claude_response_so_far[:90]
                    t.append("  Writing: ", style="dim green")
                    t.append(f"{preview}...\n", style="green")
            t.append("\n")
        else:
            t.append("  STATUS  ", style="bold black on green")
            t.append(" IDLE\n\n", style="bold green")

        # Recent history
        history = self.claude_history[-4:]
        if not history:
            t.append("  Ready. Use command bar: c <message>\n", style="dim")
        else:
            for entry in history:
                role = entry[0]
                text = entry[1]
                ts = entry[2] if len(entry) > 2 else None
                elapsed_str = ""
                if role == "claude" and len(entry) > 3:
                    elapsed_str = f" [{entry[3]:.1f}s]"
                when = time_ago(ts * 1000) if ts else ""
                if role == "user":
                    t.append("  You", style="bold yellow")
                    t.append(f" {when}: ", style="dim yellow")
                    display = text if len(text) <= 80 else text[:77] + "..."
                    t.append(f"{display}\n", style="white")
                else:
                    t.append("  Claude", style="bold cyan")
                    t.append(f"{elapsed_str} {when}: ", style="dim cyan")
                    lines = text.split("\n")
                    preview = lines[0][:80]
                    if len(lines) > 1 or len(text) > 80:
                        preview += "..."
                    t.append(f"{preview}\n", style="white")
        try:
            self.query_one("#claude-panel", Static).update(t)
        except Exception:
            pass

    # ── Error management methods ────────────────────────────────────

    def _check_errors(self) -> None:
        """Periodically check error.log for new errors and trigger auto-fix."""
        new_errors = self.error_manager.check_for_new_errors()
        if not new_errors:
            return

        for err in new_errors:
            self._append_error_log(
                f"[bold red]NEW ERROR[/] [{err.error_hash}] {err.message[:100]}"
            )

        self._populate_error_table()
        self._refresh_error_displays()

        if self.error_auto_fix:
            with self.error_manager.lock:
                already_fixing = self.error_manager.fix_in_progress
            if not already_fixing:
                unfixed = self.error_manager.get_unfixed_errors()
                if unfixed:
                    self._trigger_error_fix(unfixed[0])

    def _trigger_error_fix(self, error: TrackedError) -> None:
        """Start a Claude CLI session to fix the given error."""
        with self.error_manager.lock:
            if self.error_manager.fix_in_progress:
                self.set_status("[yellow]Fix already in progress, queued[/]")
                return
            self.error_manager.fix_in_progress = True

        self._append_error_log(
            f"[bold yellow]FIXING[/] [{error.error_hash}] Launching Claude CLI..."
        )
        self.set_status(f"[yellow]Claude fixing error {error.error_hash}...[/]")
        self._error_fix_worker(error)

    def _append_error_log(self, line: str) -> None:
        """Write a line to the error fix log widgets."""
        for log_id in ("#error-fix-log",):
            try:
                log = self.query_one(log_id, RichLog)
                log.write(Text.from_markup(f"[dim]{datetime.now().strftime('%H:%M:%S')}[/] {line}"))
            except Exception:
                pass

    @work(thread=True)
    def _error_fix_worker(self, error: TrackedError) -> None:
        """Spawn Claude CLI with all permissions to diagnose and fix the error."""
        error_context = error.full_text
        error_msg = error.message

        fix_prompt = (
            f"An error has been detected in the site-manager application at {SCRIPT_DIR}. "
            f"Your job is to diagnose the root cause and fix it.\n\n"
            f"## Error Details\n"
            f"Error hash: {error.error_hash}\n"
            f"Occurrences: {error.count}\n"
            f"First seen: {datetime.fromtimestamp(error.first_seen).isoformat()}\n"
            f"Last seen: {datetime.fromtimestamp(error.last_seen).isoformat()}\n\n"
            f"## Error Message\n```\n{error_context}\n```\n\n"
            f"## Instructions\n"
            f"1. Read the error log at {ERROR_LOG_FILE} for full context\n"
            f"2. Read the application log at {os.path.join(SCRIPT_DIR, 'app.log')} for surrounding context\n"
            f"3. Identify the source file(s) causing the error\n"
            f"4. Read those source files to understand the code\n"
            f"5. Make targeted fixes - do NOT make speculative or unrelated changes\n"
            f"6. If it's a Java/Spring Boot error, check src/main/java/com/sitemanager/\n"
            f"7. If it's a build error, check pom.xml\n"
            f"8. If it's a database error, check the Flyway migrations in src/main/resources/db/migration/\n"
            f"9. After fixing, commit and push:\n"
            f"   git add <changed-files>\n"
            f"   git commit -m \"fix: <description of fix>\"\n"
            f"   git push origin main\n"
            f"10. Report what you fixed and why\n\n"
            f"IMPORTANT: Be concise. Fix only what's broken. Do not refactor unrelated code."
        )

        session = self.error_manager.start_fix_session(error.error_hash)
        proc = None

        try:
            with open(ERROR_FIX_LOG_FILE, "a") as log_f:
                log_f.write(f"\n{'='*60}\n")
                log_f.write(f"Error Fix Session: {datetime.now().isoformat()}\n")
                log_f.write(f"Error Hash: {error.error_hash}\n")
                log_f.write(f"Error: {error_msg}\n")
                log_f.write(f"{'='*60}\n")

            self.error_manager.update_fix_stage("ANALYZING", "Launching Claude CLI")
            self.call_from_thread(self._refresh_error_displays)

            proc = subprocess.Popen(
                [
                    "claude",
                    "--dangerously-skip-permissions",
                    "-p", fix_prompt,
                    "--output-format", "stream-json",
                    "--verbose",
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                cwd=SCRIPT_DIR,
            )

            self.error_manager.mark_fixing(error.error_hash, proc.pid)
            self.call_from_thread(self._refresh_error_displays)
            self.call_from_thread(self._populate_error_table)

            response_parts = []
            tools_used = []

            read_tools = {"Read", "ReadFile", "read_file", "View"}
            write_tools = {"Write", "Edit", "WriteFile", "write_file", "str_replace_editor",
                           "EditFile", "edit_file", "InsertText", "ReplaceText"}
            exec_tools = {"Bash", "bash", "execute_command", "RunCommand", "Shell"}
            git_tools = {"git_commit", "git_push", "git_add"}

            for raw_line in proc.stdout:
                with open(ERROR_FIX_LOG_FILE, "a") as log_f:
                    log_f.write(raw_line)

                line = raw_line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue

                ev_type = ev.get("type", "")

                if ev_type == "assistant":
                    content = ev.get("message", {}).get("content", [])
                    for block in content:
                        btype = block.get("type", "")
                        if btype == "text":
                            text_chunk = block.get("text", "")
                            response_parts.append(text_chunk)
                            preview = text_chunk[:120].replace("\n", " ")
                            with self.error_manager.lock:
                                if session:
                                    session.response_snippet = preview
                            self.call_from_thread(
                                self._append_error_log,
                                f"[green]CLAUDE[/] {preview}"
                            )
                            self.call_from_thread(self._refresh_fix_progress)
                        elif btype == "tool_use":
                            tool_name = block.get("name", "?")
                            tools_used.append(tool_name)
                            tool_input = block.get("input", {})
                            input_preview = json.dumps(tool_input, separators=(",", ":"))[:100]

                            with self.error_manager.lock:
                                if session:
                                    session.tools_used.append(tool_name)
                                    session.tool_call_count += 1

                                    file_path = tool_input.get("path") or tool_input.get("file_path") or tool_input.get("filename") or ""
                                    if file_path:
                                        short_path = file_path.replace(SCRIPT_DIR + "/", "")

                                    if tool_name in read_tools:
                                        if file_path and short_path not in session.files_read:
                                            session.files_read.append(short_path)
                                        self.error_manager.update_fix_stage("READING", f"Reading {short_path}" if file_path else "")
                                    elif tool_name in write_tools:
                                        if file_path and short_path not in session.files_modified:
                                            session.files_modified.append(short_path)
                                        self.error_manager.update_fix_stage("FIXING", f"Editing {short_path}" if file_path else "")
                                    elif tool_name in exec_tools:
                                        cmd_str = str(tool_input.get("command", ""))[:50]
                                        if any(g in cmd_str for g in ["git commit", "git push", "git add"]):
                                            self.error_manager.update_fix_stage("COMMITTING", cmd_str)
                                        elif any(kw in cmd_str for kw in ["mvn", "gradle", "test", "build", "npm"]):
                                            self.error_manager.update_fix_stage("TESTING", cmd_str)
                                        else:
                                            session.current_action = cmd_str
                                    elif tool_name in git_tools:
                                        self.error_manager.update_fix_stage("COMMITTING", tool_name)

                            self.call_from_thread(
                                self._append_error_log,
                                f"[bold blue]TOOL[/] [cyan]{tool_name}[/] [dim]{input_preview}[/]"
                            )
                            self.call_from_thread(self._refresh_fix_progress)
                        elif btype == "thinking":
                            snippet = (block.get("thinking", "") or "")[:80].replace("\n", " ")
                            if snippet:
                                with self.error_manager.lock:
                                    if session:
                                        session.thinking_snippet = snippet
                                        if session.stage == "ANALYZING":
                                            self.error_manager.update_fix_stage("DIAGNOSING", "Thinking...")
                                self.call_from_thread(
                                    self._append_error_log,
                                    f"[dim cyan]THINK[/] {snippet}"
                                )
                                self.call_from_thread(self._refresh_fix_progress)

                elif ev_type == "user":
                    content = ev.get("message", {}).get("content", [])
                    if isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "tool_result":
                                is_err = block.get("is_error", False)
                                result_content = block.get("content", "")
                                if isinstance(result_content, list):
                                    result_content = " ".join(
                                        c.get("text", "") for c in result_content
                                        if isinstance(c, dict)
                                    )
                                snippet = str(result_content)[:80].replace("\n", " ")
                                color = "red" if is_err else "green"
                                self.call_from_thread(
                                    self._append_error_log,
                                    f"[{color}]{'ERR' if is_err else 'OK'}[/] [dim]{snippet}[/]"
                                )

                elif ev_type == "result":
                    final = ev.get("result", "")
                    if final:
                        response_parts = [final]
                    stats = ev.get("stats", {})
                    if stats:
                        cost = stats.get("cost_usd", None)
                        turns = stats.get("num_turns", "?")
                        cost_str = f" cost=${cost:.4f}" if cost is not None else ""
                        with self.error_manager.lock:
                            if session:
                                session.cost = cost
                                session.turns = turns if isinstance(turns, int) else 0
                        self.call_from_thread(
                            self._append_error_log,
                            f"[dim]DONE turns={turns}{cost_str}[/]"
                        )

            proc.wait()
            response = "".join(response_parts).strip() if response_parts else ""

            git_committed = any(t in tools_used for t in ["Bash", "bash", "execute_command"])
            fix_indicators = ["fix", "commit", "push", "changed", "updated", "modified"]
            likely_fixed = any(ind in response.lower() for ind in fix_indicators) or git_committed

            if likely_fixed:
                self.error_manager.mark_fixed(error.error_hash, response[:2000])
                self.error_manager.finish_fix_session(success=True)
                self.call_from_thread(
                    self._append_error_log,
                    f"[bold green]FIXED[/] [{error.error_hash}] Error appears resolved"
                )
                self.call_from_thread(
                    self.set_status,
                    f"[green]Error {error.error_hash} fixed by Claude[/]"
                )
            else:
                self.error_manager.mark_failed(error.error_hash, response[:2000])
                self.error_manager.finish_fix_session(success=False)
                self.call_from_thread(
                    self._append_error_log,
                    f"[bold red]FAILED[/] [{error.error_hash}] Fix attempt unsuccessful"
                )
                self.call_from_thread(
                    self.set_status,
                    f"[red]Error {error.error_hash} fix failed[/]"
                )

        except Exception as e:
            self.error_manager.mark_failed(error.error_hash, str(e))
            self.error_manager.finish_fix_session(success=False)
            self.call_from_thread(
                self._append_error_log,
                f"[bold red]ERROR[/] Fix process failed: {e}"
            )
            self.call_from_thread(
                self.set_status,
                f"[red]Error fix failed: {e}[/]"
            )
        finally:
            if proc is not None:
                try:
                    proc.kill()
                except OSError:
                    pass
                try:
                    proc.wait(timeout=5)
                except Exception:
                    pass

        self.call_from_thread(self._refresh_error_displays)
        self.call_from_thread(self._populate_error_table)

        if self.error_auto_fix:
            with self.error_manager.lock:
                already_fixing = self.error_manager.fix_in_progress
            if not already_fixing:
                unfixed = self.error_manager.get_unfixed_errors()
                if unfixed:
                    next_err = unfixed[0]
                    self.call_from_thread(self._trigger_error_fix, next_err)

    def _populate_error_table(self) -> None:
        """Populate the error tracking table."""
        try:
            table = self.query_one("#error-table", DataTable)
        except Exception:
            return

        all_errors = self.error_manager.get_all_errors()

        table.clear(columns=True)
        table.add_columns("Hash", "Status", "Count", "First Seen", "Last Seen", "Message")

        status_styles = {
            "NEW": "red bold",
            "FIXING": "yellow bold",
            "FIXED": "green",
            "FAILED": "red",
            "IGNORED": "dim",
        }

        for err in all_errors:
            status_style = status_styles.get(err.status, "white")
            first = time_ago(err.first_seen * 1000)
            last = time_ago(err.last_seen * 1000)
            msg = err.message[:80]

            table.add_row(
                err.error_hash[:12],
                Text(err.status, style=status_style),
                str(err.count),
                first,
                last,
                msg,
                key=err.error_hash,
            )

    def _refresh_fix_progress(self) -> None:
        """Refresh the fix progress widget."""
        try:
            widget = self.query_one("#fix-progress-panel", FixProgressWidget)
            widget.refresh_data(self.error_manager)
        except Exception:
            pass

    def _refresh_error_displays(self) -> None:
        """Refresh all error-related display widgets."""
        for widget_id in ("#error-stats-panel", "#error-dashboard-panel"):
            try:
                widget = self.query_one(widget_id, ErrorStatsWidget)
                widget.refresh_data(self.error_manager)
            except Exception:
                pass

        self._refresh_fix_progress()
        self._refresh_error_actions_panel()
        self._refresh_error_recent_panel()

    def _refresh_error_actions_panel(self) -> None:
        """Update the error actions panel in the Errors tab."""
        t = Text()
        t.append("Actions & Config\n\n", style="bold")

        t.append("  Auto-Fix:  ", style="dim")
        if self.error_auto_fix:
            t.append("ON", style="bold green")
            t.append("  (errors auto-fixed by Claude)\n", style="dim")
        else:
            t.append("OFF", style="bold red")
            t.append("  (manual fixes only)\n", style="dim")

        t.append(f"  Check Interval: ", style="dim")
        t.append(f"{ERROR_CHECK_INTERVAL}s\n", style="white")

        t.append(f"  Error Log: ", style="dim")
        t.append(f"{ERROR_LOG_FILE}\n", style="dim")

        t.append(f"  Fix Log: ", style="dim")
        t.append(f"{ERROR_FIX_LOG_FILE}\n", style="dim")

        if self.error_manager.fix_in_progress:
            t.append("\n  ", style="")
            t.append("  FIX IN PROGRESS  ", style="bold black on yellow")
            t.append("\n", style="")

        t.append("\nCommands:\n", style="bold")
        t.append("  fix <hash>     ", style="cyan")
        t.append("trigger fix\n", style="dim")
        t.append("  ignore <hash>  ", style="cyan")
        t.append("ignore error\n", style="dim")
        t.append("  autofix on/off ", style="cyan")
        t.append("toggle auto-fix\n", style="dim")

        try:
            self.query_one("#error-actions-panel", Static).update(t)
        except Exception:
            pass

    def _refresh_error_recent_panel(self) -> None:
        """Update the recent errors panel on the dashboard tab."""
        t = Text()
        t.append("Recent Errors\n\n", style="bold red")

        all_errors = self.error_manager.get_all_errors()
        if not all_errors:
            t.append("  No errors detected\n", style="dim green")
        else:
            for err in all_errors[:5]:
                status_style = {
                    "NEW": "red bold", "FIXING": "yellow bold",
                    "FIXED": "green", "FAILED": "red", "IGNORED": "dim",
                }.get(err.status, "white")

                t.append(f"  {err.error_hash[:8]} ", style="dim")
                t.append(f"{err.status:<8}", style=status_style)
                t.append(f" x{err.count} ", style="bold white")
                t.append(f"{time_ago(err.last_seen * 1000)}\n", style="dim")
                msg_preview = err.message[:60]
                t.append(f"    {msg_preview}\n", style="dim")

        try:
            self.query_one("#error-recent-panel", Static).update(t)
        except Exception:
            pass

    def _populate_suggestions_table(self) -> None:
        try:
            table = self.query_one("#suggestions-table", DataTable)
        except Exception:
            return

        conn = get_db()
        try:
            c = conn.cursor()
            query = "SELECT id, title, status, up_votes, down_votes, priority, pr_url, last_activity_at FROM suggestions"
            params = []
            if self.list_filter:
                query += " WHERE status = ?"
                params.append(self.list_filter)
            sort_map = {
                "id": "id DESC",
                "votes": "(up_votes - down_votes) DESC",
                "activity": "last_activity_at DESC",
                "created": "created_at DESC",
                "status": "status ASC",
            }
            query += f" ORDER BY {sort_map.get(self.list_sort, 'id DESC')}"
            query += f" LIMIT 50 OFFSET {self.list_page * 50}"
            c.execute(query, params)
            rows = c.fetchall()
        finally:
            conn.close()

        table.clear(columns=True)
        table.add_columns("#", "Title", "Status", "Priority", "Votes", "PR", "Activity")
        for row in rows:
            title = (row["title"] or "(untitled)")[:48]
            status = row["status"] or "UNKNOWN"
            priority = row["priority"] or "-"
            votes = row["up_votes"] - row["down_votes"]
            vote_str = f"+{votes}" if votes > 0 else str(votes)
            has_pr = "Y" if row["pr_url"] else "-"
            activity = time_ago(row["last_activity_at"])
            table.add_row(
                str(row["id"]), title, status, priority, vote_str, has_pr, activity,
                key=str(row["id"]),
            )

    def _populate_activity_table(self) -> None:
        try:
            table = self.query_one("#activity-table", DataTable)
        except Exception:
            return

        conn = get_db()
        try:
            c = conn.cursor()
            c.execute("""
                SELECT sm.sender_name, sm.sender_type, sm.content, sm.created_at, sm.suggestion_id, s.title
                FROM suggestion_messages sm
                JOIN suggestions s ON s.id = sm.suggestion_id
                ORDER BY sm.created_at DESC
                LIMIT 100
            """)
            msgs = c.fetchall()
        finally:
            conn.close()

        table.clear(columns=True)
        table.add_columns("Time", "Sug#", "From", "Message")
        for m in msgs:
            when = time_ago(m["created_at"])
            sid = f"#{m['suggestion_id']}"
            sender = m["sender_name"] or m["sender_type"] or "?"
            content = (m["content"] or "")[:100]
            table.add_row(when, sid, sender, content)

    def set_status(self, msg: str) -> None:
        try:
            self.query_one("#status-bar", Label).update(msg)
        except Exception:
            pass

    @on(Input.Submitted, "#command-input")
    def handle_command(self, event: Input.Submitted) -> None:
        cmd = event.value.strip()
        event.input.clear()
        if not cmd:
            return
        self._process_command(cmd)

    @on(Button.Pressed, "#login-claude-btn")
    @on(Button.Pressed, "#login-claude-btn2")
    @on(Button.Pressed, "#login-claude-btn-dash")
    def open_claude_login(self) -> None:
        def _after(_result=None):
            self._refresh_login_status()
        self.push_screen(ClaudeLoginScreen(), _after)

    def _refresh_login_status(self) -> None:
        try:
            stored = has_stored_claude_credentials()
        except Exception:
            stored = False
        try:
            widget = self.query_one("#login-claude-status", Static)
        except Exception:
            return
        if stored:
            widget.update(Text.from_markup(
                "[green]Claude CLI: logged in (credentials in sqlite).[/]"
            ))
        else:
            widget.update(Text.from_markup(
                "[yellow]Claude CLI: not logged in — click the button to start OAuth.[/]"
            ))

    @on(Button.Pressed, "#claude-send-btn")
    def send_claude_services(self) -> None:
        inp = self.query_one("#claude-msg-input", Input)
        msg = inp.value.strip()
        if msg:
            inp.clear()
            self._send_to_claude(msg)

    @on(Input.Submitted, "#claude-msg-input")
    def submit_claude_services(self, event: Input.Submitted) -> None:
        msg = event.value.strip()
        if msg:
            event.input.clear()
            self._send_to_claude(msg)

    @on(Button.Pressed, "#claude-send-btn2")
    def send_claude_tab(self) -> None:
        inp = self.query_one("#claude-msg-input2", Input)
        msg = inp.value.strip()
        if msg:
            inp.clear()
            self._send_to_claude(msg)

    @on(Input.Submitted, "#claude-msg-input2")
    def submit_claude_tab(self, event: Input.Submitted) -> None:
        msg = event.value.strip()
        if msg:
            event.input.clear()
            self._send_to_claude(msg)

    def _process_command(self, cmd: str) -> None:
        cmd_lower = cmd.lower()

        if cmd_lower == "q":
            self.exit()
        elif cmd_lower == "r":
            self.action_refresh()
        elif cmd_lower == "n":
            self.list_page += 1
            self._populate_suggestions_table()
            self.set_status(f"Page {self.list_page + 1}")
        elif cmd_lower == "p":
            self.list_page = max(0, self.list_page - 1)
            self._populate_suggestions_table()
            self.set_status(f"Page {self.list_page + 1}")
        elif cmd_lower.startswith("v "):
            try:
                sid = int(cmd.split()[1])
                self.push_screen(DetailScreen(sid))
            except (ValueError, IndexError):
                self.set_status("[red]Usage: v <suggestion_id>[/]")
        elif cmd_lower.startswith("f "):
            arg = cmd_lower[2:].strip().upper()
            if arg == "CLEAR":
                self.list_filter = None
                self.set_status("[dim]Filter cleared[/]")
            else:
                self.list_filter = arg
                self.set_status(f"Filter: {arg}")
            self.list_page = 0
            self._populate_suggestions_table()
        elif cmd_lower.startswith("s ") and not cmd_lower.startswith("start") and not cmd_lower.startswith("stop"):
            arg = cmd_lower[2:].strip()
            if arg in ("id", "votes", "activity", "created", "status"):
                self.list_sort = arg
                self._populate_suggestions_table()
                self.set_status(f"Sort: {arg}")
            else:
                self.set_status(f"[red]Unknown sort: {arg}. Use: id, votes, activity, created, status[/]")
        elif cmd_lower.startswith("start "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                self.set_status(f"[yellow]Starting {svc_key}...[/]")
                self._run_service_cmd(start_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("stop "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key in SERVICES:
                self.set_status(f"[yellow]Stopping {svc_key}...[/]")
                self._run_service_cmd(stop_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("restart "):
            svc_key = cmd_lower.split(None, 1)[1].strip()
            if svc_key == "all":
                self.set_status("[yellow]Restarting all services...[/]")
                self._run_service_cmd_all()
            elif svc_key in SERVICES:
                self.set_status(f"[yellow]Restarting {svc_key}...[/]")
                self._run_service_cmd(restart_service, svc_key)
            else:
                self.set_status(f"[red]Unknown service: {svc_key}[/]")
        elif cmd_lower.startswith("c "):
            msg = cmd[2:].strip()
            if msg:
                self._send_to_claude(msg)
        elif cmd_lower == "c":
            self.set_status("[dim]Use: c <message>[/]")
        elif cmd_lower in ("login", "claude-login", "/login"):
            self.push_screen(ClaudeLoginScreen())
        elif cmd_lower.startswith("fix "):
            error_hash = cmd_lower.split(None, 1)[1].strip()
            matched = None
            for eh, err in self.error_manager.errors.items():
                if eh.startswith(error_hash):
                    matched = err
                    break
            if matched:
                if matched.status == "FIXING":
                    self.set_status(f"[yellow]Error {error_hash} is already being fixed[/]")
                else:
                    matched.status = "NEW"
                    self._trigger_error_fix(matched)
            else:
                self.set_status(f"[red]Error hash not found: {error_hash}[/]")
        elif cmd_lower.startswith("ignore "):
            error_hash = cmd_lower.split(None, 1)[1].strip()
            matched_hash = None
            for eh in self.error_manager.errors:
                if eh.startswith(error_hash):
                    matched_hash = eh
                    break
            if matched_hash:
                self.error_manager.mark_ignored(matched_hash)
                self._populate_error_table()
                self._refresh_error_displays()
                self.set_status(f"[dim]Error {error_hash} ignored[/]")
            else:
                self.set_status(f"[red]Error hash not found: {error_hash}[/]")
        elif cmd_lower.startswith("autofix"):
            arg = cmd_lower.replace("autofix", "").strip()
            if arg == "on":
                self.error_auto_fix = True
                self.set_status("[green]Auto-fix enabled[/]")
                self._refresh_error_displays()
                with self.error_manager.lock:
                    already_fixing = self.error_manager.fix_in_progress
                if not already_fixing:
                    unfixed = self.error_manager.get_unfixed_errors()
                    if unfixed:
                        self._trigger_error_fix(unfixed[0])
            elif arg == "off":
                self.error_auto_fix = False
                self.set_status("[yellow]Auto-fix disabled[/]")
                self._refresh_error_displays()
            else:
                status = "ON" if self.error_auto_fix else "OFF"
                self.set_status(f"Auto-fix is {status}. Use: autofix on/off")
        elif cmd_lower == "errors":
            all_errors = self.error_manager.get_all_errors()
            if not all_errors:
                self.set_status("[green]No errors tracked[/]")
            else:
                new = sum(1 for e in all_errors if e.status == "NEW")
                fixing = sum(1 for e in all_errors if e.status == "FIXING")
                fixed = sum(1 for e in all_errors if e.status == "FIXED")
                self.set_status(
                    f"Errors: {len(all_errors)} total, {new} new, {fixing} fixing, {fixed} fixed"
                )
        else:
            self.set_status(f"[red]Unknown command: {cmd}[/]")

    @work(thread=True)
    def _run_service_cmd(self, fn, svc_key: str) -> None:
        ok, msg = fn(svc_key)
        style = "green" if ok else "red"
        self.call_from_thread(self.set_status, f"[{style}]{msg}[/]")
        self.call_from_thread(self._refresh_services)

    @work(thread=True)
    def _run_service_cmd_all(self) -> None:
        msgs = []
        for key in SERVICES:
            ok, msg = restart_service(key)
            msgs.append(f"[{'green' if ok else 'red'}]{msg}[/]")
        self.call_from_thread(self.set_status, " | ".join(msgs))
        self.call_from_thread(self._refresh_services)

    def _refresh_services(self) -> None:
        for w in self.query(ServicesWidget):
            w.refresh_data()

    def _send_to_claude(self, message: str) -> None:
        self.claude_history.append(("user", message, time.time()))
        self.claude_working = True
        self.claude_current_prompt = message
        self.claude_start_time = time.time()
        self.claude_thinking_snippet = ""
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self._refresh_claude_panel()
        self._update_claude_logs()
        self.set_status("[yellow]Claude is working...[/]")
        self._claude_worker(message)

    def _append_verbose_line(self, line: str) -> None:
        for log_id in ("#claude-log", "#claude-log2"):
            try:
                log = self.query_one(log_id, RichLog)
                log.write(Text.from_markup(line))
            except Exception:
                pass

    @work(thread=True)
    def _claude_worker(self, message: str) -> None:
        system_context = (
            "You are managing the site-manager application at /home/claude/site-manager. "
            "The user is interacting with you through a dashboard terminal. "
            "You can make code changes, commit and push them, restart services, etc. "
            "Be concise in responses (under 500 chars unless showing code). "
            "When making changes, actually edit the files and run the commands. "
            "After making code changes, offer to commit and push. "
            "Available services: Spring Boot app (auto-update.sh), Error Extractor (extract-errors.sh). "
            "To restart the app: stop auto-update.sh processes, then relaunch. "
            "To commit: git add, git commit, git push origin main. "
            "Current directory: /home/claude/site-manager "
            "IMPORTANT: Do exactly what the user requests and nothing more. Do not get creative or make changes beyond what was explicitly asked. If the user asks to move something in a specific way, do only that exact move."
        )
        response_parts = []
        session_ts = datetime.now().isoformat()
        proc = None
        try:
            with open(CLAUDE_LOG_FILE, "a") as log_f:
                log_f.write(f"\n{'='*60}\n")
                log_f.write(f"Session: {session_ts}\n")
                log_f.write(f"Prompt: {message}\n")
                log_f.write(f"{'='*60}\n")

            self.call_from_thread(self._append_verbose_line, f"[bold yellow]>[/] {message}")

            try:
                restore_claude_credentials_to_disk()
            except Exception:
                pass

            proc = subprocess.Popen(
                [
                    "claude", "--dangerously-skip-permissions",
                    "-p", f"{system_context}\n\nUser request: {message}",
                    "--model", "sonnet",
                    "--output-format", "stream-json",
                    "--verbose",
                ],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, cwd=SCRIPT_DIR,
            )
            for raw_line in proc.stdout:
                with open(CLAUDE_LOG_FILE, "a") as log_f:
                    log_f.write(raw_line)

                line = raw_line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                except json.JSONDecodeError:
                    continue
                ev_type = ev.get("type", "")

                if ev_type == "assistant":
                    content = ev.get("message", {}).get("content", [])
                    for block in content:
                        btype = block.get("type", "")
                        if btype == "thinking":
                            thinking = block.get("thinking", "")
                            snippet = thinking[:120].replace("\n", " ") if thinking else ""
                            self.claude_is_thinking = True
                            self.claude_thinking_snippet = snippet
                            self.call_from_thread(self._refresh_claude_panel)
                            if snippet:
                                self.call_from_thread(
                                    self._append_verbose_line,
                                    f"[dim cyan]THINK[/] {snippet}",
                                )
                        elif btype == "text":
                            text_chunk = block.get("text", "")
                            response_parts.append(text_chunk)
                            self.claude_is_thinking = False
                            preview = "".join(response_parts)[:200].replace("\n", " ")
                            self.claude_response_so_far = preview
                            self.call_from_thread(self._refresh_claude_panel)
                        elif btype == "tool_use":
                            tool_name = block.get("name", "?")
                            tool_input = block.get("input", {})
                            input_str = json.dumps(tool_input, separators=(",", ":"))[:120]
                            self.call_from_thread(
                                self._append_verbose_line,
                                f"[bold blue]TOOL[/] [cyan]{tool_name}[/] [dim]{input_str}[/]",
                            )

                elif ev_type == "user":
                    content = ev.get("message", {}).get("content", [])
                    if isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and block.get("type") == "tool_result":
                                result_content = block.get("content", "")
                                if isinstance(result_content, list):
                                    result_content = " ".join(
                                        c.get("text", "") for c in result_content
                                        if isinstance(c, dict)
                                    )
                                snippet = str(result_content)[:100].replace("\n", " ")
                                is_err = block.get("is_error", False)
                                color = "red" if is_err else "green"
                                self.call_from_thread(
                                    self._append_verbose_line,
                                    f"[{color}]{'ERR' if is_err else 'OK '}[/] [dim]{snippet}[/]",
                                )

                elif ev_type == "system":
                    subtype = ev.get("subtype", "")
                    if subtype == "init":
                        tools = ev.get("tools", [])
                        tool_names = ", ".join(t.get("name", "?") for t in tools[:8])
                        self.call_from_thread(
                            self._append_verbose_line,
                            f"[dim]INIT tools: {tool_names}[/]",
                        )

                elif ev_type == "result":
                    final = ev.get("result", "")
                    if final:
                        response_parts = [final]
                        self.claude_response_so_far = final[:200].replace("\n", " ")
                    stats = ev.get("stats", {})
                    if stats:
                        turns = stats.get("num_turns", "?")
                        cost = stats.get("cost_usd", None)
                        cost_str = f"  cost=${cost:.4f}" if cost is not None else ""
                        self.call_from_thread(
                            self._append_verbose_line,
                            f"[dim]DONE  turns={turns}{cost_str}[/]",
                        )

            proc.wait()
            response = "".join(response_parts).strip() if response_parts else "(no response)"
        except Exception as e:
            response = f"[error: {e}]"
        finally:
            if proc is not None:
                try:
                    proc.kill()
                except OSError:
                    pass
                try:
                    proc.wait(timeout=5)
                except Exception:
                    pass

        if len(response) > 3000:
            response = response[:2900] + "\n... (truncated)"

        elapsed = time.time() - self.claude_start_time if self.claude_start_time else 0
        self.claude_history.append(("claude", response, time.time(), elapsed))
        self.claude_working = False
        self.claude_is_thinking = False
        self.claude_response_so_far = ""
        self.call_from_thread(self._refresh_claude_panel)
        self.call_from_thread(self._update_claude_logs)
        self.call_from_thread(self.set_status, f"[green]Claude responded ({elapsed:.1f}s)[/]")

    def _update_claude_logs(self) -> None:
        for log_id in ("#claude-log", "#claude-log2"):
            try:
                log = self.query_one(log_id, RichLog)
                log.clear()
                for entry in self.claude_history[-20:]:
                    role = entry[0]
                    text = entry[1]
                    ts = entry[2] if len(entry) > 2 else None
                    elapsed_str = f" [{entry[3]:.1f}s]" if role == "claude" and len(entry) > 3 else ""
                    when = f" ({time_ago(ts * 1000)})" if ts else ""
                    if role == "user":
                        header = Text(f"You{when}: ", style="bold yellow")
                        log.write(header + Text(text))
                    else:
                        header = Text(f"Claude{elapsed_str}{when}: ", style="bold cyan")
                        log.write(header + Text(text))
                    log.write("")
            except Exception:
                pass

    @on(DataTable.RowSelected, "#suggestions-table")
    def on_suggestion_selected(self, event: DataTable.RowSelected) -> None:
        if event.row_key and event.row_key.value:
            try:
                sid = int(event.row_key.value)
                self.push_screen(DetailScreen(sid))
            except (ValueError, TypeError):
                pass


def main():
    app = SiteManagerApp()
    app.run()


if __name__ == "__main__":
    main()
