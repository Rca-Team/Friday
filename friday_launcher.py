"""Frozen application entry point; initialization precedes heavy imports."""
import os
import sys
from pathlib import Path


def run():
    from core.user_paths import get_user_data_dir
    data = get_user_data_dir()
    os.chdir(data)
    logs = data / "logs"
    logs.mkdir(exist_ok=True)
    if sys.stdout is None:
        sys.stdout = open(logs / "runtime.log", "a", encoding="utf-8", buffering=1)
    if sys.stderr is None:
        sys.stderr = sys.stdout
    # Generated scripts use the bundled interpreter via this entry point.
    if len(sys.argv) > 1 and sys.argv[1].endswith(".py"):
        import runpy
        runpy.run_path(sys.argv[1], run_name="__main__")
        return
    if len(sys.argv) > 1 and sys.argv[1] == "-m":
        print("Installing arbitrary Python packages into the frozen app is unsupported. Use a developer environment.", file=sys.stderr)
        raise SystemExit(2)
    if "--self-test" in sys.argv:
        from core.release_checks import self_test
        self_test(Path(sys.argv[sys.argv.index("--self-test") + 1]))
        return
    from core.instance import acquire_instance
    if not acquire_instance():
        return
    try:
        from main import main
        main()
    except Exception:
        import traceback
        report = traceback.format_exc()
        (logs / "startup-error.log").write_text(report, encoding="utf-8")
        print(report, file=sys.stderr)
        if os.name == "nt":
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, f"Friday could not start. Details: {logs / 'startup-error.log'}", "Friday startup error", 0x10)
        raise


if __name__ == "__main__":
    run()
