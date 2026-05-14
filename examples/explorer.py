"""Subprocess wrapper for the Explorer stdio protocol and --replay command."""
import json
import subprocess
from pathlib import Path

_ROOT    = Path(__file__).resolve().parent.parent
_CP      = f"{_ROOT / 'tla2tools.jar'}:{_ROOT / 'target' / 'classes'}"
SPEC_DIR = Path(__file__).resolve().parent  # examples/
MUTEX    = ("Mutex.tla", "Mutex.cfg")  # filenames relative to SPEC_DIR


class Explorer:
    """Wraps the Explorer subprocess, sending commands and parsing JSON replies.

    spec and cfg must be filenames (or paths) relative to cwd.  TLC resolves
    module imports from the JVM working directory, so cwd must be the directory
    that contains the .tla file.
    """

    def __init__(self, spec: str, cfg: str, *, cwd: str | Path):
        self._proc = subprocess.Popen(
            ["java", "-cp", _CP, "Explorer", spec, cfg],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True,
            cwd=str(cwd),
        )
        ready = self._recv()
        if not ready.get("ready"):
            raise RuntimeError(f"Explorer failed to start: {ready}")

    def send(self, cmd: str) -> dict:
        self._proc.stdin.write(cmd + "\n")
        self._proc.stdin.flush()
        return self._recv()

    def _recv(self) -> dict:
        while True:
            line = self._proc.stdout.readline()
            if not line:
                raise RuntimeError("Explorer process ended unexpectedly")
            line = line.strip()
            if line.startswith("{"):
                return json.loads(line)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        try:
            self._proc.stdin.write("exit\n")
            self._proc.stdin.flush()
        except BrokenPipeError:
            pass
        self._proc.wait()


def replay(spec: str, cfg: str, trace_path: str, *, cwd: str | Path) -> tuple[bool, str]:
    """Run --replay mode; return (passed, full output text)."""
    r = subprocess.run(
        ["java", "-cp", _CP, "Explorer", "--replay", spec, cfg, trace_path],
        capture_output=True, text=True, cwd=str(cwd),
    )
    return "PASS:" in r.stdout, r.stdout + r.stderr


