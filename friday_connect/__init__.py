"""Friday Connect subsystem.

This package adds the local gateway, device registry, pairing flow, and
protocol definitions used by Friday AI to reach companion devices.
"""

from .service import FridayConnectService, get_service

__all__ = ["FridayConnectService", "get_service"]
