"""Render the real settings controls with isolated local test configuration."""
import ast
import json
import os
import platform
import sys
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from contextlib import ExitStack

ROOT = Path(__file__).resolve().parents[1]
os.environ["QT_QPA_PLATFORM"] = "windows"
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / ".venv" / "Lib" / "site-packages"))

from PyQt6 import QtCore, QtGui, QtWidgets
from ui_settings import CATEGORIES


def run():
    print("Creating Qt test application", flush=True)
    app = QtWidgets.QApplication([sys.executable])
    namespace = {"os": os, "json": json, "sys": sys, "platform": platform,
                 "time": time, "Path": Path}
    for module in (QtCore, QtGui, QtWidgets):
        namespace.update({name: getattr(module, name) for name in dir(module) if name.startswith("Q")})
    namespace["Qt"] = QtCore.Qt
    tree = ast.parse((ROOT / "ui.py").read_text(encoding="utf-8"))
    nodes = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name in ("C", "SystemConnectivityPage")]
    nodes += [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in ("_default_app_settings", "_default_discord_settings")]
    exec(compile(ast.Module(body=nodes, type_ignores=[]), "ui.py", "exec"), namespace)
    print("Loaded settings controls", flush=True)
    cls = namespace["SystemConnectivityPage"]
    # No account lookup, hardware probing, sound or recovery work in this test.
    cls._update_ig_status = lambda self: None
    cls._refresh_auto_heal_status = lambda self: None
    namespace["identity"] = Mock()
    for name, value in {
        "assistant_name": "Friday", "application_name": "Friday",
        "owner_name": "Developer", "owner_role": "Engineering", "behavior_mode": "technical",
    }.items():
        getattr(namespace["identity"], "get_" + name).return_value = value
    namespace["sound_mgr"] = Mock()
    original_cwd = Path.cwd()
    with tempfile.TemporaryDirectory(dir=ROOT) as folder, ExitStack() as cleanup:
        cleanup.callback(os.chdir, original_cwd)
        with patch("core.user_paths.get_user_data_dir", return_value=Path(folder)):
            from memory import config_manager
            from core import audio_devices
        audio_devices.list_devices = lambda kind: []
        audio_devices.prefetch = lambda: None
        os.chdir(folder)
        config_manager.CONFIG_DIR = Path(folder)
        config_manager.SETTINGS_FILE = Path(folder) / "app_settings.json"
        namespace.update({
            "APP_SETTINGS_FILE": config_manager.SETTINGS_FILE,
            "API_FILE": Path(folder) / "api_keys.json",
            "DISCORD_SETTINGS_FILE": Path(folder) / "discord_bot.json",
            "CONFIG_DIR": Path(folder), "BASE_DIR": ROOT,
        })
        print("Rendering isolated settings", flush=True)
        page = cls()
        page.resize(1020, 760)
        page.show()
        app.processEvents()
        shell = page._shell
        assert all(any(category == name for category, _, _ in shell._entries) for name in CATEGORIES)
        shell.search.setText("microphone")
        app.processEvents()
        assert shell._current == "Voice"
        assert any(not card.isHidden() for _, card, _ in shell._entries)
        shell.search.setText("no-result-12345")
        assert shell.empty.isVisible()
        shell.search.clear()
        shell.select_category("AI & Vision")
        page._set_default_provider("Local")
        page.refresh()
        assert page._default_provider.currentText() == "Local"
        assert not page._local_ai_widget.isHidden()
        page._set_reduced_motion(True)
        assert json.loads(config_manager.SETTINGS_FILE.read_text())["reduce_motion_enabled"] is True
        shell.set_reduced_motion(True)
        shell.select_category("AI & Vision")
        app.processEvents()
        output = ROOT / "outputs" / "settings-rebuilt.png"
        output.parent.mkdir(exist_ok=True)
        assert page.grab().save(str(output))
        os.chdir(original_cwd)
        for name in CATEGORIES:
            shell.select_category(name)
            app.processEvents()
            scroll = shell._pages[name][0]
            assert scroll.horizontalScrollBar().maximum() == 0, (name, scroll.horizontalScrollBar().maximum())
        page.resize(820, 640)
        for name in CATEGORIES:
            shell.select_category(name)
            app.processEvents()
            assert shell._pages[name][0].horizontalScrollBar().maximum() == 0, name
        page.close()
        os.chdir(original_cwd)
    print("SETTINGS_UI_OK: all 7 categories, search, empty state, Local persistence, reduced motion, rendering")
    print(output)


if __name__ == "__main__":
    try:
        run()
    except BaseException:
        import traceback
        (ROOT / "outputs" / "settings-smoke-error.txt").write_text(traceback.format_exc(), encoding="utf-8")
        traceback.print_exc(file=sys.__stderr__)
        raise
