from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import shutil
import socket
import string
import threading
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, Query, UploadFile, File
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse, FileResponse
import uvicorn

from .capability_manager import CapabilityManager
from .authentication import constant_time_equals, hash_secret
from .command_router import CommandRouter
from .device_manager import DeviceManager
from .discovery import GatewayDiscovery, local_ip
from .models import PairingOffer
from .pairing import PairingManager
from .protocol import ProtocolTypes, build_message, validate_message, now_iso, new_request_id
from .tunnel import FridayTunnelManager
from .websocket import ConnectionHub

logger = logging.getLogger("FridayGateway")


def _default_config_path(base_dir: Path) -> Path:
    return Path(base_dir) / "config" / "friday_connect.json"


def _default_registry_path(base_dir: Path) -> Path:
    return Path(base_dir) / "config" / "friday_connect" / "devices.json"


@dataclass(slots=True)
class FridayGatewayConfig:
    host: str = "0.0.0.0"
    port: int = 8765
    enabled: bool = True
    advertise: bool = True
    service_name: str = "_FRIDAY._tcp.local."
    pairing_ttl_seconds: int = 300
    request_timeout_seconds: int = 30
    tunnel_enabled: bool = True
    tunnel_token: str | None = None
    config_path: Path | None = None
    registry_path: Path | None = None

    @classmethod
    def load(cls, base_dir: Path, override: dict[str, Any] | None = None) -> "FridayGatewayConfig":
        base_dir = Path(base_dir)
        data: dict[str, Any] = {
            "host": "0.0.0.0",
            "port": 8765,
            "enabled": True,
            "advertise": True,
            "service_name": "_FRIDAY._tcp.local.",
            "pairing_ttl_seconds": 300,
            "request_timeout_seconds": 30,
            "tunnel_enabled": True,
            "tunnel_token": None,
        }
        config_path = _default_config_path(base_dir)
        if config_path.exists():
            try:
                data.update(json.loads(config_path.read_text(encoding="utf-8")))
            except Exception:
                pass
        if override:
            data.update({k: v for k, v in override.items() if v is not None})
        return cls(
            host=str(data.get("host", "0.0.0.0")),
            port=int(data.get("port", 8765)),
            enabled=bool(data.get("enabled", True)),
            advertise=bool(data.get("advertise", True)),
            service_name=str(data.get("service_name", "_FRIDAY._tcp.local.")),
            pairing_ttl_seconds=int(data.get("pairing_ttl_seconds", 300)),
            request_timeout_seconds=int(data.get("request_timeout_seconds", 30)),
            tunnel_enabled=bool(data.get("tunnel_enabled", True)),
            tunnel_token=data.get("tunnel_token"),
            config_path=config_path,
            registry_path=_default_registry_path(base_dir),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "host": self.host,
            "port": self.port,
            "enabled": self.enabled,
            "advertise": self.advertise,
            "service_name": self.service_name,
            "pairing_ttl_seconds": self.pairing_ttl_seconds,
            "request_timeout_seconds": self.request_timeout_seconds,
            "tunnel_enabled": self.tunnel_enabled,
            "tunnel_token": self.tunnel_token,
        }

    def save(self) -> None:
        if self.config_path is None:
            return
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")


