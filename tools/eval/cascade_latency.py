"""tools/eval/cascade_latency.py — production-fidelity latency sweep for the
cascade pipeline.

The cascade architecture (Paraformer step 1 + qwen-mt-plus step 2) opens
two WS connections per utterance vs the joint Qwen-Omni path's one, so
TTFB and end-of-stream latency will be ~100-300 ms higher until the
connection pool warms. This script measures it under server_vad (the
prod commit mode) on a small tier1 subset, so we can spot the regression
before shipping the toggle.

Run modes:

  A) Joint baseline  — current Qwen-Omni realtime, default model.
  B) Cascade step 1  — Paraformer-V2 verbatim ASR (no MT step).
  C) Cascade step 1+2 — Paraformer-V2 → qwen-mt-plus, end-to-end.

Per mode the script records:
  - ttfb           (sec, time to first text delta from any stage)
  - first_zhen     (sec, time to first fully-formed ZH/EN pair)
  - total_latency  (sec, response.done)
  - max_delta_gap  (sec, largest intra-turn pause — catches stalls)
  - delta_count    (count)

Recommended output: a JSON with per-sample latencies + a summary that
compares modes side-by-side (mean/median/p95 per metric).

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python cascade_latency.py --manifest manifest.tier1.jsonl \
        --modes joint,cascade1,cascade2 \
        --limit 10 --report report.latency.tier1.json
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional

from qwen_client import (
    EvalResult, QwenRealtimeClient, TRANSCRIBE_INSTRUCTIONS_V1, load_wav_as_pcm16_mono,
)

# Cascade step 1 uses the DashScope SDK (different driver).
try:
    import dashscope
    from dashscope.audio.asr import Recognition, RecognitionCallback
    import threading
    HAVE_DASHSCOPE = True
except Exception:
    HAVE_DASHSCOPE = False


def _set_api_key(explicit: Optional[str] = None) -> str:
    key = (explicit or os.environ.get("DASHSCOPE_API_KEY")
           or os.environ.get("QWEN_API_KEY") or os.environ.get("DASHSCOPE_TOKEN"))
    if not key:
        raise RuntimeError("Set DASHSCOPE_API_KEY")
    if HAVE_DASHSCOPE:
        dashscope.api_key = key
    return key


# ---------------------------------------------------------------------
# Mode A: joint Qwen-Omni
# ---------------------------------------------------------------------

async def run_joint(pcm: bytes, sid: str, args) -> dict:
    client = QwenRealtimeClient(
        endpoint=args.endpoint,
        model=args.model,
        api_key=_set_api_key(args.api_key),
        silence_ms=args.vad_silence,
        threshold=args.vad_threshold,
        task="transcribe",
        trailing_silence_ms=args.trailing_silence,
        commit_mode="vad",
        realtime=True,           # latency run: real-time pacing required
        instructions=TRANSCRIBE_INSTRUCTIONS_V1,
    )
    result = await client.transcribe(pcm, sid)
    lat = _latency(result.delta_times)
    return {
        "id": sid, "mode": "joint",
        "ttfb": lat["ttfb"], "total": lat["total"],
        "max_delta_gap": lat["max_delta_gap"], "delta_count": lat["delta_count"],
        "n_chars": len(result.transcript or ""),
    }


# ---------------------------------------------------------------------
# Mode B: cascade step 1 (Paraformer-V2) — latency only
# ---------------------------------------------------------------------

class _ParaLatCb(RecognitionCallback):
    def __init__(self):
        self._first_text_t: Optional[float] = None
        self._last_text_t: Optional[float] = None
        self._deltas: List[float] = []
        self._final_text = ""
        self._lock = threading.Lock()
        self._done = threading.Event()
        self._err: Optional[str] = None

    def on_event(self, result):
        out = getattr(result, "output", None)
        if out is None: return
        sent = out.get("sentence") if isinstance(out, dict) else getattr(out, "sentence", None)
        if not isinstance(sent, dict): return
        text = sent.get("text") or ""
        end_t = sent.get("end_time")
        now = time.monotonic()
        with self._lock:
            if text:
                if self._first_text_t is None: self._first_text_t = now
                self._last_text_t = now
                self._deltas.append(now)
                self._final_text = text
            if end_t is not None:
                # sentence finalised
                pass

    def on_complete(self): self._done.set()
    def on_error(self, result):
        with self._lock:
            self._err = (getattr(result, "message", None) or getattr(result, "code", None)
                         or "RecognitionError")
        self._done.set()
    def on_close(self): self._done.set()


def _run_asr_step1(pcm: bytes, args) -> dict:
    """fun-asr ASR (cascade step 1), shared by cascade2 and cascade2_prod
    so the two MT-step variants compare on identical ASR input + timing.
    Returns the absolute monotonic t0 plus the callback's deltas/gaps."""
    cb = _ParaLatCb()
    rec = Recognition(model=args.asr_model, callback=cb, format="pcm", sample_rate=16000)
    t0 = time.monotonic()
    rec.start()
    FRAME = 640
    offset = 0
    while offset < len(pcm):
        rec.send_audio_frame(pcm[offset:offset + FRAME]); offset += FRAME
        time.sleep(0.005)
    rec.stop()
    cb._done.wait(timeout=30)
    with cb._lock:
        deltas = cb._deltas[:]
        first = cb._first_text_t
        last = cb._last_text_t
        err = cb._err
        text = cb._final_text
    gaps = [b - a for a, b in zip(deltas, deltas[1:])] if len(deltas) > 1 else []
    return {"t0": t0, "asr_text": (text or ""), "first": first, "last": last,
            "deltas": deltas, "gaps": gaps, "err": err}


