"""Exercise the real receive handler with a fake Gemini stream, no hardware/API."""
import ast
import asyncio
import threading
import types
from pathlib import Path
from unittest.mock import Mock

from core.voice_activation import VoiceActivation
from core.conversational_filter import ConversationalSessionTracker


def receive_method():
    tree = ast.parse((Path(__file__).resolve().parents[1] / 'main.py').read_text(encoding='utf-8'))
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'FridayLive')
    method = next(n for n in cls.body if isinstance(n, ast.AsyncFunctionDef) and n.name == '_receive_audio')
    namespace = {'asyncio': asyncio, 'threading': threading, 'types': types.SimpleNamespace(FunctionResponse=lambda **kw: kw), '_update_memory_async': lambda *args: None}
    exec(compile(ast.Module(body=[method], type_ignores=[]), 'main.py', 'exec'), namespace)
    return namespace['_receive_audio']


import unittest


class VoiceReceiveTests(unittest.TestCase):
    def test_receive_gates_tools_audio_and_activates_once(self):
        import sys
        sys.modules['actions.attention_monitor'] = types.SimpleNamespace(stop_native_speech=lambda: None)

        async def scenario():
            def response(text=None, audio=None, tool=False, done=False):
                sc = types.SimpleNamespace(input_transcription=types.SimpleNamespace(text=text) if text else None,
                                           output_transcription=None, turn_complete=done)
                call = types.SimpleNamespace(function_calls=[types.SimpleNamespace(id='t', name='open_app')]) if tool else None
                return types.SimpleNamespace(server_content=sc, data=audio, tool_call=call)
            frames = [response('dinner ready', b'blocked', True, True), response('Fri'),
                      response('day', b'allowed'), response('Friday', tool=True), response(done=True)]
            sent, executed = [], []
            class Session:
                async def receive(self):
                    for frame in frames:
                        yield frame
                    raise asyncio.CancelledError
                async def send_tool_response(self, **kw):
                    sent.append(kw)
            async def execute(fc):
                executed.append(fc.name)
                return {'ok': True}
            ui = types.SimpleNamespace(set_state=Mock(), write_log=Mock(), activate_voice=Mock())
            obj = types.SimpleNamespace(session=Session(), ui=ui, audio_in_queue=asyncio.Queue(),
                                        _voice_activation=VoiceActivation(), _conv_tracker=ConversationalSessionTracker(),
                                        _ptt_enabled=False, _ptt_held=False, _wake_enabled=True,
                                        _reset_idle_activity=Mock(), set_speaking=Mock(), _execute_tool=execute)
            try:
                await receive_method()(obj)
            except asyncio.CancelledError:
                pass
            self.assertEqual(executed, ['open_app'])
            self.assertTrue(sent[0]['function_responses'][0]['response']['error'])
            self.assertEqual(ui.activate_voice.call_count, 1)
            self.assertEqual(obj.audio_in_queue.get_nowait(), b'allowed')
            self.assertIsNone(obj.audio_in_queue.get_nowait())
            self.assertTrue(obj.audio_in_queue.empty())
        asyncio.run(scenario())

    def test_interim_transcription_flushes_early_audio(self):
        import sys
        sys.modules['actions.attention_monitor'] = types.SimpleNamespace(stop_native_speech=lambda: None)

        async def scenario():
            # Packet 1: Audio arrives BEFORE transcription (e.g. Gemini starts streaming audio instantly)
            frame1 = types.SimpleNamespace(server_content=None, data=b'early_greeting', tool_call=None)
            # Packet 2: Interim transcription arrives with "wake up friday"
            sc2 = types.SimpleNamespace(
                interim_input_transcription=types.SimpleNamespace(text="wake up friday"),
                input_transcription=None,
                output_transcription=None,
                turn_complete=False
            )
            frame2 = types.SimpleNamespace(server_content=sc2, data=b'rest_of_greeting', tool_call=None)
            # Packet 3: Turn finishes
            sc3 = types.SimpleNamespace(
                interim_input_transcription=None,
                input_transcription=types.SimpleNamespace(text="wake up friday"),
                output_transcription=None,
                turn_complete=True
            )
            frame3 = types.SimpleNamespace(server_content=sc3, data=None, tool_call=None)

            frames = [frame1, frame2, frame3]
            class Session:
                async def receive(self):
                    for frame in frames:
                        yield frame
                    raise asyncio.CancelledError
                async def send_tool_response(self, **kw):
                    pass
            ui = types.SimpleNamespace(set_state=Mock(), write_log=Mock(), activate_voice=Mock())
            obj = types.SimpleNamespace(session=Session(), ui=ui, audio_in_queue=asyncio.Queue(),
                                        _voice_activation=VoiceActivation(), _conv_tracker=ConversationalSessionTracker(),
                                        _ptt_enabled=False, _ptt_held=False, _wake_enabled=True,
                                        _reset_idle_activity=Mock(), set_speaking=Mock(), _execute_tool=lambda fc: None)
            try:
                await receive_method()(obj)
            except asyncio.CancelledError:
                pass

            self.assertEqual(ui.activate_voice.call_count, 1)
            # Both early_greeting AND rest_of_greeting must be in the queue in exact order!
            self.assertEqual(obj.audio_in_queue.get_nowait(), b'early_greeting')
            self.assertEqual(obj.audio_in_queue.get_nowait(), b'rest_of_greeting')
            self.assertIsNone(obj.audio_in_queue.get_nowait())
            self.assertTrue(obj.audio_in_queue.empty())
        asyncio.run(scenario())

    def test_user_requested_wake_phrases(self):
        for phrase in ["friday", "wake up friday", "utho friday", "iutho firday", "utho dost", "jagjao", "jag jao", "jarvis"]:
            state = VoiceActivation()
            allowed, newly_woke = state.accept(phrase)
            self.assertTrue(allowed, f"Failed allowed for {phrase}")
            self.assertTrue(newly_woke, f"Failed newly_woke for {phrase}")


if __name__ == '__main__':
    unittest.main()

