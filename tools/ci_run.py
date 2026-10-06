"""Keep CI console output concise and retain complete command logs as artifacts."""
import pathlib
import subprocess
import sys
import time

label, *command = sys.argv[1:]
folder = pathlib.Path(".scratch/ci-logs")
folder.mkdir(parents=True, exist_ok=True)
log = folder / (label + ".log")
started = time.monotonic()
with log.open("w", encoding="utf-8") as output:
    result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
print(f"{label}: {'passed' if result.returncode == 0 else 'failed'} ({time.monotonic() - started:.0f}s)", flush=True)
if result.returncode:
    print("\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-60:]))
sys.exit(result.returncode)