def run_cascade1(pcm: bytes, sid: str, args) -> dict:
    if not HAVE_DASHSCOPE:
        return {"id": sid, "mode": "cascade1", "error": "dashscope SDK missing"}
    cb = _ParaLatCb()
    rec = Recognition(model=args.asr_model, callback=cb, format="pcm", sample_rate=16000)
    t0 = time.monotonic()
    rec.start()
    FRAME = 640
    offset = 0
    while offset < len(pcm):
        rec.send_audio_frame(pcm[offset:offset + FRAME]); offset += FRAME
        time.sleep(0.005)
    rec.stop()
    cb._done.wait(timeout=30)
    with cb._lock:
        deltas = cb._deltas[:]
        first = cb._first_text_t
        last = cb._last_text_t
        err = cb._err
        text = cb._final_text
    gaps = [b - a for a, b in zip(deltas, deltas[1:])] if len(deltas) > 1 else []
    return {
        "id": sid, "mode": "cascade1",
        "ttfb": (first - t0) if first else None,
        "total": (last - t0) if last else None,
        "max_delta_gap": max(gaps) if gaps else 0.0,
        "delta_count": len(deltas),
        "n_chars": len(text or ""),
        "error": err,
    }


# ---------------------------------------------------------------------
# Mode C: cascade step 1 + step 2 — full end-to-end pipeline
# ---------------------------------------------------------------------

# Reuse the same verbatim ASR prompt used in cascade_step1.py.
VERBATIM_INSTRUCTIONS = (
    "You are a speech-to-text transcription engine. Transcribe the user's "
    "speech EXACTLY as spoken, word for word, in the original language(s). "
    "The speech freely mixes Mandarin Chinese and English within a single "
    "utterance — keep every word in the language it was actually spoken; do "
    "NOT translate anything. Write all Chinese characters in SIMPLIFIED "
    "Chinese (简体字). Output ONLY the raw transcription text: no labels, "
    "no language tags, no quotation marks, no markdown, no commentary. If "
    "nothing intelligible was said, output nothing."
)

MT_INSTRUCTIONS = (
    "You are a real-time interpreter. The user speaks either Chinese or "
    "English. For each utterance, output the translation in BOTH languages "
    "using EXACTLY this two-line format and nothing else:\n"
    "ZH: <Mandarin translation>\n"
    "EN: <English translation>\n"
    "Always output the ZH line first, then the EN line. Use no labels other "
    "than 'ZH:' and 'EN:'. No extra commentary, no markdown, no apologies."
)


