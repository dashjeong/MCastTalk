import unittest
from unittest.mock import Mock
import time
import threading
import numpy as np
from mcasttalk_worker.meeting_engine import MeetingEngine,encode_wav,language,speech_context

class MeetingEngineTest(unittest.TestCase):
    def engine(self):
        e=MeetingEngine.__new__(MeetingEngine);e.selected_backend='cpu';e.fallback_reason='';e.llama=None;e.credentials=None
        e.translate=Mock(side_effect=lambda text,source,target:target+':'+text)
        e.speak=Mock(return_value=b'fixture-wav')
        return e
    def request(self,**changes):
        return dict(op='text',text='Synthetic meeting',sourceLanguage='en',publishLanguage='ja',targets='ko,en',audioTargets='',**changes)
    def test_supported_languages(self):
        for value in ['ko','en','ja','zh-CN']:self.assertEqual(language(value),value)
    def test_unsupported_language(self):
        with self.assertRaises(ValueError):language('file:///secret')
    def test_wav_header(self):self.assertEqual(encode_wav(np.zeros(100),16000)[:4],b'RIFF')
    def test_nan_audio(self):
        with self.assertRaises(ValueError):encode_wav([float('nan')],16000)
    def test_oversized_audio(self):
        with self.assertRaises(ValueError):encode_wav(np.zeros(16000*31),16000)
    def test_invalid_rate(self):
        with self.assertRaises(ValueError):encode_wav([0],1000)
    def test_unique_translation_targets(self):
        e=self.engine();r=e.execute(self.request());self.assertEqual(e.translate.call_count,3);self.assertEqual(r['publishedText'],'ja:Synthetic meeting')
    def test_text_does_not_generate_audio(self):
        e=self.engine();e.execute(self.request());e.speak.assert_not_called()
    def test_unsupported_target(self):
        e=self.engine();r=self.request();r['targets']='fr'
        with self.assertRaises(ValueError):e.execute(r)
        e.translate.assert_not_called()
    def test_audio_target_not_in_delivery_set(self):
        e=self.engine();r=self.request();r['audioTargets']='zh-CN'
        with self.assertRaises(ValueError):e.execute(r)
    def test_empty_text(self):
        r=self.request();r['text']=' '
        with self.assertRaises(ValueError):self.engine().execute(r)
    def test_oversized_text(self):
        r=self.request();r['text']='a'*2001
        with self.assertRaises(ValueError):self.engine().execute(r)
    def test_voice_size_before_asr(self):
        r=self.request();r.update(op='voice',pcm='AA==')
        with self.assertRaises(ValueError):self.engine().execute(r)
    def test_vulkan_startup_falls_back_once_to_cpu(self):
        e=self.engine();e.selected_backend='vulkan';e._start_translation=Mock(side_effect=[RuntimeError('GPU unavailable'),None]);e.stop_translation=Mock()
        e.start_translation();self.assertEqual(e.selected_backend,'cpu');self.assertEqual(e.fallback_reason,'vulkan-startup-failed')
        self.assertEqual([call.args[0] for call in e._start_translation.call_args_list],['vulkan','cpu'])
    def test_cpu_failure_is_not_hidden(self):
        e=self.engine();e._start_translation=Mock(side_effect=RuntimeError('unavailable'));e.stop_translation=Mock()
        with self.assertRaises(RuntimeError):e.start_translation()
        self.assertEqual(e._start_translation.call_count,1)
    def test_vulkan_request_failure_retries_once_on_cpu(self):
        e=self.engine();e.selected_backend='vulkan';e.stop_translation=Mock()
        e._translate_once=Mock(side_effect=[ValueError('Translation was truncated'),'안녕하세요'])
        self.assertEqual(MeetingEngine.translate(e,'Hello','en','ko'),'안녕하세요')
        self.assertEqual(e.selected_backend,'cpu');self.assertEqual(e.fallback_reason,'vulkan-translation-request-failed')
        self.assertEqual(e._translate_once.call_count,2);e.stop_translation.assert_called_once()
    def test_cpu_retry_failure_remains_a_failure(self):
        e=self.engine();e.selected_backend='vulkan';e.stop_translation=Mock()
        e._translate_once=Mock(side_effect=ValueError('Translation was truncated'))
        with self.assertRaises(ValueError):MeetingEngine.translate(e,'Hello','en','ko')
        self.assertEqual(e._translate_once.call_count,2)

    def test_context_covers_every_supported_duration_with_margin(self):
        for samples in range(4000,96001,137):
            self.assertGreaterEqual(speech_context(samples)*320,samples+32*320)
            self.assertIn(speech_context(samples),[128,192,256,320,384,448,512])
    def test_context_invalid_samples(self):
        for samples in [0,3999,96001]:
            with self.assertRaises(ValueError):speech_context(samples)
    def progressive(self,e,targets='en,ko,ja',audio='ko,ja',emit=lambda event:None):
        return e._progressive_voice(dict(targets=targets),dict(originalText='synthetic',sourceLanguage='en',asrMs=42),
            set(targets.split(',')),set(filter(None,audio.split(','))),'en',time.perf_counter(),emit)
    def test_progressive_caption_before_audio_for_each_target(self):
        events=[];result=self.progressive(self.engine(),emit=events.append)
        for target in ['ko','ja']:
            kinds=[event['kind'] for event in events if event['targetLanguage']==target]
            self.assertEqual(kinds,['caption','audio'])
        self.assertEqual(result['status'] if 'status' in result else 'complete','complete')
        self.assertIn('audio_ko',result)
    def test_original_language_does_not_translate_or_synthesize(self):
        e=self.engine();events=[];self.progressive(e,'en','',events.append)
        e.translate.assert_not_called();e.speak.assert_not_called();self.assertEqual(len(events),1)
    def test_first_audio_does_not_wait_for_last_language(self):
        e=self.engine();heard=threading.Event();original=e.translate.side_effect
        def translate(text,source,target):
            if target=='ja':self.assertTrue(heard.wait(2),'Audio blocked on all-language barrier')
            return original(text,source,target)
        e.translate.side_effect=translate
        self.progressive(e,emit=lambda event:heard.set() if event['kind']=='audio' else None)
    def test_progressive_unique_targets(self):
        e=self.engine();self.progressive(e,'en,ko,ko','ko')
        self.assertEqual(e.translate.call_count,1);self.assertEqual(e.speak.call_count,1)
    def test_progressive_tts_failure_not_reported_as_complete(self):
        e=self.engine();e.speak.side_effect=RuntimeError('synthetic failure')
        with self.assertRaises(RuntimeError):self.progressive(e)
    def test_warmup_uses_four_synthetic_language_measurements(self):
        e=self.engine();e.start_translation=Mock();e.speak.return_value=encode_wav(np.zeros(24000),24000)
        def execute(request,on_event=None):
            target=request['targets']
            return dict(status='complete',asrMs=1500,**{'translationMs_'+target:400,'ttsMs_'+target:200})
        e.execute=Mock(side_effect=execute)
        result=e.warmup();self.assertEqual(result['calibrationSamples'],4)
        self.assertEqual(set(call.args[0]['targets'] for call in e.execute.call_args_list),{'ko','en','ja','zh-CN'})
        self.assertEqual(result['calibrationAsrMs'],1500)

if __name__=='__main__':unittest.main()
