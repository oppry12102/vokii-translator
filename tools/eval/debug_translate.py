"""debug-only — print the raw model output under --task translate.

Used for the E' audit: we need to know if the model is failing to emit
the EN: line, or if the parser is eating it. Not committed permanently —
delete after diagnosing.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import qwen_client as qc


async def main() -> int:
    api_key = os.environ.get("QWEN_API_KEY") or os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        print("ERROR: QWEN_API_KEY env required", file=sys.stderr)
        return 2

    audio_path = sys.argv[1] if len(sys.argv) > 1 else None
    if not audio_path or not Path(audio_path).is_file():
        print("usage: _debug_translate.py /path/to/file.wav", file=sys.stderr)
        return 2

    pcm = qc.load_wav_as_pcm16_mono(audio_path)
    client = qc.QwenRealtimeClient(
        endpoint="wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
        model="qwen3.5-omni-plus-realtime-2026-03-15",
        api_key=api_key,
        task="translate",
        commit_mode="manual",
        realtime=False,
    )
    res = await client.transcribe(pcm, Path(audio_path).stem)
    print("=== full_text (raw, what the WS session returned) ===")
    print(repr(res.full_text))
    print()
    print("=== parsed by Bilingual.parse ===")
    zh, en = qc.parse_bilingual(res.full_text)
    print(f"  zh={zh!r}")
    print(f"  en={en!r}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
