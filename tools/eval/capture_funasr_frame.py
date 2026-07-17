"""Capture the exact DashScope run-task start frame the Python SDK sends for
fun-asr-realtime vs paraformer-realtime-v2, so we can diff it against
ParaformerAsrClient.sendStartTask (Java) and find why fun-asr rejects the
Java payload with "format is empty" (WS 1007).

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python capture_funasr_frame.py
"""
from __future__ import annotations

import logging
import os
import time

# Enable SDK debug logging so the on-wire payloads are printed.
logging.basicConfig(level=logging.DEBUG, format="%(name)s | %(message)s")
for name in ("dashscope", "dashscope.api_entities.websocket_request",
             "dashscope.audio.asr.recognition"):
    logging.getLogger(name).setLevel(logging.DEBUG)

import dashscope
from dashscope.audio.asr import Recognition, RecognitionCallback

dashscope.api_key = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
assert dashscope.api_key, "set DASHSCOPE_API_KEY"

# 20 ms of 16 kHz mono PCM16 silence = 640 bytes of zeros.
SILENCE_FRAME = b"\x00\x00" * 320


class CB(RecognitionCallback):
    def __init__(self, label):
        self.label = label
        self.events = 0
    def on_open(self):
        print(f"\n>>> [{self.label}] on_open")
    def on_event(self, result):
        self.events += 1
        out = getattr(result, "output", None)
        sent = None
        if out is not None:
            sent = getattr(out, "sentence", None) if not isinstance(out, dict) else out.get("sentence")
        print(f">>> [{self.label}] on_event #{self.events}: {sent}")
    def on_complete(self):
        print(f">>> [{self.label}] on_complete")
    def on_error(self, result):
        msg = getattr(result, "message", None) or getattr(result, "code", None)
        print(f">>> [{self.label}] on_error: {msg}")
    def on_close(self):
        print(f">>> [{self.label}] on_close")


def try_model(model: str):
    print(f"\n========== MODEL: {model} ==========")
    cb = CB(model)
    try:
        rec = Recognition(model=model, callback=cb, format="pcm", sample_rate=16000)
        rec.start()
        for _ in range(5):  # ~100 ms of silence
            rec.send_audio_frame(SILENCE_FRAME)
            time.sleep(0.02)
        rec.stop()
        time.sleep(1.5)
    except Exception as e:
        print(f">>> [{model}] exception: {type(e).__name__}: {e}")


for m in ("paraformer-realtime-v2", "fun-asr-realtime"):
    try_model(m)
    time.sleep(0.5)
