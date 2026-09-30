from __future__ import annotations

import logging
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.request
from pathlib import Path
from typing import Optional

logger = logging.getLogger("FridayTunnel")

CLOUDFLARED_DOWNLOAD_URL = (
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
)


class FridayTunnelManager:
    """Manages secure zero-config public tunneling for Friday Connect.
    
    Enables mobile phones on 4G/5G or outside Wi-Fi to securely connect to Friday PC
    from anywhere in the world without router port forwarding.
    """

    def __init__(self, base_dir: Path, bin_dir: Optional[Path] = None):
        self.base_dir = Path(base_dir)
        self.bin_dir = bin_dir or (self.base_dir / "bin")
        self.bin_dir.mkdir(parents=True, exist_ok=True)
        self.cloudflared_path = self.bin_dir / "cloudflared.exe"
        self._process: Optional[subprocess.Popen] = None
        self._tunnel_url: Optional[str] = None
        self._monitor_thread: Optional[threading.Thread] = None
        self._stop_requested = threading.Event()
        self._lock = threading.RLock()

    def get_public_url(self) -> Optional[str]:
        with self._lock:
            return self._tunnel_url if self.is_active() else None

    def is_active(self) -> bool:
        with self._lock:
            return self._process is not None and self._process.poll() is None and self._tunnel_url is not None

    def ensure_cloudflared(self) -> bool:
        """Ensures cloudflared executable exists either in PATH or in bin/."""
        # 1. Check system PATH
        found_in_path = shutil.which("cloudflared")
        if found_in_path:
            self.cloudflared_path = Path(found_in_path)
            return True

        # 2. Check local bin/
        if self.cloudflared_path.exists() and self.cloudflared_path.stat().st_size > 1_000_000:
            return True

        # 3. Download official binary
        try:
            logger.info(f"[Tunnel] Downloading cloudflared for infinite connectivity: {CLOUDFLARED_DOWNLOAD_URL}")
            req = urllib.request.Request(
                CLOUDFLARED_DOWNLOAD_URL,
                headers={"User-Agent": "Friday-AI-Tunnel-Agent/1.0"}
            )
            temp_path = self.cloudflared_path.with_suffix(".tmp")
            with urllib.request.urlopen(req, timeout=30) as resp, open(temp_path, "wb") as f:
                shutil.copyfileobj(resp, f)
            if temp_path.exists() and temp_path.stat().st_size > 1_000_000:
                temp_path.replace(self.cloudflared_path)
                logger.info(f"[Tunnel] cloudflared successfully installed to {self.cloudflared_path}")
                return True
            return False
        except Exception as exc:
            logger.warning(f"[Tunnel] Could not auto-download cloudflared: {exc}")
            return False

    def start_tunnel(self, local_port: int, custom_tunnel_token: Optional[str] = None) -> Optional[str]:
        """Starts a secure tunnel to local_port and returns the public HTTPS/WSS URL."""
        with self._lock:
            if self.is_active():
                return self._tunnel_url

        self.stop_tunnel()
        if not self.ensure_cloudflared():
            logger.warning("[Tunnel] cloudflared executable not found. Remote connectivity outside LAN is disabled.")
            return None

        self._stop_requested.clear()
        cmd = [str(self.cloudflared_path), "tunnel"]
        if custom_tunnel_token:
            cmd.extend(["run", "--token", custom_tunnel_token])
        else:
            cmd.extend(["--url", f"http://127.0.0.1:{local_port}", "--no-autoupdate"])

        try:
            logger.info(f"[Tunnel] Spawning cloudflared tunnel for port {local_port}...")
            startupinfo = None
            if os.name == "nt":
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                startupinfo.wShowWindow = subprocess.SW_HIDE

            self._process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                startupinfo=startupinfo,
                bufsize=1,
            )

            # Read stream until tunnel URL appears
            tunnel_url_found = None
            url_pattern = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")
            process = self._process

            def _reader():
                nonlocal tunnel_url_found
                try:
                    for line in iter(process.stdout.readline, ""):
                        if self._stop_requested.is_set():
                            break
                        match = url_pattern.search(line)
                        if match and not tunnel_url_found:
                            tunnel_url_found = match.group(0).strip()
                            with self._lock:
                                if self._process is not process:
                                    return
                                self._tunnel_url = tunnel_url_found
                            logger.info(f"[Tunnel] 🚀 Infinite Connectivity Active: {self._tunnel_url}")
                except Exception as e:
                    logger.debug(f"[Tunnel] Reader exception: {e}")

            self._monitor_thread = threading.Thread(target=_reader, name="FridayTunnelReader", daemon=True)
            self._monitor_thread.start()

            # Wait up to 25 seconds for URL detection
            start_wait = time.time()
            while time.time() - start_wait < 25:
                with self._lock:
                    if self._tunnel_url:
                        return self._tunnel_url
                if self._process.poll() is not None:
                    break
                time.sleep(0.3)

            with self._lock:
                return self._tunnel_url
        except Exception as exc:
            logger.error(f"[Tunnel] Failed to start tunnel: {exc}")
            self.stop_tunnel()
            return None

    def stop_tunnel(self) -> None:
        """Gracefully shuts down the active tunnel process."""
        self._stop_requested.set()
        with self._lock:
            proc = self._process
            self._process = None
            self._tunnel_url = None

        if proc:
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    proc.kill()
            except Exception:
                pass
        logger.info("[Tunnel] Tunnel stopped.")
