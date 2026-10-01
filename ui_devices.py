"""Live device connections UI. Gateway operations run outside the Qt thread."""
import asyncio
import io
import json
import time
from concurrent.futures import ThreadPoolExecutor

from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QPropertyAnimation, QEasingCurve
from PyQt6.QtGui import QPainter, QColor, QPen, QPixmap
from PyQt6.QtWidgets import (
    QApplication, QWidget, QFrame, QLabel, QPushButton, QLineEdit, QComboBox,
    QVBoxLayout, QHBoxLayout, QGridLayout, QScrollArea, QStackedWidget,
    QProgressBar, QGraphicsOpacityEffect, QInputDialog, QMessageBox, QSizePolicy, QLayout,
)


class ConnectionPulse(QWidget):
    """A small connection visualization; never animates off screen."""
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setFixedSize(112, 112)
        self.setAccessibleName("PC and phone connection")
        self.phase = 0
        self.online = False
        self.reduced_motion = False
        self.timer = QTimer(self)
        self.timer.setInterval(50)
        self.timer.timeout.connect(self._tick)

    def _tick(self):
        self.phase = (self.phase + 5) % 360
        self.update()

    def showEvent(self, event):
        super().showEvent(event)
        if not self.reduced_motion:
            self.timer.start()

    def hideEvent(self, event):
        self.timer.stop()
        super().hideEvent(event)

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        color = QColor("#30D158" if self.online else "#0A84FF")
        painter.setPen(QPen(QColor(255, 255, 255, 25), 2))
        painter.drawEllipse(10, 10, 92, 92)
        painter.setPen(QPen(color, 3))
        painter.drawArc(10, 10, 92, 92, self.phase * 16, 100 * 16)
        painter.setPen(QPen(color, 2))
        painter.drawRoundedRect(27, 40, 33, 24, 4, 4)
        painter.drawLine(43, 64, 43, 70)
        painter.drawLine(34, 70, 52, 70)
        painter.drawRoundedRect(69, 35, 17, 34, 4, 4)
        painter.drawLine(61, 52, 67, 52)
        painter.end()