class FridayGateway:
    def __init__(self, base_dir: Path, config: FridayGatewayConfig | None = None):
        self.base_dir = Path(base_dir)
        self.config = config or FridayGatewayConfig.load(self.base_dir)
        self.device_manager = DeviceManager(self.config.registry_path or _default_registry_path(self.base_dir))
        self.capability_manager = CapabilityManager()
        self.hub = ConnectionHub()
        self.command_router = CommandRouter(self.device_manager, self.hub, self.capability_manager)
        self.pairing_manager = PairingManager(self.config.service_name, self.config.pairing_ttl_seconds)
        self.discovery = GatewayDiscovery(self.config.service_name)
        self.tunnel_manager = FridayTunnelManager(self.base_dir)
        self._running = False
        self._shutdown = threading.Event()
        self._server: uvicorn.Server | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._log: list[dict[str, Any]] = []
        self._pending_requests: dict[str, dict[str, Any]] = {}
        self.on_chat_message = None
        self.app = self._build_app()

    def is_running(self) -> bool:
        return self._running and not self._shutdown.is_set()

    def request_shutdown(self) -> None:
        self._shutdown.set()
        self.tunnel_manager.stop_tunnel()
        if self._server is not None:
            self._server.should_exit = True

    def _append_log(self, event_type: str, **payload: Any) -> None:
        entry = {"type": event_type, "timestamp": now_iso(), **payload}
        self._log.append(entry)
        self._log = self._log[-200:]

    def log(self) -> list[dict[str, Any]]:
        return list(self._log)

    def list_devices(self) -> list[dict[str, Any]]:
        return self.device_manager.list_devices()

    def get_device(self, device_or_target: str) -> dict[str, Any] | None:
        query = str(device_or_target or "").strip()
        if not query:
            return None
        record = self.device_manager.get(query)
        if record is None:
            matches = self.device_manager.resolve(query)
            record = matches[0] if len(matches) == 1 else None
        return record.to_dict() if record else None

    def resolve_devices(self, query: str) -> list[dict[str, Any]]:
        return [item.to_dict() for item in self.device_manager.resolve(query)]

    def rename_device(self, device_or_target: str, new_name: str) -> dict[str, Any]:
        query = str(device_or_target or "").strip()
        record = self.device_manager.get(query)
        if record is None:
            matches = self.device_manager.resolve(query)
            if len(matches) == 1:
                record = matches[0]
            elif len(matches) > 1:
                return {
                    "success": False,
                    "error": "Multiple devices matched the request.",
                    "error_code": "MULTIPLE_DEVICES",
                    "matches": [item.to_dict() for item in matches],
                }
        if record is None:
            return {"success": False, "error": "Device not found.", "error_code": "DEVICE_NOT_FOUND"}
        renamed = self.device_manager.rename(record.device_id, new_name)
        if renamed is None:
            return {"success": False, "error": "Device not found.", "error_code": "DEVICE_NOT_FOUND"}
        self._append_log("DEVICE_RENAMED", device_id=renamed.device_id, name=renamed.name)
        return {"success": True, "device": renamed.to_dict()}

    def get_capabilities(self, device_or_target: str) -> dict[str, Any]:
        record = self.device_manager.get(str(device_or_target or "").strip())
        if record is None:
            matches = self.device_manager.resolve(device_or_target)
            if len(matches) == 1:
                record = matches[0]
            elif len(matches) > 1:
                return {
                    "success": False,
                    "error": "Multiple devices matched the request.",
                    "error_code": "MULTIPLE_DEVICES",
                    "matches": [item.to_dict() for item in matches],
                }
        if record is None:
            return {"success": False, "error": "Device not found.", "error_code": "DEVICE_NOT_FOUND"}
        return {
            "success": True,
            "device": record.to_dict(),
            "capabilities": list(record.capabilities),
            "permissions": list(record.permissions),
        }

    async def disconnect_device(self, device_or_target: str, *, reason: str = "Disconnected by Friday") -> dict[str, Any]:
        query = str(device_or_target or "").strip()
        matches = self.device_manager.resolve(query)
        record = None
        if self.device_manager.get(query):
            record = self.device_manager.get(query)
        elif len(matches) == 1:
            record = matches[0]
        elif len(matches) > 1:
            return {
                "success": False,
                "error": "Multiple devices matched the request.",
                "error_code": "MULTIPLE_DEVICES",
                "matches": [item.to_dict() for item in matches],
            }
        if record is None:
            return {"success": False, "error": "Device not found.", "error_code": "DEVICE_NOT_FOUND"}

        disconnected = False
        try:
            disconnected = await self.hub.close_device(record.device_id, reason=reason)
        except Exception:
            disconnected = False
        self.device_manager.mark_offline(record.device_id)
        self._append_log("DEVICE_DISCONNECTED", device_id=record.device_id, name=record.name, forced=disconnected)
        return {"success": True, "device": record.to_dict(), "disconnected": disconnected}

    async def reconnect_device(self, device_or_target: str) -> dict[str, Any]:
        query = str(device_or_target or "").strip()
        matches = self.device_manager.resolve(query)
        record = self.device_manager.get(query)
        if record is None and len(matches) == 1:
            record = matches[0]
        elif record is None and len(matches) > 1:
            return {
                "success": False,
                "error": "Multiple devices matched the request.",
                "error_code": "MULTIPLE_DEVICES",
                "matches": [item.to_dict() for item in matches],
            }
        if record is None:
            return {"success": False, "error": "Device not found.", "error_code": "DEVICE_NOT_FOUND"}
        state = await self.hub.get(record.device_id)
        if state is None:
            return {
                "success": False,
                "device": record.to_dict(),
                "error": f"Your {record.name} is currently offline.",
                "error_code": "DEVICE_OFFLINE",
            }
        self.device_manager.touch(record.device_id)
        self._append_log("DEVICE_RECONNECTED", device_id=record.device_id, name=record.name)
        return {"success": True, "device": record.to_dict(), "reconnected": True}

    def create_pairing_offer(self, *, device_name: str = "Unknown Device", platform: str = "unknown") -> dict[str, Any]:
        advertised_host = local_ip() if self.config.host in {"0.0.0.0", "::"} else self.config.host
        tunnel_url = self.tunnel_manager.get_public_url()
        offer = self.pairing_manager.create_offer(advertised_host, self.config.port, tunnel_url=tunnel_url)
        self._append_log("PAIRING_REQUEST", device=device_name, platform=platform, code=offer.pairing_code, tunnel_url=tunnel_url)
        res = offer.to_dict()
        res["tunnel_url"] = tunnel_url
        return res

    def list_pending_requests(self) -> list[dict[str, Any]]:
        return [
            {
                "pending_id": pending_id,
                "timestamp": item.get("timestamp", ""),
                "device_name": item.get("device_name", "Unknown Device"),
                "platform": item.get("platform", "unknown"),
                "os_version": item.get("os_version", ""),
                "agent_version": item.get("agent_version", ""),
                "capabilities": list(item.get("capabilities") or []),
                "permissions": list(item.get("permissions") or []),
                "ip": item.get("ip", ""),
            }
            for pending_id, item in self._pending_requests.items()
        ]

    async def approve_pending_request(self, pending_id: str) -> dict[str, Any]:
        item = self._pending_requests.pop(str(pending_id), None)
        if item is None:
            return {"success": False, "error": "Pending request not found."}
        websocket = item.get("websocket")
        if websocket is None:
            return {"success": False, "error": "Device is no longer connected."}

        record, secret = self.device_manager.create_from_pairing(
            name=str(item.get("device_name") or "Unknown Device"),
            platform=str(item.get("platform") or "unknown"),
            os_version=str(item.get("os_version") or ""),
            agent_version=str(item.get("agent_version") or ""),
            ip=str(item.get("ip") or ""),
            battery=item.get("battery"),
            capabilities=list(item.get("capabilities") or []),
            permissions=list(item.get("permissions") or []),
            metadata=dict(item.get("metadata") or {}),
        )
        pairing_token = str(item.get("pairing_token") or "")
        if pairing_token:
            self.pairing_manager.approve(pairing_token)
        self._append_log("PAIR_APPROVED", device=record.device_id, name=record.name, platform=record.platform)
        await websocket.send_json(
            build_message(
                ProtocolTypes.PAIR_APPROVED,
                {"device": record.to_dict(), "device_secret": secret},
                request_id=str(item.get("request_id") or ""),
            )
        )
        return {"success": True, "device": record.to_dict(), "device_secret": secret}

    def reject_pending_request(self, pending_id: str) -> bool:
        item = self._pending_requests.pop(str(pending_id), None)
        if item is None:
            return False
        websocket = item.get("websocket")
        if websocket is not None:
            try:
                asyncio.create_task(
                    websocket.send_json(
                        build_message(
                            ProtocolTypes.ERROR,
                            {"error": "Pairing request rejected by user."},
                            request_id=str(item.get("request_id") or ""),
                        )
                    )
                )
            except Exception:
                pass
        self._append_log("PAIR_REJECTED", pending_id=pending_id)
        return True

    async def route_command(self, target: str, action: str, parameters: dict[str, Any] | None = None) -> dict[str, Any]:
        return await self.command_router.route(target, action, parameters or {}, timeout=self.config.request_timeout_seconds)

    async def _pair_device(self, payload: dict[str, Any], websocket: WebSocket) -> dict[str, Any]:
        offer_token = str(payload.get("pairing_token") or "").strip()
        offer_code = str(payload.get("pairing_code") or "").strip()
        offer = self.pairing_manager.get_offer(offer_token) if offer_token else self.pairing_manager.get_offer_by_code(offer_code)
        if offer is None:
            return {"success": False, "error": "Invalid or expired pairing token."}

        device_name = str(payload.get("device_name") or "Unknown Device").strip()
        platform = str(payload.get("platform") or "unknown").strip()
        os_version = str(payload.get("os_version") or "")
        agent_version = str(payload.get("agent_version") or "")
        battery = payload.get("battery")
        capabilities = list(payload.get("capabilities") or [])
        permissions = list(payload.get("permissions") or [])
        metadata = dict(payload.get("metadata") or {})
        ip = websocket.client.host if websocket.client else ""
        record, secret = self.device_manager.create_from_pairing(
            name=device_name,
            platform=platform,
            os_version=os_version,
            agent_version=agent_version,
            ip=ip,
            battery=int(battery) if isinstance(battery, (int, float, str)) and str(battery).isdigit() else None,
            capabilities=capabilities,
            permissions=permissions,
            metadata=metadata,
        )
        self.pairing_manager.approve(offer.pairing_token)
        self._append_log("PAIR_APPROVED", device=record.device_id, name=record.name, platform=record.platform)
        return {
            "success": True,
            "device": record.to_dict(),
            "device_secret": secret,
            "pairing_token": offer.pairing_token,
            "tunnel_url": self.tunnel_manager.get_public_url(),
        }

    def _build_app(self) -> FastAPI:
        app = FastAPI(docs_url=None, redoc_url=None)
        from .mobile_api import MobileAPI
        self.mobile_api = MobileAPI(self)
        app.include_router(self.mobile_api.router)

        @app.middleware("http")
        async def require_paired_device(request: Request, call_next):
            path = request.url.path
            # A tunnel connects from loopback; never treat loopback as authorization.
            # Device administration is performed through the desktop service API.
            if path.startswith("/gateway/") and path != "/gateway/info":
                return JSONResponse({"error": "Use the Friday desktop to manage devices."}, status_code=403)
            if path.startswith("/api/remote/"):
                record = self.device_manager.get(request.headers.get("X-Friday-Device", ""))
                authorization = request.headers.get("Authorization", "")
                secret = authorization[7:] if authorization.startswith("Bearer ") else ""
                if not secret or record is None or record.revoked or not constant_time_equals(record.secret_hash, hash_secret(secret)):
                    return JSONResponse({"error": "Pair this phone with Friday first."}, status_code=401)
            return await call_next(request)

        @app.get("/health")
        async def health():
            return {"ok": True, "running": self.is_running(), "host": self.config.host, "port": self.config.port}

        @app.get("/gateway/info")
        async def info():
            return {
                "ok": True,
                "service": "Friday Connect",
                "host": self.config.host,
                "port": self.config.port,
                "advertise": self.config.advertise,
                "paired_devices": len(self.device_manager.list_devices()),
                "log_entries": len(self._log),
            }

        @app.get("/gateway/pair")
        async def get_pairing_offer():
            return self.create_pairing_offer()

        @app.get("/gateway/devices")
        async def list_devices():
            return {"ok": True, "devices": self.device_manager.list_devices()}

        @app.post("/gateway/devices/{device_id}/revoke")
        async def revoke_device(device_id: str):
            if not self.device_manager.revoke(device_id):
                return JSONResponse({"ok": False, "error": "Device not found."}, status_code=404)
            self._append_log("DEVICE_REVOKED", device_id=device_id)
            return {"ok": True}

        @app.post("/gateway/devices/{device_id}/forget")
        async def forget_device(device_id: str):
            if not self.device_manager.remove(device_id):
                return JSONResponse({"ok": False, "error": "Device not found."}, status_code=404)
            self._append_log("DEVICE_FORGOTTEN", device_id=device_id)
            return {"ok": True}

        @app.get("/gateway/logs")
        async def logs():
            return {"ok": True, "entries": self.log()}

        @app.get("/gateway/pending")
        async def pending_requests():
            return {"ok": True, "requests": self.list_pending_requests()}

        @app.post("/gateway/pending/{pending_id}/approve")
        async def approve_pending(pending_id: str):
            result = await self.approve_pending_request(pending_id)
            if not result.get("success"):
                return JSONResponse(result, status_code=404)
            return result

        @app.post("/gateway/pending/{pending_id}/reject")
        async def reject_pending(pending_id: str):
            if not self.reject_pending_request(pending_id):
                return JSONResponse({"ok": False, "error": "Pending request not found."}, status_code=404)
            return {"ok": True}

        @app.get("/gateway/tunnel")
        async def get_tunnel_status():
            url = self.tunnel_manager.get_public_url()
            return {
                "ok": True,
                "active": self.tunnel_manager.is_active(),
                "public_url": url,
                "type": "cloudflare",
            }

        @app.get("/api/remote/files/browse")
        async def browse_files(path: str = Query(default="")):
            clean_path = path.strip()
            if not clean_path:
                roots = []
                for letter in string.ascii_uppercase:
                    drive = f"{letter}:\\"
                    if os.path.exists(drive):
                        roots.append({
                            "name": f"Local Disk ({letter}:)",
                            "path": drive,
                            "is_dir": True,
                            "type": "drive",
                            "size_bytes": 0,
                        })
                home = Path.home()
                quick_folders = ["Desktop", "Downloads", "Documents", "Pictures", "Videos", "Music"]
                for qf in quick_folders:
                    p = home / qf
                    if p.exists():
                        roots.append({
                            "name": qf,
                            "path": str(p),
                            "is_dir": True,
                            "type": "quick_access",
                            "size_bytes": 0,
                        })
                return {"ok": True, "current_path": "", "entries": roots}

            target = Path(clean_path).resolve()
            if not target.exists():
                return JSONResponse({"ok": False, "error": "Path does not exist."}, status_code=404)
            if not target.is_dir():
                return JSONResponse({"ok": False, "error": "Path is not a directory."}, status_code=400)

            entries = []
            try:
                with os.scandir(target) as it:
                    for entry in it:
                        try:
                            is_dir = entry.is_dir(follow_symlinks=False)
                            stat = entry.stat(follow_symlinks=False)
                            entries.append({
                                "name": entry.name,
                                "path": entry.path,
                                "is_dir": is_dir,
                                "size_bytes": stat.st_size if not is_dir else 0,
                                "modified": int(stat.st_mtime),
                                "extension": Path(entry.name).suffix.lower() if not is_dir else "",
                            })
                        except Exception:
                            continue
                entries.sort(key=lambda x: (not x["is_dir"], x["name"].lower()))
                parent = str(target.parent) if target.parent != target else ""
                return {"ok": True, "current_path": str(target), "parent": parent, "entries": entries}
            except Exception as exc:
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=500)

        @app.get("/api/remote/files/download")
        async def download_file_or_folder(path: str = Query(...)):
            target = Path(path.strip()).resolve()
            if not target.exists():
                return JSONResponse({"ok": False, "error": "Path does not exist."}, status_code=404)

            if target.is_file():
                return FileResponse(
                    str(target),
                    filename=target.name,
                    media_type="application/octet-stream"
                )

            # Directory: dynamic on-the-fly zip stream
            folder_name = target.name or "folder"
            zip_filename = f"{folder_name}.zip"

            def _zip_generator():
                class _IterBuffer(io.RawIOBase):
                    def __init__(self):
                        self._buffer = bytearray()
                    def write(self, b):
                        self._buffer.extend(b)
                        return len(b)
                    def read_all(self):
                        data = bytes(self._buffer)
                        self._buffer.clear()
                        return data

                buf = _IterBuffer()
                with zipfile.ZipFile(buf, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
                    for root, _, files in os.walk(target):
                        for f in files:
                            full_p = Path(root) / f
                            try:
                                rel_p = full_p.relative_to(target)
                                zf.write(full_p, arcname=str(rel_p))
                                chunk = buf.read_all()
                                if chunk:
                                    yield chunk
                            except Exception:
                                continue
                final_chunk = buf.read_all()
                if final_chunk:
                    yield final_chunk

            return StreamingResponse(
                _zip_generator(),
                media_type="application/zip",
                headers={"Content-Disposition": f'attachment; filename="{zip_filename}"'}
            )

        @app.post("/api/remote/files/upload")
        async def upload_file_to_pc(target_dir: str = Query(default=""), file: UploadFile = File(...)):
            save_dir = Path(target_dir.strip()).resolve() if target_dir.strip() else (Path.home() / "Downloads" / "FromMobile")
            save_dir.mkdir(parents=True, exist_ok=True)
            filename = Path(str(file.filename or "attachment").replace("\\", "/")).name
            if not filename or filename in (".", ".."):
                return JSONResponse({"error": "Invalid filename"}, status_code=400)
            destination = save_dir / filename
            with open(destination, "wb") as f:
                shutil.copyfileobj(file.file, f)
            return {"ok": True, "saved_path": str(destination), "size": destination.stat().st_size}

        @app.post("/api/remote/command")
        async def execute_remote_command(request: Request):
            body = await request.json()
            cmd_text = str(body.get("text") or "").strip()
            if not cmd_text:
                return JSONResponse({"ok": False, "error": "Empty command text."}, status_code=400)
            if self.on_chat_message:
                res = self.on_chat_message(cmd_text, device_id="remote_api")
                return {"ok": True, "response": res or "Command dispatched to Friday."}
            return {"ok": True, "response": "Friday received your command."}

        @app.get("/api/remote/pc/vitals")
        async def get_pc_vitals():
            import psutil
            import platform
            cpu = psutil.cpu_percent(interval=None)
            ram = psutil.virtual_memory()
            battery = psutil.sensors_battery()
            active_win = ""
            try:
                import pygetwindow as gw
                win = gw.getActiveWindow()
                if win:
                    active_win = win.title
            except Exception:
                pass
            return {
                "ok": True,
                "cpu_percent": cpu,
                "ram_percent": ram.percent,
                "ram_used_mb": int(ram.used // (1024 * 1024)),
                "ram_total_mb": int(ram.total // (1024 * 1024)),
                "battery_percent": int(battery.percent) if battery else None,
                "battery_charging": bool(battery.power_plugged) if battery else None,
                "active_window": active_win,
                "hostname": platform.node(),
                "os": f"{platform.system()} {platform.release()}",
            }

        @app.get("/api/remote/pc/screenshot")
        async def get_pc_screenshot():
            import io
            buf = io.BytesIO()
            grabbed = False
            try:
                from PIL import ImageGrab
                img = ImageGrab.grab()
                img.save(buf, format="JPEG", quality=60)
                grabbed = True
            except Exception:
                pass

            if not grabbed:
                try:
                    import pyautogui
                    img = pyautogui.screenshot()
                    img.save(buf, format="JPEG", quality=60)
                    grabbed = True
                except Exception:
                    pass

            if not grabbed:
                return JSONResponse({"ok": False, "error": "Unable to capture screen in current desktop session."}, status_code=500)

            buf.seek(0)
            return StreamingResponse(buf, media_type="image/jpeg")

        @app.post("/api/remote/pc/execute")
        async def execute_pc_terminal(request: Request):
            body = await request.json()
            cmd = str(body.get("command") or "").strip()
            if not cmd:
                return JSONResponse({"ok": False, "error": "command is required."}, status_code=400)
            try:
                import subprocess
                res = subprocess.run(
                    ["powershell", "-NoProfile", "-Command", cmd],
                    capture_output=True,
                    text=True,
                    timeout=20
                )
                return {
                    "ok": True,
                    "stdout": res.stdout,
                    "stderr": res.stderr,
                    "exit_code": res.returncode
                }
            except subprocess.TimeoutExpired:
                return JSONResponse({"ok": False, "error": "Command timed out after 20 seconds."}, status_code=408)
            except Exception as exc:
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=500)

        @app.post("/api/remote/pc/media")
        async def control_pc_media(request: Request):
            body = await request.json()
            action = str(body.get("action") or "").lower().strip()
            level = body.get("level")
            try:
                import pyautogui
                if action == "volume_up":
                    for _ in range(5): pyautogui.press("volumeup")
                elif action == "volume_down":
                    for _ in range(5): pyautogui.press("volumedown")
                elif action == "mute":
                    pyautogui.press("volumemute")
                elif action == "play_pause":
                    pyautogui.press("playpause")
                elif action == "next":
                    pyautogui.press("nexttrack")
                elif action == "prev":
                    pyautogui.press("prevtrack")
                elif action == "volume_set" and level is not None:
                    from actions.computer_settings import volume_set
                    volume_set(int(level))
                else:
                    return JSONResponse({"ok": False, "error": f"Unknown media action: {action}"}, status_code=400)
                return {"ok": True, "action": action}
            except Exception as exc:
                return JSONResponse({"ok": False, "error": str(exc)}, status_code=500)

        @app.post("/api/remote/pc/app")
        async def control_pc_app(request: Request):
            body = await request.json()
            action = str(body.get("action") or "").lower().strip()
            app_name = str(body.get("app_name") or "").strip()
            if action == "launch":
                if not app_name:
                    return JSONResponse({"ok": False, "error": "app_name is required."}, status_code=400)
                from actions.open_app import open_app
                res = open_app({"app_name": app_name})
                return {"ok": True, "result": res}
            elif action == "kill":
                if not app_name:
                    return JSONResponse({"ok": False, "error": "app_name is required."}, status_code=400)
                import subprocess
                pname = app_name if app_name.lower().endswith(".exe") else f"{app_name}.exe"
                subprocess.run(["taskkill", "/IM", pname, "/F"], capture_output=True)
                return {"ok": True, "killed": pname}
            elif action == "list":
                common = ["chrome", "code", "spotify", "whatsapp", "notepad", "calc", "powershell", "explorer", "discord"]
                return {"ok": True, "common_apps": common}
            return JSONResponse({"ok": False, "error": f"Unknown action: {action}"}, status_code=400)

        @app.post("/api/remote/pc/power")
        async def control_pc_power(request: Request):
            body = await request.json()
            action = str(body.get("action") or "").lower().strip()
            import subprocess
            if action == "lock":
                subprocess.run(["rundll32.exe", "user32.dll,LockWorkStation"])
                return {"ok": True, "message": "PC Locked"}
            elif action == "sleep":
                subprocess.run(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
                return {"ok": True, "message": "PC entering sleep"}
            elif action == "restart":
                subprocess.run(["shutdown", "/r", "/t", "5"])
                return {"ok": True, "message": "PC restarting in 5s"}
            elif action == "shutdown":
                subprocess.run(["shutdown", "/s", "/t", "10"])
                return {"ok": True, "message": "PC shutting down in 10s"}
            return JSONResponse({"ok": False, "error": f"Unknown power action: {action}"}, status_code=400)

        @app.websocket("/ws")
        async def ws_endpoint(websocket: WebSocket):
            await websocket.accept()
            state = await self.hub.attach(websocket)
            device_id = ""
            try:
                while True:
                    incoming = await websocket.receive_json()
                    valid, error = validate_message(incoming)
                    if not valid:
                        await websocket.send_json(build_message(ProtocolTypes.ERROR, {"error": error}))
                        continue
                    msg_type = str(incoming["type"]).strip().lower()
                    request_id = str(incoming["request_id"])
                    payload = dict(incoming.get("payload") or {})

                    if msg_type == ProtocolTypes.PING:
                        await websocket.send_json(build_message(ProtocolTypes.PONG, {"status": "ok"}, request_id=request_id))
                        continue

                    if msg_type == ProtocolTypes.HELLO:
                        pending_id = new_request_id()
                        self._pending_requests[pending_id] = {
                            "request_id": pending_id,
                            "websocket": websocket,
                            "timestamp": now_iso(),
                            "device_name": str(payload.get("device_name") or "Unknown Device"),
                            "platform": str(payload.get("platform") or "unknown"),
                            "os_version": str(payload.get("os_version") or ""),
                            "agent_version": str(payload.get("agent_version") or ""),
                            "capabilities": list(payload.get("capabilities") or []),
                            "permissions": list(payload.get("permissions") or []),
                            "battery": payload.get("battery"),
                            "metadata": dict(payload.get("metadata") or {}),
                            "ip": websocket.client.host if websocket.client else "",
                        }
                        await websocket.send_json(
                            build_message(
                                ProtocolTypes.PAIR_REQUEST,
                                {
                                    "pending_id": pending_id,
                                    "message": "Pairing request received. Awaiting user approval in Friday.",
                                    "device_name": self._pending_requests[pending_id]["device_name"],
                                    "platform": self._pending_requests[pending_id]["platform"],
                                },
                                request_id=request_id,
                            )
                        )
                        continue

                    if msg_type == ProtocolTypes.AUTHENTICATE:
                        device_id = str(payload.get("device_id") or "").strip()
                        secret = str(payload.get("device_secret") or "").strip()
                        record = self.device_manager.authenticate(
                            device_id,
                            secret,
                            ip=websocket.client.host if websocket.client else "",
                        )
                        if record is None:
                            device_id = ""
                            await websocket.send_json(build_message(ProtocolTypes.ERROR, {"error": "Authentication failed."}, request_id=request_id))
                            continue
                        await self.hub.register(websocket, record.device_id)
                        self.device_manager.touch(record.device_id, ip=websocket.client.host if websocket.client else "")
                        self._append_log("DEVICE_CONNECTED", device_id=record.device_id, name=record.name)
                        await websocket.send_json(build_message(ProtocolTypes.DEVICE_ONLINE, {"device": record.to_dict()}, request_id=request_id))
                        await websocket.send_json(build_message(ProtocolTypes.CAPABILITIES, {"device_id": record.device_id, "capabilities": record.capabilities}, request_id=request_id))
                        continue

                    if msg_type == ProtocolTypes.PAIR_REQUEST:
                        result = await self._pair_device(payload, websocket)
                        await websocket.send_json(build_message(ProtocolTypes.PAIR_APPROVED if result.get("success") else ProtocolTypes.ERROR, result, request_id=request_id))
                        continue

                    record = self.device_manager.get(device_id) if device_id else None
                    if record is None or record.revoked:
                        await websocket.send_json(build_message(ProtocolTypes.ERROR, {"error": "Authentication required."}, request_id=request_id))
                        continue

                    if msg_type == ProtocolTypes.RESULT:
                        await self.hub.resolve_pending(device_id or str(payload.get("device_id") or ""), request_id, payload)
                        continue

                    if msg_type == ProtocolTypes.ERROR:
                        await self.hub.reject_pending(device_id or str(payload.get("device_id") or ""), request_id, str(payload.get("error") or "Unknown error"))
                        continue

                    if msg_type == ProtocolTypes.EVENT:
                        self._append_log("EVENT", device_id=device_id, payload=payload)
                        continue

                    if msg_type == ProtocolTypes.CHAT_MESSAGE:
                        chat_text = payload.get("text")
                        if chat_text:
                            self._append_log("CHAT_MESSAGE", device_id=device_id, text=chat_text)
                            if self.on_chat_message:
                                def _dispatch(t=chat_text, dev=device_id, ws=websocket, req=request_id):
                                    try:
                                        res = self.on_chat_message(t, device_id=dev)
                                        if res and isinstance(res, str):
                                            reply = build_message(
                                                ProtocolTypes.CHAT_MESSAGE,
                                                {"role": "assistant", "text": res},
                                                request_id=req
                                            )
                                            if self._loop and self._loop.is_running():
                                                asyncio.run_coroutine_threadsafe(ws.send_json(reply), self._loop)
                                    except Exception as ex:
                                        logger.error(f"[Gateway] Error executing on_chat_message: {ex}")
                                threading.Thread(target=_dispatch, daemon=True).start()
                        continue

                    if msg_type == ProtocolTypes.DEVICE_OFFLINE:
                        if device_id:
                            self.device_manager.mark_offline(device_id)
                            self._append_log("DEVICE_DISCONNECTED", device_id=device_id)
                        continue

                    await websocket.send_json(build_message(ProtocolTypes.ERROR, {"error": f"Unsupported message type: {msg_type}."}, request_id=request_id))
            except WebSocketDisconnect:
                pass
            finally:
                detached = await self.hub.unregister(websocket)
                if detached:
                    self.device_manager.mark_offline(detached)
                    self._append_log("DEVICE_DISCONNECTED", device_id=detached)

        return app

    async def serve(self) -> None:
        if not self.config.enabled:
            return
        self._running = True
        self._loop = asyncio.get_running_loop()
        advertised = False
        if self.config.advertise:
            advertised = self.discovery.start(host=self.config.host, port=self.config.port, properties={"service": "friday", "version": "1"})
        self._append_log("GATEWAY_STARTING", host=self.config.host, port=self.config.port, advertised=advertised)
        if getattr(self.config, "tunnel_enabled", True):
            threading.Thread(
                target=lambda: self.tunnel_manager.start_tunnel(self.config.port, getattr(self.config, "tunnel_token", None)),
                name="FridayTunnelStarter",
                daemon=True,
            ).start()
        try:
            cfg = uvicorn.Config(
                self.app,
                host=self.config.host,
                port=self.config.port,
                log_level="warning",
                log_config=None,
                access_log=False,
            )
            self._server = uvicorn.Server(cfg)
            self._server.install_signal_handlers = lambda: None
            await self._server.serve()
        finally:
            self._server = None
            self.discovery.stop()
            self.tunnel_manager.stop_tunnel()
            self._running = False