def run_cascade2(pcm: bytes, sid: str, args) -> dict:
    """Step 1 (Paraformer ASR) + step 2 (qwen-mt-plus MT) end-to-end.
    Records latency at both stages and the combined pipeline total.

    Returns ttfb = time from upload-start to first text delta from EITHER
    stage (in practice: ASR first, then MT). Total = ASR commit + MT
    complete. For finer breakdown see step1_ttfb / step1_total /
    mt_ttfb / mt_total in the returned dict.
    """
    if not HAVE_DASHSCOPE:
        return {"id": sid, "mode": "cascade2", "error": "dashscope SDK missing"}

    # ---- Step 1: fun-asr ASR (shared helper _run_asr_step1) ----
    s1 = _run_asr_step1(pcm, args)
    pipeline_t0 = s1["t0"]
    first1, last1 = s1["first"], s1["last"]
    deltas1, gaps1 = s1["deltas"], s1["gaps"]
    asr_text, err1 = s1["asr_text"], s1["err"]
    step1_ttfb = (first1 - pipeline_t0) if first1 else None
    step1_total = (last1 - pipeline_t0) if last1 else None

    if err1 or not (asr_text or "").strip():
        return {
            "id": sid, "mode": "cascade2",
            "ttfb": step1_ttfb, "total": step1_total,
            "step1_ttfb": step1_ttfb, "step1_total": step1_total,
            "mt_ttfb": None, "mt_total": None,
            "max_delta_gap": max(gaps1) if gaps1 else 0.0,
            "delta_count": len(deltas1),
            "n_chars_step1": len(asr_text or ""),
            "n_chars_step2": 0,
            "error": err1 or "step1 empty",
        }

    # ---- Step 2: qwen-mt-plus streaming chat completion ----
    from dashscope import Generation
    step2_t0 = time.monotonic()
    mt_first_t: Optional[float] = None
    mt_last_t: Optional[float] = None
    mt_deltas: List[float] = []
    mt_text = ""
    mt_err: Optional[str] = None
    try:
        messages = [
            {"role": "system", "content": MT_INSTRUCTIONS},
            {"role": "user", "content": asr_text},
        ]
        # incremental_output=True makes Generation emit only the new chunk
        # text per iteration — exactly what we need for TTFB timing.
        responses = Generation.call(
            model=args.mt_model,
            messages=messages,
            stream=True,
            incremental_output=True,
            result_format="message",
        )
        for chunk in responses:
            now = time.monotonic()
            if mt_first_t is None:
                mt_first_t = now
            mt_last_t = now
            mt_deltas.append(now)
            try:
                # chunk.output.choices[0].message.content is the new text
                content = chunk.output.choices[0].message.content
                if content:
                    mt_text += content
            except (AttributeError, IndexError, KeyError):
                # Some chunks may be metadata-only — skip.
                pass
    except Exception as e:
        mt_err = f"{type(e).__name__}: {e}"

    mt_ttfb = (mt_first_t - step2_t0) if mt_first_t else None
    mt_total = (mt_last_t - step2_t0) if mt_last_t else None
    mt_gaps = [b - a for a, b in zip(mt_deltas, mt_deltas[1:])] if len(mt_deltas) > 1 else []

    # Combined metrics: end-to-end time + first text from either stage.
    # ASR runs first, so combined ttfb is whichever stage delivered first.
    pipeline_first_t = first1 if first1 else mt_first_t
    pipeline_last_t = mt_last_t if mt_last_t else last1

    # Combined max_delta_gap: the worst stall across both stages, with
    # the MT start offset added so the value is comparable to single-mode.
    combined_gaps = list(gaps1)
    if mt_deltas:
        # MT runs serially after ASR; offsets within step 2 are relative.
        if mt_deltas and mt_deltas[0] is not None and last1 is not None:
            mt_gaps_abs = [(d - mt_deltas[0]) + (last1 - pipeline_t0)
                           for d in mt_deltas]
        else:
            mt_gaps_abs = []
        # Add inter-stage gap (ASR last → MT first).
        if mt_first_t and last1:
            inter = mt_first_t - last1
            combined_gaps.append(inter)
        combined_gaps.extend(mt_gaps_abs)

    return {
        "id": sid, "mode": "cascade2",
        # Combined end-to-end (what the user actually feels)
        "ttfb": (pipeline_first_t - pipeline_t0) if pipeline_first_t else None,
        "total": (pipeline_last_t - pipeline_t0) if pipeline_last_t else None,
        "max_delta_gap": max(combined_gaps) if combined_gaps else 0.0,
        # Per-stage breakdown
        "step1_ttfb": step1_ttfb,
        "step1_total": step1_total,
        "mt_ttfb": mt_ttfb,
        "mt_total": mt_total,
        "delta_count": len(deltas1) + len(mt_deltas),
        "n_chars_step1": len(asr_text or ""),
        "n_chars_step2": len(mt_text or ""),
        "error": mt_err,
    }


