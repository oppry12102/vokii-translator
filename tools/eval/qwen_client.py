"""Thin async client for the DashScope Qwen-Omni Realtime WebSocket API.

Mirrors the Android engine (``QwenOmniRealtimeClient``) so the eval
exercises the same protocol the production app uses. We push raw 16 kHz
mono PCM16 in small frames, the server runs VAD and streams back text
deltas, and we collect every delta's wall-clock timestamp so the metrics
module can compute TTFB / per-delta gaps / total latency.

A small ``Bilingual.parse`` (zh/en split) is also in here so the eval
runner can score zh and en independently from a single ``response``.
"""
from __future__ import annotations

import asyncio
import base64
import json
import time
from dataclasses import dataclass, field
from typing import List, Optional

# soundfile is heavy and only needed by load_wav_as_pcm16_mono; defer
# the import so the parser and dataclasses can be used in slim envs.


# ---------------------------------------------------------------------
# Protocol constants
# ---------------------------------------------------------------------

SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2          # 16-bit PCM
CHANNELS = 1
FRAME_MS = 20             # 20 ms per uplink frame → 640 bytes
FRAME_BYTES = SAMPLE_RATE * SAMPLE_WIDTH * FRAME_MS // 1000  # = 640

VAD_SILENCE_MS = 300      # short silence = faster turn commits
VAD_THRESHOLD = 0.3       # more sensitive to quiet speech


# ---------------------------------------------------------------------
# Bilingual parser
# ---------------------------------------------------------------------

def parse_bilingual(text: str) -> tuple[str, str]:
    """Split a model response into (zh, en). Handles the 'ZH: ..\\nEN: ..'
    line format the model is asked to use (in either label order), plus
    a JSON fallback.

    Routes by Han character count so a swapped label can't end up with
    English in the left (zh) column. Returns ('', '') for empty input.
    """
    s = (text or "").strip()
    if not s:
        return "", ""

    # JSON fallback: {"zh":"..","en":".."}
    if s.startswith("{"):
        try:
            obj = json.loads(_extract_json(s))
            return _route(obj.get("zh", "").strip(), obj.get("en", "").strip())
        except Exception:
            pass

    zi = s.find("ZH:")
    ei = s.find("EN:")
    if zi >= 0 and ei >= 0:
        # Both labels present. Each is followed by its content up to
        # the other label (or end of string).
        if zi < ei:
            zh = s[zi + 3:ei]
            en = s[ei + 3:]
        else:
            en = s[ei + 3:zi]
            zh = s[zi + 3:]
    elif zi >= 0:
        zh = s[zi + 3:]
        en = ""
    elif ei >= 0:
        en = s[ei + 3:]
        zh = ""
    else:
        zh = s
        en = ""
    return _route(_clean(zh), _clean(en))


def _clean(s: str) -> str:
    return s.strip(" \t:：\n").strip()


def _route(a: str, b: str) -> tuple[str, str]:
    if _han(b) > _han(a):
        return b, a
    return a, b


def _han(s: str) -> int:
    return sum(1 for c in s if 0x4E00 <= ord(c) <= 0x9FFF)


def _extract_json(s: str) -> str:
    a, b = s.find("{"), s.rfind("}")
    return s[a:b + 1] if a >= 0 and b > a else s


# ---------------------------------------------------------------------
# Result container
# ---------------------------------------------------------------------

@dataclass
class EvalResult:
    """Everything the metrics module needs to score one sample."""
    sample_id: str
    text_zh: str = ""
    text_en: str = ""
    full_text: str = ""
    transcript: str = ""     # verbatim output, used in task='transcribe'
    delta_times: List[float] = field(default_factory=list)  # sec from upload start
    error: Optional[str] = None
    audio_seconds: float = 0.0
    pcm_bytes_sent: int = 0


# Session instructions per task. 'transcribe' is verbatim ASR (what the
# CS-Dialogue eval scores); 'translate' mirrors the production interpreter.
#
# Versioning: prompts are kept as named constants so a sweep can attribute
# MER deltas to specific rule changes. `TRANSCRIBE_INSTRUCTIONS` is the
# single alias for "what currently runs by default"; flip it to point at
# a different version after that version wins its A/B.
#
#   v1 — original production prompt (best on tier1: avg MER 0.134)
#   v2 — 2026-07 rewrite with 5 stricter rules (regressed: 0.159, +0.025)
#   v3 — v1 + the 3 net-positive v2 rules (drop v2's rule 4 silence and
#         rule 5 "output nothing if unclear", which caused head-drop and
#         empty outputs on tier1)
#
# Override at runtime with `instructions=` on QwenRealtimeClient.