class DevicesPage(QFrame):
    _completed = pyqtSignal(str, object, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._service = None
        self._pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="FridayDevices")
        pool = self._pool
        self.destroyed.connect(lambda: pool.shutdown(wait=False, cancel_futures=True))
        self._busy = set()
        self._snapshot = None
        self._offer = {}
        self._deadline = 0
        self._known_ids = set()
        self._pair_generation = 0
        self._expanded = set()
        self._devices = []
        self._pending = []
        self._reduced_motion = False
        self.setObjectName("DeviceConnections")
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, True)
        self.setStyleSheet("""
            QFrame#DeviceConnections { background: rgba(18,22,31,240); border-radius: 22px; }
            QWidget { color: #F5F5F7; font-family: 'Segoe UI'; font-size: 13px; }
            QLabel { background: transparent; border: none; }
            QLabel#DevicesTitle { font-size: 28px; font-weight: 600; }
            QLabel#Muted { color: #A2ACBD; }
            QLabel#Heading { font-size: 17px; font-weight: 600; }
            QFrame#GlassCard { background: qlineargradient(x1:0,y1:0,x2:1,y2:1,stop:0 rgba(255,255,255,17),stop:1 rgba(255,255,255,7)); border: 1px solid rgba(255,255,255,24); border-radius: 18px; }
            QPushButton { background: rgba(255,255,255,14); border: 1px solid rgba(255,255,255,25); border-radius: 10px; padding: 9px 12px; }
            QPushButton:hover { background: rgba(10,132,255,35); border-color: #0A84FF; }
            QPushButton:focus, QLineEdit:focus { border-color: #0A84FF; }
            QPushButton:disabled { color: #687182; }
            QPushButton#Primary { background: #0A84FF; border-color: #0A84FF; }
            QPushButton#Danger { color: #FF6961; }
            QLineEdit, QComboBox { background: rgba(0,0,0,35); border: 1px solid rgba(255,255,255,30); border-radius: 10px; padding: 8px; min-height: 22px; }
            QScrollArea, QWidget#Content { background: transparent; border: none; }
            QProgressBar { background: rgba(255,255,255,15); border: none; border-radius: 3px; height: 6px; }
            QProgressBar::chunk { background: #0A84FF; border-radius: 3px; }
            QScrollBar:vertical { background: transparent; width: 8px; }
            QScrollBar::handle:vertical { background: rgba(255,255,255,45); border-radius: 4px; min-height: 30px; }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
            QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background: transparent; }
        """)
        root = QVBoxLayout(self)
        root.setContentsMargins(22, 22, 22, 18)
        root.setSpacing(16)
        header = QHBoxLayout()
        titles = QVBoxLayout()
        titles.addWidget(self._label("Devices", "DevicesTitle"))
        titles.addWidget(self._label("Your phone and PC, connected through Friday.", "Muted"))
        header.addLayout(titles, 1)
        self.gateway_status = self._label("Gateway unavailable", "Muted")
        header.addWidget(self.gateway_status)
        self.add_button = self._button("+ Pair phone", self._trigger_add_device, "Primary")
        header.addWidget(self.add_button)
        root.addLayout(header)

        stats = QHBoxLayout()
        self.metrics = {}
        for name in ("Connected", "Paired", "Internet access"):
            card, layout = self._card()
            value = self._label("—", "Heading")
            self.metrics[name] = value
            layout.addWidget(value)
            layout.addWidget(self._label(name, "Muted"))
            stats.addWidget(card, 1)
        root.addLayout(stats)
        self.stack = QStackedWidget()
        root.addWidget(self.stack, 1)

        overview = QWidget()
        ov = QVBoxLayout(overview)
        ov.setContentsMargins(0, 0, 0, 0)
        toolbar = QHBoxLayout()
        self.search = QLineEdit()
        self.search.setPlaceholderText("Search name, platform or capability")
        self.search.setClearButtonEnabled(True)
        self.search.textChanged.connect(self._render_devices)
        toolbar.addWidget(self.search, 1)
        self.filter = QComboBox()
        self.filter.addItems(["All devices", "Connected", "Offline"])
        self.filter.currentTextChanged.connect(self._render_devices)
        toolbar.addWidget(self.filter)
        toolbar.addWidget(self._button("Refresh", lambda: self.refresh(force=True)))
        ov.addLayout(toolbar)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        host = QWidget()
        host.setObjectName("Content")
        self.device_list = QVBoxLayout(host)
        self.device_list.setContentsMargins(0, 5, 8, 5)
        self.device_list.setSpacing(12)
        scroll.setWidget(host)
        ov.addWidget(scroll, 1)
        self.activity = self._label("Connection activity will appear here.", "Muted")
        self.activity.setWordWrap(True)
        ov.addWidget(self.activity)
        self.stack.addWidget(overview)

        pair_scroll = QScrollArea()
        pair_scroll.setWidgetResizable(True)
        pair_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        pair = QWidget()
        pair.setObjectName("Content")
        pairing = QVBoxLayout(pair)
        pairing.setContentsMargins(0, 0, 8, 0)
        pairing.addWidget(self._button("Back to devices", self._cancel_onboarding), alignment=Qt.AlignmentFlag.AlignLeft)
        self.pending_host = QWidget()
        self.pending_layout = QVBoxLayout(self.pending_host)
        self.pending_layout.setContentsMargins(0, 0, 0, 0)
        pairing.addWidget(self.pending_host)
        card, panel = self._card()
        body = QHBoxLayout()
        body.setSpacing(24)
        qr_column = QVBoxLayout()
        details = QVBoxLayout()
        self.pulse = ConnectionPulse()
        details.addWidget(self.pulse, alignment=Qt.AlignmentFlag.AlignLeft)
        details.addWidget(self._label("Pair your Android phone", "Heading"))
        instructions = self._label("1. Open Friday on your phone.\n2. Scan the QR code or enter the code.\n3. Review and approve the pairing request.", "Muted")
        instructions.setWordWrap(True)
        instructions.setMinimumHeight(60)
        details.addWidget(instructions)
        self.qr = QLabel("Preparing pairing code…")
        self.qr.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.qr.setFixedSize(212, 212)
        self.qr.setStyleSheet("background: white; color: #18202D; border-radius: 14px; padding: 6px;")
        qr_column.addWidget(self.qr, alignment=Qt.AlignmentFlag.AlignCenter)
        self.code = self._label("------", "Heading")
        self.code.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        qr_column.addWidget(self.code, alignment=Qt.AlignmentFlag.AlignCenter)
        qr_column.addStretch()
        self.pair_status = self._label("Waiting for gateway", "Muted")
        self.pair_status.setWordWrap(True)
        details.addWidget(self.pair_status)
        self.expiry = QProgressBar()
        self.expiry.setFixedHeight(6)
        self.expiry.setTextVisible(False)
        details.addWidget(self.expiry)
        self.endpoint = self._label("", "Muted")
        self.endpoint.setWordWrap(True)
        self.endpoint.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        details.addWidget(self.endpoint)
        controls = QGridLayout()
        controls.setSpacing(10)
        self.copy_code = self._button("Copy code", lambda: self._copy("code"))
        self.copy_address = self._button("Copy address", lambda: self._copy("address"))
        controls.addWidget(self.copy_code, 0, 0)
        controls.addWidget(self.copy_address, 0, 1)
        controls.addWidget(self._button("New code", self._refresh_onboarding_offer), 1, 0, 1, 2)
        details.addStretch()
        details.addLayout(controls)
        body.addLayout(qr_column)
        body.addLayout(details, 1)
        panel.addLayout(body)
        pairing.addWidget(card)
        pairing.addStretch()
        pair_scroll.setWidget(pair)
        self.stack.addWidget(pair_scroll)
        self.notice = self._label("", "Muted")
        self.notice.setWordWrap(True)
        root.addWidget(self.notice)
        self._effect = QGraphicsOpacityEffect(self.stack)
        self.stack.setGraphicsEffect(self._effect)
        self._fade = QPropertyAnimation(self._effect, b"opacity", self)
        self._fade.setDuration(180)
        self._fade.setEasingCurve(QEasingCurve.Type.OutCubic)
        self._completed.connect(self._finish)
        self.poll = QTimer(self)
        self.poll.setInterval(2000)
        self.poll.timeout.connect(self.refresh)
        self.countdown = QTimer(self)
        self.countdown.setInterval(1000)
        self.countdown.timeout.connect(self._tick_onboarding)
        self._render_devices()

    @staticmethod
    def _label(text, role=""):
        label = QLabel(text)
        label.setTextFormat(Qt.TextFormat.PlainText)
        label.setObjectName(role)
        label.setWordWrap(True)
        label.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Maximum)
        return label

    @staticmethod
    def _card():
        card = QFrame()
        card.setObjectName("GlassCard")
        card.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Minimum)
        layout = QVBoxLayout(card)
        layout.setSizeConstraint(QLayout.SizeConstraint.SetMinimumSize)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)
        return card, layout

    @staticmethod
    def _button(text, handler, role=""):
        button = QPushButton(text)
        button.setObjectName(role)
        button.setMinimumHeight(38)
        button.setCursor(Qt.CursorShape.PointingHandCursor)
        button.clicked.connect(handler)
        return button

    @staticmethod
    def _clear(layout):
        while layout.count():
            item = layout.takeAt(0)
            if item.widget():
                item.widget().hide()
                item.widget().deleteLater()

    def set_service(self, service):
        self._service = service
        self._snapshot = None
        if self.isVisible():
            self.refresh(force=True)

    def _service_obj(self):
        parent = self.parentWidget()
        return self._service or getattr(parent, "_friday_connect", None) or getattr(parent, "_brahma_connect", None)

    def _submit(self, name, operation):
        if name in self._busy:
            return
        self._busy.add(name)
        self.notice.setText("Working on it…")
        def work():
            try:
                result, error = operation(), ""
            except Exception as exc:
                result, error = None, str(exc)
            try:
                self._completed.emit(name, result, error)
            except RuntimeError:
                pass  # Window was destroyed while work was finishing.
        self._pool.submit(work)

    def _await_service(self, awaitable):
        loop = getattr(self._service_obj(), "_loop", None)
        if loop and loop.is_running():
            future = asyncio.run_coroutine_threadsafe(awaitable, loop)
            try:
                return future.result(timeout=15)
            except TimeoutError:
                future.cancel()
                raise RuntimeError("Device did not respond. Try again.")
        return asyncio.run(asyncio.wait_for(awaitable, timeout=15))

    def _finish(self, name, result, error):
        self._busy.discard(name)
        if name.startswith("pair:") and name != f"pair:{self._pair_generation}":
            return  # Ignore work from a cancelled pairing session.
        if error or result is False or isinstance(result, dict) and result.get("success") is False:
            self.notice.setText(error or (str(result.get("error") or "Action failed") if isinstance(result, dict) else "Action failed"))
            if name.startswith("pair:"):
                self._offer = {}
                self.qr.clear()
                self.qr.setText("Pairing unavailable")
                self.pair_status.setText(self.notice.text())
                self.copy_code.setEnabled(False)
                self.copy_address.setEnabled(False)
            return
        self.notice.setText("Done.")
        if name.startswith("pair:"):
            self._offer, png_data = result
            pixmap = QPixmap()
            pixmap.loadFromData(png_data, "PNG")
            pixmap = pixmap.scaled(200, 200, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            self._deadline = time.monotonic() + int(self._offer.get("expires", 0))
            self.expiry.setRange(0, max(1, int(self._offer.get("expires", 0))))
            self.code.setText(str(self._offer.get("pairing_code", "------")))
            self.qr.setPixmap(pixmap)
            self._tick_onboarding()
        self.refresh(force=True)

    def _transition(self, index):
        self.stack.setCurrentIndex(index)
        if not self._reduced_motion and self.isVisible():
            self._fade.stop()
            self._fade.setStartValue(.6)
            self._fade.setEndValue(1)
            self._fade.start()

    def _trigger_add_device(self):
        self._known_ids = {str(d.get("device_id")) for d in self._devices}
        self._transition(1)
        self.countdown.start()
        self._refresh_onboarding_offer()

    def _cancel_onboarding(self):
        self.countdown.stop()
        self._pair_generation += 1
        self._offer = {}
        self.qr.clear()
        self.code.setText("------")
        self._transition(0)

    def _refresh_onboarding_offer(self):
        if any(name.startswith("pair:") for name in self._busy):
            QTimer.singleShot(200, lambda: self._refresh_onboarding_offer() if self.isVisible() and self.stack.currentIndex() == 1 and not self._offer else None)
            return
        self._pair_generation += 1
        service = self._service_obj()
        self._offer = {}
        self.qr.clear()
        self.qr.setText("Preparing pairing code…")
        self.code.setText("------")
        self.copy_code.setEnabled(False)
        self.copy_address.setEnabled(False)
        if service is None:
            self.pair_status.setText("Gateway unavailable. Start Friday Connect, then choose New code.")
            self.qr.setText("Gateway unavailable")
            return
        def prepare():
            if not service.is_running():
                service.gateway.config.enabled = True
                service.start_background()
                for _ in range(30):
                    if service.is_running():
                        break
                    time.sleep(.1)
                else:
                    raise RuntimeError("Gateway is still starting. Try New code shortly.")
            offer = dict(service.create_pairing_offer(device_name="Friday Connect", platform="gateway"))
            import qrcode
            image = qrcode.make(json.dumps(offer, sort_keys=True, ensure_ascii=False)).convert("RGB")
            buffer = io.BytesIO()
            image.save(buffer, format="PNG")
            # Return PNG bytes; QPixmap must be constructed on the UI thread.
            return offer, buffer.getvalue()
        self._submit(f"pair:{self._pair_generation}", prepare)

    def _tick_onboarding(self):
        if self.stack.currentIndex() != 1 or not self.isVisible():
            return
        remaining = max(0, int(self._deadline - time.monotonic()))
        self.expiry.setValue(remaining)
        valid = bool(self._offer) and remaining > 0
        self.copy_code.setEnabled(valid)
        self.copy_address.setEnabled(valid)
        if self._offer:
            if valid:
                self.pair_status.setText(f"Waiting for your phone · Code expires in {remaining // 60}:{remaining % 60:02d}")
            else:
                self.qr.clear()
                self.qr.setText("Code expired\nChoose New code")
                self.pair_status.setText("Code expired. Generate a new code to continue.")
        tunnel = self._offer.get("tunnel_url")
        local = f"{self._offer.get('host', '')}:{self._offer.get('port', '')}"
        self.endpoint.setText(f"Internet: {tunnel}" if tunnel else f"Same network: {local}\nInternet tunnel is not ready yet.")

    def _copy(self, kind):
        if not self._offer or time.monotonic() >= self._deadline:
            return
        value = self._offer.get("pairing_code") if kind == "code" else self._offer.get("tunnel_url") or f"{self._offer.get('host')}:{self._offer.get('port')}"
        QApplication.clipboard().setText(str(value))
        self.notice.setText("Pairing code copied." if kind == "code" else "Connection address copied.")

    def refresh(self, force=False):
        service = self._service_obj()
        try:
            info = service.gateway_info() if service else {}
            devices = service.list_devices() if service else []
            pending = service.list_pending_requests() if service else []
            logs = service.logs() if service else []
        except Exception as exc:
            self.gateway_status.setText("Gateway unavailable")
            self.notice.setText(str(exc))
            return
        running = bool(info.get("running"))
        self.gateway_status.setText("Gateway online" if running else "Gateway offline")
        self.gateway_status.setStyleSheet(f"color: {'#30D158' if running else '#A2ACBD'};")
        self.metrics["Connected"].setText(str(sum(bool(d.get("online")) for d in devices)))
        self.metrics["Paired"].setText(str(len(devices)))
        self.metrics["Internet access"].setText("Ready" if running and info.get("tunnel_active") and info.get("tunnel_url") else "Not ready")
        self.pulse.online = running
        self.pulse.update()
        self.add_button.setText(f"Review requests ({len(pending)})" if pending else "+ Pair phone")
        if self.stack.currentIndex() == 1 and self._offer:
            new_device = next((d for d in devices if str(d.get("device_id")) not in self._known_ids and d.get("online")), None)
            if new_device:
                self.notice.setText(f"Connected to {new_device.get('name', 'your phone')}.")
                self._cancel_onboarding()
            elif info.get("tunnel_url") and info["tunnel_url"] != self._offer.get("tunnel_url") and time.monotonic() < self._deadline:
                self._refresh_onboarding_offer()
        snapshot = json.dumps([devices, pending], sort_keys=True, default=str)
        if force or snapshot != self._snapshot:
            self._snapshot = snapshot
            self._devices, self._pending = devices, pending
            self._render_devices()
            self._render_pending()
        recent = logs[-3:]
        self.activity.setText("Recent activity: " + "  ·  ".join(str(log.get("event") or log.get("type") or "Connection update") for log in recent) if recent else "Connection activity will appear here.")
        self._tick_onboarding()

    def _render_devices(self, *_):
        self._clear(self.device_list)
        query = self.search.text().casefold().split()
        mode = self.filter.currentText()
        matches = [d for d in self._devices if all(word in " ".join([str(d.get("name", "")), str(d.get("platform", "")), " ".join(d.get("capabilities") or [])]).casefold() for word in query)
                   and (mode == "All devices" or bool(d.get("online")) == (mode == "Connected"))]
        if not matches:
            card, layout = self._card()
            layout.addWidget(self._label("No matching devices" if self._devices else "Bring your phone into your workspace", "Heading"))
            note = self._label("Try a different search or filter." if self._devices else "Pair Friday on Android to access your connected PC. Internet access becomes available when the tunnel is ready.", "Muted")
            note.setWordWrap(True)
            layout.addWidget(note)
            self.device_list.addWidget(card)
        for device in matches:
            device_id = str(device.get("device_id", ""))
            card, layout = self._card()
            top = QHBoxLayout()
            title = self._label(str(device.get("name", "Device")), "Heading")
            title.setWordWrap(True)
            top.addWidget(title, 1)
            status = self._label("Connected" if device.get("online") else "Offline", "Muted")
            status.setStyleSheet(f"color: {'#30D158' if device.get('online') else '#A2ACBD'};")
            top.addWidget(status)
            top.addWidget(self._button("Less" if device_id in self._expanded else "Details", lambda checked=False, target=device_id: self._toggle_details(target)))
            layout.addLayout(top)
            battery = device.get("battery")
            summary = f"{device.get('platform', 'Unknown').title()} · Battery: {battery}%" if battery is not None else f"{device.get('platform', 'Unknown').title()} · Battery not reported"
            label = self._label(summary, "Muted")
            label.setWordWrap(True)
            layout.addWidget(label)
            if device_id in self._expanded:
                for text in (
                    f"Last seen: {device.get('last_seen') or 'Not reported'}",
                    "Capabilities: " + (", ".join(device.get("capabilities") or []) or "Not reported"),
                    "Permissions: " + (", ".join(device.get("permissions") or []) or "Not reported"),
                ):
                    label = self._label(text, "Muted")
                    label.setWordWrap(True)
                    layout.addWidget(label)
                actions = QGridLayout()
                actions.setSpacing(12)
                actions.addWidget(self._button("Rename", lambda checked=False, d=device: self._rename(d)), 0, 0)
                action = "disconnect" if device.get("online") else "reconnect"
                actions.addWidget(self._button(action.title(), lambda checked=False, target=device_id, a=action: self._device_action(target, a)), 0, 1)
                actions.addWidget(self._button("Forget device", lambda checked=False, d=device: self._forget(d), "Danger"), 1, 0, 1, 2)
                layout.addLayout(actions)
            self.device_list.addWidget(card)
        self.device_list.addStretch()

    def _toggle_details(self, device_id):
        if device_id in self._expanded:
            self._expanded.remove(device_id)
        else:
            self._expanded.add(device_id)
        self._render_devices()
        self._transition(0)

    def _render_pending(self):
        self._clear(self.pending_layout)
        for request in self._pending:
            card, layout = self._card()
            label = self._label(f"Pairing request: {request.get('device_name', 'Phone')}", "Heading")
            label.setWordWrap(True)
            layout.addWidget(label)
            label = self._label(f"{request.get('platform', '')} · {request.get('ip', '')}", "Muted")
            label.setWordWrap(True)
            layout.addWidget(label)
            row = QHBoxLayout()
            for action in ("approve", "reject"):
                button = self._button(action.title(), lambda checked=False, a=action, target=request.get("pending_id"): self._pending_action(target, a), "Primary" if action == "approve" else "")
                button.setEnabled(f"pending:{request.get('pending_id')}" not in self._busy)
                row.addWidget(button)
            layout.addLayout(row)
            self.pending_layout.addWidget(card)
        self.pending_host.setVisible(bool(self._pending))

    def _pending_action(self, pending_id, action):
        service = self._service_obj()
        if service:
            method = service.approve_pending_request if action == "approve" else service.reject_pending_request
            self._submit(f"pending:{pending_id}", lambda: self._await_service(method(pending_id)))
            self._render_pending()

    def _rename(self, device):
        name, accepted = QInputDialog.getText(self, "Rename device", "Device name", text=str(device.get("name", "")))
        service = self._service_obj()
        if accepted and name.strip() and service:
            self._submit(f"device:{device['device_id']}", lambda: service.rename_device(device["device_id"], name.strip()))

    def _device_action(self, device_id, action):
        service = self._service_obj()
        if service:
            method = service.disconnect_device if action == "disconnect" else service.reconnect_device
            self._submit(f"device:{device_id}", lambda: self._await_service(method(device_id)))

    def _forget(self, device):
        if QMessageBox.question(self, "Forget device?", f"Remove {device.get('name', 'this device')}? It will need to pair again.") != QMessageBox.StandardButton.Yes:
            return
        service = self._service_obj()
        if service:
            def forget():
                self._await_service(service.disconnect_device(device["device_id"]))
                removed = service.gateway.device_manager.remove(device["device_id"])
                return {"success": bool(removed), "error": "Device could not be removed."}
            self._submit(f"device:{device['device_id']}", forget)

    def showEvent(self, event):
        super().showEvent(event)
        try:
            from memory.config_manager import get_setting
            self._reduced_motion = bool(get_setting("reduce_motion_enabled", False))
        except (OSError, ImportError):
            pass
        self.pulse.reduced_motion = self._reduced_motion
        if self._reduced_motion:
            self.pulse.timer.stop()
            self._fade.stop()
            self._effect.setOpacity(1)
        self.poll.start()
        if self.stack.currentIndex() == 1:
            self.countdown.start()
        self.refresh(force=True)

    def hideEvent(self, event):
        self.poll.stop()
        self.countdown.stop()
        self.pulse.timer.stop()
        self._fade.stop()
        self._effect.setOpacity(1)
        super().hideEvent(event)