# ---------------------------------------------------------------------
# Mode D: cascade step 1 + PRODUCTION step 2 (translate-mode fidelity)
# ---------------------------------------------------------------------
#
# cascade2 above runs a SIMPLIFIED MT step (hardcoded MT_INSTRUCTIONS,
# qwen-mt-plus via the dashscope native SDK, no tools). The production
# app runs a heavier path: MtPromptBuilder.buildSystemPrompt (verbatim
# CORE PRINCIPLE + auto-detect interpreter block + eavesdrop/command
# paragraph + live SESSION CONTEXT), qwen-turbo over the OpenAI-compat
# streaming endpoint, temperature 0.3. This mode reproduces that path
# so mt_ttfb/mt_total reflect what the shipping app actually pays.
#
# No tools schema is sent: latency memory measured tools-vs-no-tools
# TTFB at 631 vs 639 ms (noise, n=5), and CS-Dialogue samples carry no
# voice commands so the tool_calls branch never fires. The prompt TEXT
# (incl. the eavesdrop paragraph) IS reproduced in full because it sets
# the prefill token count that drives TTFB.

PROD_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# Python port of MtPromptBuilder.buildSystemPrompt for the production
# default session: sourceLang="auto" (ConfigStore.DEFAULT_SRC_LANG,
# applied at MainActivity.onCreate:153), targetLang="en", display BOTH,
# mic listening, cascade on, no style, temperature 0.30 -> the auto
# branch (MtPromptBuilder.java:96-121) + eavesdrop paragraph (:143-190)
# + a static SESSION CONTEXT stub (recent cmds/utterances empty, :91-126).
PROD_SYSTEM_PROMPT = (
    "CORE PRINCIPLE — VERBATIM SOURCE + FREE TRANSLATION:\n"
    "1. The line matching the SPOKEN language must be EXACTLY what the user said — "
    "FROM THE VERY FIRST WORD to the last. Preserve every word, every "
    "'呃'/'嗯'/'那个' filler, every repetition, every grammatical error, every "
    "slang, every non-standard expression. Do NOT correct, improve, paraphrase, "
    "or 'clean up' the source text. If the user said '呃那个我今天呃想要吃苹果', "
    "output exactly that — NOT '我今天想吃苹果'.\n"
    "2. CRITICAL FOR CODE-SWITCHING: when the user mixes English words inside a "
    "Chinese sentence (or vice versa), keep the foreign-language words EXACTLY as "
    "the user said them. Do NOT translate them. This applies at the START of the "
    "sentence too — if the user starts in English and switches to Chinese, the "
    "English opening must remain English. Example: user says 'Alright. 是这样的，"
    "好像怎么说，是因为社会的快速发展才使我们 had to make some innovation' → ZH: "
    "must contain the verbatim 'Alright. 是这样的...' including the English "
    "'Alright. had to make some innovation' fragments, NOT '好的。是这样的...'.\n"
    "3. The OTHER line is the translation. It can be styled per the user's "
    "preferences (formal, casual, concise, literary, etc.).\n"
    "4. Voice commands (e.g. '下面改成中日翻译', '翻译得更正式一些') are CONTROL "
    "COMMANDS that adjust translation behavior. They are NOT part of the source "
    "text. They do NOT affect the transcription line in any way.\n\n"
    "You are a real-time bilingual interpreter. The user may speak either "
    "Mandarin Chinese or English — auto-detect the spoken language.\n\n"
    "OUTPUT FORMAT — TWO LINES, ONE LABEL EACH:\n"
    "Line 1: the VERBATIM SOURCE TRANSCRIPT, prefixed with the matching label:\n"
    "  - If user spoke Chinese, prefix with 'ZH: '\n"
    "  - If user spoke English, prefix with 'EN: '\n"
    "Line 2: the TRANSLATION into the OTHER language, prefixed with the OTHER label:\n"
    "  - If source was Chinese, line 2 is 'EN: <English translation>'\n"
    "  - If source was English, line 2 is 'ZH: <Chinese translation>'\n\n"
    "RULE: line 1 is always the VERBATIM source. The label on line 1 must match "
    "the language of the source. Line 2 is the translation with the OTHER "
    "language's label.\n\n"
    "EXAMPLES:\n"
    "User says '我去学校' (Chinese) →\n"
    "ZH: 我去学校\n"
    "EN: I go to school\n\n"
    "User says 'i go to school' (English) →\n"
    "EN: i go to school\n"
    "ZH: 我去学校\n\n"
    "User says '呃那个我今天呃想吃苹果' (Chinese with fillers) →\n"
    "ZH: 呃那个我今天呃想吃苹果\n"
    "EN: Um, that, um, I want to eat an apple today\n\n"
    "Use no labels other than 'ZH:' and 'EN:'. No extra commentary, no markdown, "
    "no apologies.\n\n"
    "You are EAVESDROPPING on a real conversation between two people and "
    "translating it. Almost EVERY utterance is content to translate — even when "
    "it mentions languages, translation, podcasts, settings, styles, or the word "
    "'mode'. A SYSTEM CONTROL COMMAND is RARE: it is SHORT, STANDALONE, and speaks "
    "directly TO you with an imperative verb, e.g. '下面改成中日翻译', "
    "'只显示日文就好', '打开调试', '暂停', '复制到剪贴板', '总结一下', "
    "'重新翻译上一句', '温度调到0.7', '你能做什么'. The available commands cover: "
    "switching languages, hiding one language column, opening the debug panel, "
    "pausing/resuming the microphone (toggle_mic {paused:true/false} for "
    "'暂停'/'继续'/'mute'/'unmute'), copying the transcript, summarizing the "
    "session, re-translating the last turn, adjusting log verbosity, changing "
    "translation style/temperature (set_translation_mode — pass only the fields "
    "the user mentioned; do NOT confuse the literal word '模式' with this tool: "
    "'切换到普通模式' means toggle_cascade), and listing commands.\n"
    "NEVER treat the following as commands — translate them instead:\n"
    "- talking ABOUT a language, a show, or translation itself ('我会说一点日语', "
    "'我平常还特别喜欢一个播客叫无聊斋, have you heard of it?')\n"
    "- code-switching mid-sentence (mixing English into Chinese speech is CONTENT, "
    "not a language-switch request)\n"
    "- questions or quotes ('What are you actually writing about?')\n"
    "- mentioning modes, styles, or settings in conversation.\n"
    "Rules:\n"
    "1. PURE control command → call the matching tool and output NO translation "
    "lines (not even empty ones).\n"
    "2. Mixed (a clear standalone command clause plus content) → STILL call the "
    "tool and output NO translation lines — the command takes effect on the NEXT "
    "utterance, so translating the current one would mislead the user.\n"
    "3. Plain content → translate normally and DO NOT call any tool.\n"
    "4. When unsure whether an utterance is a command, do NOT call a tool — "
    "translate it normally. False-positive tool calls are far worse than missed "
    "commands.\n"
    "5. When you do call a tool, pass the exact command phrase as \"trigger_text\" "
    "— it must appear VERBATIM in the utterance, and it must contain the command "
    "keyword (e.g. '翻译'/'语言' for a language switch, '调试' for debug, '暂停' "
    "for mic pause). If you cannot quote such a phrase, it is not a command — "
    "translate instead.\n\n"
    "SESSION CONTEXT\n"
    "===============\n"
    "Current state:\n"
    "  - Source language: auto-detect (中英)\n"
    "  - Target language: English (en)\n"
    "  - Display mode: both languages\n"
    "  - Mic: listening\n"
    "  - Log level: normal\n"
    "  - Cascade: on\n"
    "  - Translation style: (none)\n"
    "  - Temperature: 0.30\n\n"
    "Use the above to disambiguate commands like \"改成中文\", \"再翻一次\", "
    "\"undo last\", or partial references. Commands fire IMMEDIATELY, so the "
    "current state above reflects what has actually been applied."
)