TRANSCRIBE_INSTRUCTIONS_V1 = (
    "You are a speech-to-text transcription engine. Transcribe the user's "
    "speech EXACTLY as spoken, word for word, in the original language(s). "
    "The speech freely mixes Mandarin Chinese and English within a single "
    "utterance — keep every word in the language it was actually spoken; do "
    "NOT translate anything. Write all Chinese characters in SIMPLIFIED "
    "Chinese (简体字). Output ONLY the raw transcription text: no labels, "
    "no language tags, no quotation marks, no markdown, no commentary. If "
    "nothing intelligible was said, output nothing."
)

# v2 (2026-07) — kept for opt-in reproduction. **Do not make this the
# default**: a tier1 A/B (n=40) showed it regresses +0.025 MER on the mean
# and triggers 1 fully-empty output, because rule 4 ("wait ≥0.5s of
# silence before writing") combined with rule 5 ("output nothing if not
# intelligible") makes the model drop sentence heads and hallucinate
# substitutions to fill the gap.
TRANSCRIBE_INSTRUCTIONS_V2 = (
    "You are a speech-to-text transcription engine. Transcribe the user's "
    "speech EXACTLY as spoken, word for word, in the original language(s). "
    "The speech freely mixes Mandarin Chinese and English within a single "
    "utterance — keep every word in the language it was actually spoken; do "
    "NOT translate anything.\n"
    "Strict rules (every output must obey all five):\n"
    "1. SET: Write all Chinese in SIMPLIFIED Chinese (简体). Never use "
    "繁体, 正體, 日式漢字, full-width Latin letters, or full-width "
    "punctuation. Numbers must be Arabic digits (0-9), never 中文数字 "
    "(五, 三十, etc.).\n"
    "2. NO LANGUAGE FLIP: If the user speaks English, output English "
    "(do NOT translate to Chinese). If the user speaks Chinese, output "
    "Chinese (do NOT translate to English). Mixed-language spans must "
    "stay mixed.\n"
    "3. NO FILLERS: Output ONLY what was actually said. Do NOT add "
    "filler words such as 'um', 'uh', '嗯', '哦', '呃', '那个' unless "
    "the user literally said them. Spoken pauses and silence are not "
    "transcribable content — leave them out.\n"
    "4. NO PARTIALS: Wait until the utterance has ended (≥0.5s of "
    "silence) before writing any text. Do not stream partial hypotheses "
    "into the output, and do not duplicate tokens across revisions.\n"
    "5. NO COMMENTARY: Output ONLY the raw transcription. No labels "
    "(no 'ZH:'/'EN:'/'Transcript:'), no language tags, no quotation marks, "
    "no markdown, no preamble, no apology, no trailing period. If nothing "
    "intelligible was said, output nothing."
)

# v3 (2026-07) — v1 + the three rules that didn't backfire. Goal: lift
# the v1 baseline by +0.005 to +0.015 without re-introducing the head-drop
# behaviour. The two v2 rules dropped here (#4 silence gate, #5 "output
# nothing") are the ones that caused tier1 regressions; if you want them
# back, run with --instructions v2 instead.
TRANSCRIBE_INSTRUCTIONS_V3 = (
    "You are a speech-to-text transcription engine. Transcribe the user's "
    "speech EXACTLY as spoken, word for word, in the original language(s). "
    "The speech freely mixes Mandarin Chinese and English within a single "
    "utterance — keep every word in the language it was actually spoken; do "
    "NOT translate anything. Write all Chinese characters in SIMPLIFIED "
    "Chinese (简体字).\n"
    "Rules:\n"
    "1. SET: Use simplified Chinese (简体) only. Numbers must be Arabic "
    "digits (0-9); never 中文数字 (五, 三十, etc.). No full-width Latin "
    "letters or full-width punctuation.\n"
    "2. NO LANGUAGE FLIP: If the user speaks English, output English "
    "(do NOT translate to Chinese). If the user speaks Chinese, output "
    "Chinese (do NOT translate to English). Mixed-language spans stay mixed.\n"
    "3. NO FILLERS: Do not add 'um', 'uh', '嗯', '哦', '呃', '那个' "
    "unless the user literally said them.\n"
    "Output ONLY the raw transcription text: no labels, no language "
    "tags, no quotation marks, no markdown, no commentary."
)

