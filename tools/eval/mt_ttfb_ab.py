"""Measure qwen-plus MT TTFB with vs without the 13-tool catalog, to test
whether the tools schema prefill is the dominant cost behind the on-device
mt_ttfb_ms=754 (2026-07-17 real-device log).

App path: POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
          stream=true, with tools=<13 tool schemas> + session-context system prompt.
This script replays that with/without tools and times the first SSE content delta.

    export DASHSCOPE_API_KEY=sk-...
    python mt_ttfb_ab.py
"""
from __future__ import annotations

import json
import os
import time
import urllib.request

API = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
KEY = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
assert KEY, "set DASHSCOPE_API_KEY"

SYS = ("You are a real-time interpreter. The user speaks either Chinese or "
       "English. For each utterance, output the translation in BOTH languages "
       "using EXACTLY this two-line format:\nZH: <Mandarin>\nEN: <English>\n"
       "Always ZH first then EN. No other labels, no commentary.")
USER = "我想预订明天去东京的机票"  # ~15 chars, like the on-device sample


def _tool(name, desc):
    return {"type": "function", "function": {
        "name": name, "description": desc,
        "parameters": {"type": "object", "properties": {}, "required": []}}}


# 13 tools, roughly the schema sizes the app sends (MtPromptBuilder.buildToolsJson).
TOOLS = [
    _tool("set_translation_languages", "Change the language pair the interpreter translates between. Takes effect on the NEXT utterance. BCP-47 short codes."),
    _tool("set_display_mode", "Filter what the transcript shows. both / source_only / target_only."),
    _tool("toggle_cascade", "Switch between cascade and joint pipeline. Takes effect on next session start."),
    _tool("toggle_debug", "Show or hide the debug log panel."),
    _tool("toggle_mic", "Pause or resume the microphone without stopping the engine."),
    _tool("set_translation_mode", "Adjust translation style and/or temperature. Both optional."),
    _tool("set_log_level", "Set debug log verbosity: verbose / normal / quiet."),
    _tool("get_current_settings", "Read-only: return a summary of the current configuration."),
    _tool("clear_transcript", "Wipe the transcript history."),
    _tool("export_transcript", "Copy the transcript to the system clipboard."),
    _tool("summarize_session", "Summarize the transcript into a chip."),
    _tool("re_translate_last", "Re-translate the last turn with current style/temperature."),
    _tool("list_commands", "List all available voice commands."),
]


def run(with_tools: bool, n: int = 5):
    body = {"model": "qwen-plus", "stream": True, "temperature": 0.3,
            "messages": [{"role": "system", "content": SYS},
                         {"role": "user", "content": USER}]}
    if with_tools:
        body["tools"] = TOOLS
        body["tool_choice"] = "auto"
    req = urllib.request.Request(
        API, data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json",
                 "Accept": "text/event-stream"})
    ttfbs, totals = [], []
    for _ in range(n):
        t0 = time.monotonic()
        first = None
        last = None
        try:
            resp = urllib.request.urlopen(req, timeout=30)
            for raw in resp:
                line = raw.decode("utf-8", "ignore").strip()
                if not line.startswith("data:"):
                    continue
                if line[5:].strip() == "[DONE]":
                    break
                now = time.monotonic()
                if first is None:
                    first = now
                last = now
        except Exception as e:
            print(f"  err: {e}")
            continue
        if first:
            ttfbs.append(first - t0)
            totals.append(last - t0)
    import statistics as st
    label = "WITH tools " if with_tools else "NO tools  "
    if ttfbs:
        print(f"{label} ttfb median={st.median(ttfbs)*1000:.0f}ms "
              f"mean={st.mean(ttfbs)*1000:.0f}ms  total median={st.median(totals)*1000:.0f}ms "
              f"(n={len(ttfbs)})")
    else:
        print(f"{label} no successful runs")


if __name__ == "__main__":
    print("qwen-plus MT TTFB — tools prefill A/B (same utterance, 5 runs each)")
    run(False)
    run(True)