def _run_prod_mt(asr_text: str, api_key: str,
                 model: str = "qwen-turbo", temperature: float = 0.3) -> dict:
    """Production-fidelity MT step 2: OpenAI-compatible streaming chat
    completion (mirrors QwenMtClient — qwen-turbo, stream, temperature,
    no tools). Returns first-event / first-bilingual / last-event times
    (monotonic), accumulated text, delta timestamps, and any error."""
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": PROD_SYSTEM_PROMPT},
            {"role": "user", "content": asr_text},
        ],
        "stream": True,
        "temperature": temperature,
        # Ask DashScope to append a final chunk carrying `usage` (incl.
        # prompt_tokens_details.cached_tokens) so we can see whether the
        # qwen-turbo implicit context cache is hitting on the (static)
        # PROD_SYSTEM_PROMPT prefix. Costs one extra SSE line after the
        # last content token — does not affect first_t / first_bilingual_t.
        "stream_options": {"include_usage": True},
    }
    req = urllib.request.Request(
        PROD_CHAT_URL, data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {api_key}",
                 "Content-Type": "application/json",
                 "Accept": "text/event-stream"},
    )
    first_t = last_t = first_bilingual_t = None
    deltas: List[float] = []
    text = ""
    usage_obj: Optional[dict] = None
    err: Optional[str] = None
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            for raw in r:
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    break
                try:
                    o = json.loads(payload)
                except (ValueError, TypeError):
                    o = {}
                # Final chunk (stream_options.include_usage) carries usage
                # and an empty choices array — capture it but do NOT let it
                # extend mt_total / deltas (it is not model output).
                if o.get("usage"):
                    usage_obj = o["usage"]
                choices = o.get("choices") or []
                if not choices:
                    continue
                now = time.monotonic()
                if first_t is None:
                    first_t = now
                last_t = now
                deltas.append(now)
                delta = choices[0].get("delta") or {}
                content = delta.get("content") or ""
                if content:
                    text += content
                    if first_bilingual_t is None and (
                            "ZH:" in text or "EN:" in text):
                        first_bilingual_t = now
    except urllib.error.HTTPError as e:
        err = f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
    except Exception as e:
        err = f"{type(e).__name__}: {e}"
    prompt_tokens = cached_tokens = None
    if usage_obj:
        prompt_tokens = usage_obj.get("prompt_tokens")
        ptd = (usage_obj.get("prompt_tokens_details") or {})
        cached_tokens = ptd.get("cached_tokens")
    return {"first_t": first_t, "last_t": last_t,
            "first_bilingual_t": first_bilingual_t,
            "deltas": deltas, "text": text, "err": err,
            "prompt_tokens": prompt_tokens, "cached_tokens": cached_tokens}


