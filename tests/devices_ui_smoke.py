"""Verify device UI state and async pairing without real accounts or phones."""
import asyncio
import os
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / ".venv" / "Lib" / "site-packages"))
os.environ["QT_QPA_PLATFORM"] = "windows"
from PyQt6.QtWidgets import QApplication
from ui_devices import DevicesPage


class Service:
    def __init__(self):
        self.running = True
        self.tunnel = "https://example.invalid"
        self.devices = [{"device_id": "phone-1", "name": "Gaurav's phone", "platform": "android",
                         "online": True, "battery": 78, "capabilities": ["call", "message", "files"],
                         "permissions": ["microphone", "notifications"], "last_seen": "Just now"},
                        {"device_id": "tablet-1", "name": "Study tablet", "platform": "android", "online": False}]
        self.pending = []
        self._loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self.thread.start()

    def is_running(self):
        return self.running

    def gateway_info(self):
        return {"running": self.running, "tunnel_active": bool(self.tunnel), "tunnel_url": self.tunnel}

    def list_devices(self):
        return self.devices

    def list_pending_requests(self):
        return self.pending

    def logs(self):
        return [{"event": "DEVICE_CONNECTED"}]

    def create_pairing_offer(self, **kwargs):
        return {"pairing_code": "123456", "pairing_token": "test-only", "host": "192.168.1.2", "port": 8765,
                "tunnel_url": self.tunnel, "expires": 120}

    async def approve_pending_request(self, pending_id):
        assert asyncio.get_running_loop() is self._loop
        self.pending = []
        self.devices.append({"device_id": "new-phone", "name": "New phone", "platform": "android", "online": True})
        return {"success": True}

    async def reject_pending_request(self, pending_id):
        assert asyncio.get_running_loop() is self._loop
        self.pending = []
        return True

    async def disconnect_device(self, device_id):
        assert asyncio.get_running_loop() is self._loop
        next(d for d in self.devices if d["device_id"] == device_id)["online"] = False
        return {"success": True}


def pump(app, condition, timeout=5):
    deadline = time.monotonic() + timeout
    while not condition() and time.monotonic() < deadline:
        app.processEvents()
        time.sleep(.02)
    app.processEvents()
    assert condition(), "UI operation timed out"


def run():
    app = QApplication([sys.executable])
    service = Service()
    settings = SimpleNamespace(get_setting=lambda name, default=False: default)
    with patch.dict(sys.modules, {"memory.config_manager": settings}):
        page = DevicesPage()
        page.set_service(service)
        page.resize(1020, 800)
        page.show()
        app.processEvents()
        assert page.metrics["Connected"].text() == "1"
        assert page.metrics["Internet access"].text() == "Ready"
        page._toggle_details("phone-1")
        page.search.setText("no-such-phone")
        assert page.device_list.count() == 2  # Empty card and stretch.
        page.search.clear()
        page.filter.setCurrentText("Offline")
        assert page.device_list.count() == 2
        page.filter.setCurrentText("All devices")
        page._reduced_motion = True
        page._fade.stop()
        page._effect.setOpacity(1)
        app.processEvents()
        page.resize(660, 580)
        app.processEvents()
        assert page.device_list.parentWidget().width() <= page.width()
        page.resize(1020, 800)
        app.processEvents()
        output = ROOT / "outputs"
        output.mkdir(exist_ok=True)
        assert page.grab().save(str(output / "devices-rebuilt.png"))
        page._trigger_add_device()
        pump(app, lambda: bool(page._offer))
        assert not page.qr.pixmap().isNull()
        assert page.copy_code.isEnabled()
        page._copy("code")
        assert app.clipboard().text() == "123456"
        page._deadline = time.monotonic() - 1
        page._tick_onboarding()
        assert not page.copy_code.isEnabled()
        assert "expired" in page.qr.text()
        page._refresh_onboarding_offer()
        pump(app, lambda: bool(page._offer))
        page.resize(660, 580)
        app.processEvents()
        assert page.stack.widget(1).horizontalScrollBar().maximum() == 0
        page.resize(1020, 800)
        app.processEvents()
        assert page.grab().save(str(output / "devices-pairing.png"))
        service.pending = [{"pending_id": "request-1", "device_name": "New phone", "platform": "android", "ip": "192.168.1.3"}]
        page.refresh()
        assert page.pending_host.isVisible()
        app.processEvents()
        assert page.grab().save(str(output / "devices-approval.png"))
        page._pending_action("request-1", "approve")
        pump(app, lambda: page.stack.currentIndex() == 0)
        assert "Connected to New phone" in page.notice.text()
        page._device_action("phone-1", "disconnect")
        pump(app, lambda: not page._busy)
        assert not service.devices[0]["online"]
        service.running = False
        service.tunnel = None
        page.refresh()
        assert page.gateway_status.text() == "Gateway offline"
        assert page.metrics["Internet access"].text() == "Not ready"
        page._trigger_add_device()
        page._cancel_onboarding()
        pump(app, lambda: not page._busy)
        assert not page._offer
        assert page.stack.currentIndex() == 0
        page.hide()
        app.processEvents()
        assert not page.poll.isActive()
        assert not page.countdown.isActive()
        assert not page.pulse.timer.isActive()
        page._pool.shutdown(wait=True)
        page.close()
    service._loop.call_soon_threadsafe(service._loop.stop)
    service.thread.join(timeout=2)
    service._loop.close()
    print("DEVICES_UI_OK: status, search, filters, QR, expiry, clipboard, approval, gateway loop, cancellation, hidden timers")


if __name__ == "__main__":
    run()
