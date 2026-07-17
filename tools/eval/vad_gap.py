"""Isolate the VAD commit delay that the cascade app currently hides.

The app drops ASR partials (CascadeEngine.onDelta is a no-op) and only
starts MT on sentence-final. So the user sees nothing from the moment
they START speaking until: ASR sentence-final commit + MT TTFB.

This script measures, per sample:
  audio_dur       — the wav's actual speech duration (s)
  t_first_partial — t from send-start to first ASR text delta
  t_final         — t from send-start to first sentence_end=true (the commit)
  vad_silence     — t_final - (t0 + audio_dur)  ≈ trailing-silence the server
                    waited before committing (the app's dead zone)
  partial_window  — t_final - t_first_partial  (how long partials streamed
                    before commit — text the app is throwing away)

Run:
    export DASHSCOPE_API_KEY=sk-...
    python vad_gap.py --manifest manifest.tier1.jsonl --limit 5
"""
from __future__ import annotations

import argparse
import json
import os
import threading
import time
from pathlib import Path
from typing import List, Optional

import dashscope
from dashscope.audio.asr import Recognition, RecognitionCallback
from qwen_client import load_wav_as_pcm16_mono

dashscope.api_key = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
assert dashscope.api_key

SAMPLE_RATE = 16000


class Cb(RecognitionCallback):
    def __init__(self):
        self.t0 = 0.0
        self.first_partial: Optional[float] = None
        self.final: Optional[float] = None
        self.last_partial: Optional[float] = None
        self._done = threading.Event()
        self._err = None
        self._lock = threading.Lock()

    def on_event(self, result):
        out = getattr(result, "output", None)
        if out is None:
            return
        sent = out.get("sentence") if isinstance(out, dict) else getattr(out, "sentence", None)
        if not isinstance(sent, dict):
            return
        text = sent.get("text") or ""
        end_t = sent.get("end_time")
        now = time.monotonic() - self.t0
        with self._lock:
            if text:
                if self.first_partial is None:
                    self.first_partial = now
                self.last_partial = now
            if end_t is not None and self.final is None:
                self.final = now

    def on_complete(self): self._done.set()
    def on_error(self, r):
        with self._lock:
            self._err = getattr(r, "message", None) or getattr(r, "code", None) or "err"
        self._done.set()
    def on_close(self): self._done.set()


def wav_duration_s(path: str) -> float:
    import wave
    with wave.open(path, "rb") as w:
        return w.getnframes() / float(w.getframerate() or SAMPLE_RATE)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--manifest", required=True)
    p.add_argument("--limit", type=int, default=5)
    p.add_argument("--model", default="fun-asr-realtime")
    args = p.parse_args()

    items = [json.loads(ln) for ln in Path(args.manifest).read_text().splitlines()
             if ln.strip() and not ln.startswith("#")][: args.limit]
    print(f"{'id':<28} {'audio':>6} {'1stPart':>8} {'final':>8} {'vadSil':>8} {'partWin':>8}")
    rows = []
    for it in items:
        sid = it["id"]
        audio = it["audio"]
        pcm = load_wav_as_pcm16_mono(audio)
        adur = wav_duration_s(audio)
        cb = Cb()
        rec = Recognition(model=args.model, callback=cb, format="pcm", sample_rate=SAMPLE_RATE)
        cb.t0 = time.monotonic()
        rec.start()
        off = 0
        while off < len(pcm):
            rec.send_audio_frame(pcm[off:off + 640]); off += 640
            time.sleep(0.005)
        rec.stop()
        cb._done.wait(timeout=30)
        fp = cb.first_partial or 0.0
        fin = cb.final or 0.0
        vad = max(0.0, fin - adur)              # silence after speech end before commit
        pwin = max(0.0, fin - fp)               # partial streaming window (app discards)
        rows.append((vad, pwin, fp))
        print(f"{sid:<28} {adur:6.2f} {fp:8.3f} {fin:8.3f} {vad:8.3f} {pwin:8.3f}"
              + (f"  err={cb._err}" if cb._err else ""))

    import statistics as st
    if rows:
        vad_m = st.median(r[0] for r in rows)
        pwin_m = st.median(r[1] for r in rows)
        fp_m = st.median(r[2] for r in rows)
        print("\n--- median across n={} ---".format(len(rows)))
        print(f"  first_partial_t (ASR TTFB): {fp_m:.3f}s")
        print(f"  VAD trailing silence:       {vad_m:.3f}s   <- app shows nothing here")
        print(f"  partial window (discarded): {pwin_m:.3f}s   <- text the app throws away")
        print(f"  => app first-translation ≈ VAD_silence + MT_TTFB(0.5s) ≈ {vad_m + 0.5:.2f}s")
        print(f"  => if partials were shown: first-text ≈ ASR_TTFB = {fp_m:.2f}s")


if __name__ == "__main__":
    main()