def run_cascade2_prod(pcm: bytes, sid: str, args) -> dict:
    """Cascade step 1 (fun-asr) + PRODUCTION step 2 (qwen-turbo over
    OpenAI-compat streaming with the full MtPromptBuilder prompt). Same
    shape as run_cascade2, plus mt_first_bilingual — first delta whose
    accumulated text contains a ZH:/EN: label, the user-visible event
    that the simplified cascade2 mt_ttfb byte-count can't surface."""
    if not HAVE_DASHSCOPE:
        return {"id": sid, "mode": "cascade2_prod", "error": "dashscope SDK missing"}

    s1 = _run_asr_step1(pcm, args)
    pipeline_t0 = s1["t0"]
    first1, last1 = s1["first"], s1["last"]
    deltas1, gaps1 = s1["deltas"], s1["gaps"]
    asr_text, err1 = s1["asr_text"], s1["err"]
    step1_ttfb = (first1 - pipeline_t0) if first1 else None
    step1_total = (last1 - pipeline_t0) if last1 else None

    if err1 or not asr_text.strip():
        return {
            "id": sid, "mode": "cascade2_prod",
            "ttfb": step1_ttfb, "total": step1_total,
            "step1_ttfb": step1_ttfb, "step1_total": step1_total,
            "mt_ttfb": None, "mt_total": None, "mt_first_bilingual": None,
            "max_delta_gap": max(gaps1) if gaps1 else 0.0,
            "delta_count": len(deltas1),
            "n_chars_step1": len(asr_text), "n_chars_step2": 0,
            "error": err1 or "step1 empty",
        }

    # ---- Step 2: production MT (OpenAI-compat streaming) ----
    step2_t0 = time.monotonic()
    mt = _run_prod_mt(asr_text, _set_api_key(args.api_key))
    mt_first_t, mt_last_t = mt["first_t"], mt["last_t"]
    mt_first_bilingual_t = mt["first_bilingual_t"]
    mt_deltas, mt_text, mt_err = mt["deltas"], mt["text"], mt["err"]
    mt_prompt, mt_cached = mt["prompt_tokens"], mt["cached_tokens"]

    mt_ttfb = (mt_first_t - step2_t0) if mt_first_t else None
    mt_total = (mt_last_t - step2_t0) if mt_last_t else None
    mt_first_bilingual = ((mt_first_bilingual_t - step2_t0)
                          if mt_first_bilingual_t else None)
    mt_gaps = [b - a for a, b in zip(mt_deltas, mt_deltas[1:])] if len(mt_deltas) > 1 else []

    pipeline_first_t = first1 if first1 else mt_first_t
    pipeline_last_t = mt_last_t if mt_last_t else last1

    combined_gaps = list(gaps1)
    if mt_deltas:
        if last1 is not None:
            mt_gaps_abs = [(d - mt_deltas[0]) + (last1 - pipeline_t0) for d in mt_deltas]
        else:
            mt_gaps_abs = []
        if mt_first_t and last1:
            combined_gaps.append(mt_first_t - last1)
        combined_gaps.extend(mt_gaps_abs)

    return {
        "id": sid, "mode": "cascade2_prod",
        "ttfb": (pipeline_first_t - pipeline_t0) if pipeline_first_t else None,
        "total": (pipeline_last_t - pipeline_t0) if pipeline_last_t else None,
        "max_delta_gap": max(combined_gaps) if combined_gaps else 0.0,
        "step1_ttfb": step1_ttfb,
        "step1_total": step1_total,
        "mt_ttfb": mt_ttfb,
        "mt_total": mt_total,
        "mt_first_bilingual": mt_first_bilingual,
        "mt_cached_tokens": mt_cached,
        "mt_prompt_tokens": mt_prompt,
        "mt_cache_ratio": ((mt_cached / mt_prompt)
                           if (mt_prompt and mt_cached is not None) else None),
        "delta_count": len(deltas1) + len(mt_deltas),
        "n_chars_step1": len(asr_text),
        "n_chars_step2": len(mt_text or ""),
        "error": mt_err,
    }


