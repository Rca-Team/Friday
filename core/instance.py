"""Per-session Windows mutex shared with the installer."""
import ctypes
import os

_handle = None

def acquire_instance() -> bool:
    global _handle
    if os.name != "nt":
        return True
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    _handle = kernel.CreateMutexW(None, False, "Local\\FridayAI-Desktop")
    if not _handle:
        raise ctypes.WinError(ctypes.get_last_error())
    return ctypes.get_last_error() != 183
