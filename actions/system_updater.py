import os
import sys
import threading
from pathlib import Path

def push_system_updates(player=None):
    """
    Pushes an update to both the PC (via git pull & restart) 
    and connected mobile devices (via APK push).
    """
    results = []
    base_dir = Path(__file__).resolve().parent.parent

    # 1. Update Mobile
    try:
        from friday_connect.service import get_service
    except ImportError:
        try:
            from brahma_connect.service import get_service
        except ImportError:
            get_service = None

    if get_service:
        try:
            from core.user_paths import get_user_data_dir
            service = get_service(get_user_data_dir())
            if service and service.is_running():
                devices = service.list_devices()
                apk_path = base_dir / "dist" / "Friday-Connect.apk"
                tunnel_url = service.get_tunnel_url()
                if not tunnel_url:
                    tunnel_url = f"http://{service.gateway.config.host}:{service.gateway.config.port}"

                if devices and apk_path.exists():
                    for d in devices:
                        if d.get("status") == "online":
                            # Send download intent to the device
                            service.route_command(
                                d["device_id"], 
                                "download_from_pc", 
                                {
                                    "path": str(apk_path.absolute()), 
                                    "gateway_url": tunnel_url, 
                                    "filename": "Friday-Connect-Update.apk"
                                }
                            )
                            results.append(f"Update pushed to mobile: {d.get('name', d['device_id'])}")
                elif not devices:
                    results.append("No mobile devices connected to push updates to.")
                elif not apk_path.exists():
                    results.append("Local APK not found; could not push to mobile.")
        except Exception as e:
            results.append(f"Mobile update error: {e}")

    # 2. Update PC
    try:
        from updater import update_from_github, restart_application
        # Temporarily enable update by removing skip flag
        os.environ["FRIDAY_SKIP_UPDATE"] = "0"
        os.environ["BRAHMA_SKIP_UPDATE"] = "0"
        did_update = update_from_github(base_dir)
        if did_update:
            results.append("PC Update Status: Updated from GitHub. Restarting...")
            def _restart():
                import time
                time.sleep(2)
                restart_application(base_dir)
            threading.Thread(target=_restart, daemon=True).start()
        else:
            results.append("PC Update Status: Already up to date or failed to fetch.")
    except Exception as e:
        results.append(f"PC Update Error: {e}")

    return " | ".join(results)