# ---------------------------------------------------------------------
# Aggregator
# ---------------------------------------------------------------------

def _latency(times: List[float]) -> dict:
    if not times:
        return {"ttfb": None, "total": None, "max_delta_gap": 0.0, "delta_count": 0}
    return {
        "ttfb": times[0],
        "total": times[-1],
        "max_delta_gap": max((b - a) for a, b in zip(times, times[1:])) if len(times) > 1 else 0.0,
        "delta_count": len(times),
    }


def summarise(samples: List[dict]) -> dict:
    """Median / p95 per metric per mode. p95 helps catch tail latencies
    that the average masks. For cascade2 we also surface per-stage
    breakdowns (step1_*, mt_*) so MT-step overhead is visible."""
    by_mode: Dict[str, List[dict]] = {}
    for s in samples:
        by_mode.setdefault(s["mode"], []).append(s)
    out: dict = {}
    for mode, rows in by_mode.items():
        if not rows: continue
        for metric in ("ttfb", "total", "max_delta_gap",
                       "step1_ttfb", "step1_total", "mt_ttfb", "mt_total",
                       "mt_first_bilingual",
                       "mt_cached_tokens", "mt_prompt_tokens", "mt_cache_ratio"):
            vals = sorted([r[metric] for r in rows if r.get(metric) is not None])
            if not vals: continue
            out[f"{mode}.{metric}.median"] = statistics.median(vals)
            p95 = vals[max(0, int(0.95 * len(vals)) - 1)]
            out[f"{mode}.{metric}.p95"] = p95
            out[f"{mode}.{metric}.mean"] = statistics.mean(vals)
        out[f"{mode}.n"] = len(rows)
    return out


