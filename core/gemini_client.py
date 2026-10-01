"""Small compatibility layer for the maintained ``google.genai`` SDK.

Older Friday modules used the retired Gemini SDK. Keeping this adapter local
lets those modules migrate without duplicating client and
system-instruction setup, while all provider calls use the current SDK.
"""

from __future__ import annotations

from google import genai
from google.genai import types


class GeminiModel:
    """Generate-content model backed by :class:`google.genai.Client`."""

    def __init__(
        self,
        model_name: str,
        *,
        api_key: str,
        system_instruction: str | None = None,
    ) -> None:
        self.model_name = model_name
        self._client = genai.Client(
            api_key=api_key,
            http_options=types.HttpOptions(api_version="v1beta"),
        )
        self._config = (
            types.GenerateContentConfig(system_instruction=system_instruction)
            if system_instruction
            else None
        )

    def generate_content(self, contents):
        kwargs = {
            "model": self.model_name,
            "contents": contents,
        }
        if self._config is not None:
            kwargs["config"] = self._config
        return self._client.models.generate_content(**kwargs)