# Active default. Set to V1 after the tier1 A/B (REPORT.tier1.ab_round1.md)
# showed v2 regressed; V3 is the dev candidate that should be promoted here
# once it shows a non-negative ΔMER on tier2.
TRANSCRIBE_INSTRUCTIONS = TRANSCRIBE_INSTRUCTIONS_V1
TRANSLATE_INSTRUCTIONS = (
    "You are a real-time interpreter. The user speaks either Chinese or "
    "English. For each utterance, output the translation in BOTH languages "
    "using EXACTLY this two-line format and nothing else:\n"
    "ZH: <Mandarin translation>\n"
    "EN: <English translation>\n"
    "Always output the ZH line first, then the EN line. Use no labels other "
    "than 'ZH:' and 'EN:'. No extra commentary, no markdown, no apologies."
)


# ---------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------

class QwenRealtimeClient:
    """Connect to DashScope Omni Realtime, push PCM, collect text deltas."""

    def __init__(self, endpoint: str, model: str, api_key: str,
                 silence_ms: int = VAD_SILENCE_MS,
                 threshold: float = VAD_THRESHOLD,
                 task: str = "transcribe",
                 trailing_silence_ms: int = 800,
                 commit_mode: str = "manual",
                 realtime: bool = True,
                 instructions: Optional[str] = None,
                 repetition_penalty: Optional[float] = None) -> None:
        self.endpoint = endpoint
        self.model = model
        self.api_key = api_key
        self.silence_ms = silence_ms
        self.threshold = threshold
        self.trailing_silence_ms = trailing_silence_ms
        if commit_mode not in ("manual", "vad"):
            raise ValueError(f"commit_mode must be 'manual' or 'vad', got {commit_mode!r}")
        self.commit_mode = commit_mode
        self.realtime = realtime
        if task not in ("transcribe", "translate"):
            raise ValueError(f"task must be 'transcribe' or 'translate', got {task!r}")
        self.task = task
        if instructions is None:
            instructions = (TRANSCRIBE_INSTRUCTIONS if task == "transcribe"
                            else TRANSLATE_INSTRUCTIONS)
        self.instructions = instructions
        # Optional sampling knobs — None means "don't send", so a
        # session.update stays minimal by default.
        if repetition_penalty is not None and repetition_penalty <= 0:
            raise ValueError("repetition_penalty must be > 0 when set")
        self.repetition_penalty = repetition_penalty

    async def transcribe(self, pcm16_mono: bytes, sample_id: str) -> EvalResult:
        """Open a fresh WS, push the whole buffer, wait for the final
        turn. Returns the assembled zh/en text + per-delta timestamps.

        Raises on transport / auth failures. The caller decides whether
        that's a fatal eval error or a per-sample skip.
        """
        try:
            import websockets  # type: ignore
        except ImportError as e:
            raise RuntimeError("pip install websockets") from e

        url = f"{self.endpoint}?model={self.model}"
        headers = [("Authorization", f"Bearer {self.api_key}")]
        result = EvalResult(sample_id=sample_id,
                            audio_seconds=len(pcm16_mono) / (SAMPLE_RATE * SAMPLE_WIDTH))
        start = time.monotonic()
        full_buf: list[str] = []
        turn_buf: list[str] = []

        async with websockets.connect(url, additional_headers=headers,
                                      ping_interval=20, ping_timeout=20) as ws:
            # 1. Configure session. In 'manual' commit mode we disable
            #    server VAD entirely: these clips are already one segmented
            #    utterance, so VAD only fragments them (dropping segments).
            #    We push the whole clip, then commit + response.create ONCE
            #    so the model transcribes it in a single turn.
            turn_detection = None if self.commit_mode == "manual" else {
                "type": "server_vad",
                "silence_duration_ms": self.silence_ms,
                "threshold": self.threshold,
            }
            await ws.send(json.dumps({
                "type": "session.update",
                "session": {
                    "modalities": ["text"],
                    "input_audio_format": "pcm16",
                    "turn_detection": turn_detection,
                    "instructions": self.instructions,
                    **({"repetition_penalty": self.repetition_penalty}
                       if self.repetition_penalty is not None else {}),
                },
            }))

            # 2. Producer: push PCM frames every FRAME_MS. We deliberately
            #    don't await the network between frames — the consumer
            #    loop reads concurrently. This mirrors the Android
            #    capture thread's "fire and forget" pattern.
            async def pusher() -> None:
                offset = 0
                while offset < len(pcm16_mono):
                    chunk = pcm16_mono[offset:offset + FRAME_BYTES]
                    offset += FRAME_BYTES
                    await ws.send(json.dumps({
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(chunk).decode("ascii"),
                    }))
                    result.pcm_bytes_sent += len(chunk)
                    # Pace the upload at ~real-time (skip in manual mode when
                    # realtime pacing is off — accuracy doesn't need it and
                    # it makes big eval runs much faster).
                    if self.realtime:
                        await asyncio.sleep(FRAME_MS / 1000)

                if self.commit_mode == "manual":
                    # Explicitly close the buffer and ask for one response.
                    await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
                    await ws.send(json.dumps({"type": "response.create"}))
                    return

                # server_vad mode: pre-segmented dataset clips are trimmed to
                # the speech and carry no tail silence, so VAD never sees the
                # pause it needs to commit the final turn. Feed zero frames
                # (a mic always would after the speaker stops) to trigger it.
                silence = b"\x00" * FRAME_BYTES
                n_frames = max(0, self.trailing_silence_ms) // FRAME_MS
                for _ in range(n_frames):
                    await ws.send(json.dumps({
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(silence).decode("ascii"),
                    }))
                    if self.realtime:
                        await asyncio.sleep(FRAME_MS / 1000)

            # 3. Consumer. server_vad segments a long utterance into
            #    MULTIPLE turns (one response per detected speech segment,
            #    split on internal pauses), so a single clip yields several
            #    responses — some empty. We must NOT stop at the first
            #    response.done; instead accumulate every turn's final text
            #    and stop once the audio is fully sent AND the stream has
            #    gone quiet for a short drain window.
            push_done = asyncio.Event()
            DRAIN_S = 2.0
            HARD_CAP_S = 180.0

            async def consumer() -> None:
                deadline = start + HARD_CAP_S
                while True:
                    try:
                        raw = await asyncio.wait_for(ws.recv(), timeout=DRAIN_S)
                    except asyncio.TimeoutError:
                        # Quiet gap: done only if the whole clip is uploaded.
                        if push_done.is_set() or time.monotonic() > deadline:
                            return
                        continue
                    ev = json.loads(raw)
                    etype = ev.get("type", "")

                    if etype in ("response.text.delta",
                                 "response.output_text.delta",
                                 "response.audio_transcript.delta"):
                        turn_buf.append(ev.get("delta", ""))
                        result.delta_times.append(time.monotonic() - start)
                    elif etype in ("response.text.done",
                                   "response.output_text.done"):
                        full = ev.get("text", "")
                        if full:
                            turn_buf.clear()
                            turn_buf.append(full)
                    elif etype == "response.done":
                        turn = "".join(turn_buf).strip()
                        turn_buf.clear()
                        if turn:
                            full_buf.append(turn)   # one entry per real turn
                        result.delta_times.append(time.monotonic() - start)
                    elif etype == "error":
                        result.error = ev.get("error", {}).get("message", raw)
                        return
                    if time.monotonic() > deadline:
                        return

            async def pusher_wrapped() -> None:
                try:
                    await pusher()
                finally:
                    push_done.set()

            push_task = asyncio.create_task(pusher_wrapped())
            try:
                await asyncio.wait_for(consumer(), timeout=HARD_CAP_S + 10)
            finally:
                push_task.cancel()

        # Reassemble the utterance from all non-empty turns, in order.
        result.full_text = " ".join(full_buf).strip()
        if self.task == "transcribe":
            # Verbatim output — no zh/en split; scorer uses .transcript.
            result.transcript = result.full_text
        else:
            result.text_zh, result.text_en = parse_bilingual(result.full_text)
        return result


# ---------------------------------------------------------------------
# WAV loader
# ---------------------------------------------------------------------

def load_wav_as_pcm16_mono(path: str) -> bytes:
    """Load a WAV (any sample rate / channels) and resample to
    16 kHz mono PCM16. Returns raw little-endian int16 bytes."""
    import soundfile as sf  # local import — see module docstring
    data, sr = sf.read(path, always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != SAMPLE_RATE:
        # Lazy import — scipy is heavy and only needed when rate
        # doesn't match.
        from scipy.signal import resample  # type: ignore
        n_target = int(len(data) * SAMPLE_RATE / sr)
        data = resample(data, n_target)
    # int16 conversion
    import numpy as np
    pcm = (np.clip(data, -1.0, 1.0) * 32767).astype("<i2")
    return pcm.tobytes()
