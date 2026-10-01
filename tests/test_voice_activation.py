import unittest
from core.voice_activation import VoiceActivation, is_supported_voice_text, wakeword_detected, microphone_allowed


class VoiceActivationTests(unittest.TestCase):
    def test_supported_wake_phrases(self):
        for phrase in ['Friday', 'WAKE UP, FRIDAY!', 'utho friday', 'iutho firday',
                       'hey Jarvis', 'utho dost',
                       'Friday open Chrome']:
            with self.subTest(phrase=phrase):
                self.assertTrue(wakeword_detected(phrase))

    def test_word_boundaries(self):
        for phrase in ['fridayish', 'author', 'wake upper', 'open Chrome', 'dinner ready']:
            self.assertFalse(wakeword_detected(phrase), phrase)

    def test_hindi_script_and_other_languages_are_rejected(self):
        for phrase in ['उठो फ्राइडे', 'फ्राईडे सुनो', 'Hola Friday', '你好 Friday']:
            self.assertFalse(is_supported_voice_text(phrase), phrase)
            self.assertFalse(wakeword_detected(phrase), phrase)

        state = VoiceActivation()
        self.assertEqual(state.accept('उठो फ्राइडे'), (False, False))

    def test_fragments_and_single_activation(self):
        state = VoiceActivation()
        self.assertEqual(state.accept('Fri'), (False, False))
        self.assertEqual(state.accept('day'), (True, True))
        self.assertEqual(state.accept('Friday open Chrome'), (True, False))

    def test_followup_window_and_timeout(self):
        now = [100.0]
        state = VoiceActivation(clock=lambda: now[0])
        self.assertFalse(state.accept('open Chrome')[0])
        state.accept('Friday')
        state.end_turn()
        now[0] += 13
        self.assertTrue(state.accept('what time is it')[0])
        state.end_turn()
        now[0] += 21
        self.assertFalse(state.accept('open Chrome')[0])

    def test_split_name_with_inline_command(self):
        state = VoiceActivation()
        self.assertEqual(state.accept('Fri'), (False, False))
        self.assertEqual(state.accept('day open Chrome'), (True, True))

    def test_stale_fragments_do_not_combine(self):
        now = [100.0]
        state = VoiceActivation(clock=lambda: now[0])
        state.accept('Fri')
        now[0] += 4
        self.assertFalse(state.accept('day')[0])

    def test_ptt_cannot_be_bypassed(self):
        state = VoiceActivation()
        self.assertFalse(state.accept('Friday', push_to_talk=True, held=False)[0])
        self.assertTrue(state.accept('open Chrome', push_to_talk=True, held=True)[0])

    def test_explicit_text_and_reconnect(self):
        state = VoiceActivation()
        state.activate()
        self.assertTrue(state.allowed)
        state.close()
        self.assertFalse(state.allowed)
        self.assertFalse(state.accept('room conversation')[0])

    def test_audio_routing(self):
        self.assertTrue(microphone_allowed(muted=False, wake_listening=False, ptt_enabled=False, ptt_held=False))
        self.assertTrue(microphone_allowed(muted=True, wake_listening=True, ptt_enabled=False, ptt_held=False))
        self.assertFalse(microphone_allowed(muted=True, wake_listening=False, ptt_enabled=False, ptt_held=False))
        self.assertFalse(microphone_allowed(muted=False, wake_listening=True, ptt_enabled=True, ptt_held=False))
        self.assertTrue(microphone_allowed(muted=False, wake_listening=True, ptt_enabled=True, ptt_held=True))


if __name__ == '__main__':
    unittest.main()