async def run(args) -> int:
    try:
        _set_api_key(args.api_key)
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr); return 2
    if "cascade1" in args.modes and not HAVE_DASHSCOPE:
        print("ERROR: cascade1 mode needs the `dashscope` Python SDK", file=sys.stderr)
        return 2

    manifest = [json.loads(ln) for ln in Path(args.manifest).read_text().splitlines()
                if ln.strip() and not ln.startswith("#")]
    if args.limit > 0:
        manifest = manifest[: args.limit]
    print(f"Loaded {len(manifest)} sample(s), modes={args.modes}")

    samples: List[dict] = []
    for i, item in enumerate(manifest, 1):
        sid = item["id"]
        audio = item["audio"]
        try:
            pcm = load_wav_as_pcm16_mono(audio)
        except Exception as e:
            print(f"[{i}/{len(manifest)}] {sid}: audio load failed: {e}")
            continue
        for mode in args.modes:
            print(f"[{i}/{len(manifest)}] {sid} mode={mode} ... ", end="", flush=True)
            t0 = time.monotonic()
            if mode == "joint":
                r = await run_joint(pcm, sid, args)
            elif mode == "cascade1":
                r = run_cascade1(pcm, sid, args)
            elif mode == "cascade2":
                r = run_cascade2(pcm, sid, args)
            elif mode == "cascade2_prod":
                r = run_cascade2_prod(pcm, sid, args)
            else:
                r = {"id": sid, "mode": mode, "error": "unknown mode"}
            r["wall_s"] = round(time.monotonic() - t0, 3)
            samples.append(r)
            print(f"ttfb={r.get('ttfb')}  total={r.get('total')}  chars={r.get('n_chars')}")

    summary = summarise(samples)
    print("\nSummary:")
    for k, v in sorted(summary.items()):
        if isinstance(v, float):
            print(f"  {k:<32} {v:.3f}")
        else:
            print(f"  {k:<32} {v}")
    if args.report:
        out = {"config": vars(args), "samples": samples, "summary": summary,
               "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
        Path(args.report).write_text(json.dumps(out, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
        print(f"\nReport written: {args.report}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Cascade latency sweep (server_vad mode)")
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", default=None)
    p.add_argument("--endpoint", default="wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
    p.add_argument("--model", default="qwen3.5-omni-flash-realtime-2026-03-15",
                   help="joint-mode model")
    p.add_argument("--asr-model", default="fun-asr-realtime",
                   help="cascade step 1 model (default fun-asr-realtime, "
                        "DashScope 2025 model that beat paraformer by -20% MER)")
    p.add_argument("--mt-model", default="qwen-mt-plus",
                   help="cascade step 2 MT model")
    p.add_argument("--vad-silence", type=int, default=300)
    p.add_argument("--vad-threshold", type=float, default=0.3)
    p.add_argument("--trailing-silence", type=int, default=800)
    p.add_argument("--modes", default="joint,cascade1",
                   help="comma-separated: joint, cascade1, cascade2, cascade2_prod")
    p.add_argument("--limit", type=int, default=10)
    p.add_argument("--report", default="report.latency.json")
    args = p.parse_args()
    args.modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())