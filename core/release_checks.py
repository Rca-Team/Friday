"""Explicit opt-in installed-build smoke checks, without live account actions."""
import json
import sys
from pathlib import Path


def self_test(report: Path):
    results = {}
    try:
        print("SELFTEST: importing main", flush=True)
        import main
        results["main_import"] = True
        from PyQt6.QtWidgets import QApplication
        from PyQt6.QtCore import QTimer, QThread, Qt, qInstallMessageHandler
        qInstallMessageHandler(lambda kind, context, message: print(f"QT: {message}", flush=True))
        from ui import MainWindow, FridayUI
        print("SELFTEST: constructing QApplication", flush=True)
        app = QApplication.instance() or QApplication([sys.executable])
        print("SELFTEST: constructing MainWindow", flush=True)
        win = MainWindow(str(main.BASE_DIR / "assets" / "friday_logo.png"))
        # Exercise the actual public UI facade without starting account integrations.
        facade = FridayUI.__new__(FridayUI)
        facade._win = win
        facade.show_main = lambda: win.showNormal()
        win._voice_activate_sig.connect(facade._activate_voice_on_ui_thread, Qt.ConnectionType.QueuedConnection)
        changes = []
        facade.bind_push_to_talk(changes.append)
        win.ptt_changed.emit(True)
        win.ptt_changed.emit(False)
        results['ptt_signal'] = changes == [True, False]
        win.set_muted_state(True)
        results['wake_state_forwarded'] = facade.wakeword_listening
        import threading
        threading.Thread(target=facade.activate_voice).start()
        print("SELFTEST: showing MainWindow", flush=True)
        win.show()
        def finish():
            win.grab().save(str(report.with_suffix(".png")))
            results["qt_window"] = bool(win.isVisible())
            results['wake_unmuted'] = not facade.muted
            app.quit()
        QTimer.singleShot(5000, finish)
        app.exec()
        from actions.playwright_mcp_client import PlaywrightMCPClient
        from core.user_paths import get_user_data_dir
        browser = PlaywrightMCPClient(headless=True, user_data_dir=str(get_user_data_dir() / "smoke-browser"))
        try:
            results["browser_handshake"] = browser.start()
            response = browser.call_tool("browser_navigate", {"url": "data:text/html,<title>Friday smoke test</title><h1>Bundled browser works</h1>"})
            results["browser_navigation"] = response
        finally:
            browser.close()
        from fastapi.testclient import TestClient
        from dashboard.server import DashboardServer
        with TestClient(DashboardServer().app) as client:
            results["dashboard_http"] = client.get("/login").status_code
        results["passed"] = all(results.get(k) for k in ['qt_window', 'ptt_signal', 'wake_state_forwarded', 'wake_unmuted', 'browser_handshake']) and results["dashboard_http"] == 200 and isinstance(response, dict) and response.get("success") is True
    except Exception:
        import traceback
        results["error"] = traceback.format_exc()
        results["passed"] = False
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(results, indent=2, default=str), encoding="utf-8")
    if not results.get("passed"):
        raise SystemExit(1)
