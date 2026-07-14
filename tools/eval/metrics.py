"""Evaluation metrics for the Vokii speech-to-text pipeline.

All functions are pure and dependency-light — only the optional
``sacrebleu`` import is a no-op if missing. Used both by the eval runner
and by ad-hoc unit tests.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from typing import List, Sequence

# Traditional→Simplified Chinese (cheap, optional). The Pro model
# occasionally emits 繁體 characters; without this, the CER is inflated
# by characters that mean the same thing.
try:
    import opencc  # type: ignore
    _T2S = opencc.OpenCC("t2s").convert
except ImportError:                              # pragma: no cover
    _T2S = lambda s: s                           # noqa: E731


# ---------------------------------------------------------------------
# Edit distance
# ---------------------------------------------------------------------

def edit_distance(ref: Sequence[str], hyp: Sequence[str]) -> int:
    """Standard Levenshtein distance over a sequence of tokens."""
    if len(ref) == 0:
        return len(hyp)
    if len(hyp) == 0:
        return len(ref)
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        curr = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cost = 0 if r == h else 1
            curr[j] = min(
                curr[j - 1] + 1,        # insertion
                prev[j] + 1,            # deletion
                prev[j - 1] + cost,     # substitution
            )
        prev = curr
    return prev[-1]


def error_rate(ref: str, hyp: str, level: str = "char") -> float:
    """CER (level='char') or WER (level='word'). Returns 0..inf, where
    0 is perfect and anything >1 means hyp is wildly longer than ref."""
    if level == "char":
        r, h = list(ref), list(hyp)
    elif level == "word":
        r, h = ref.split(), hyp.split()
    else:
        raise ValueError(f"level must be 'char' or 'word', got {level!r}")
    if len(r) == 0:
        return float(len(h))  # any hallucination counts
    return edit_distance(r, h) / len(r)


# ---------------------------------------------------------------------
# Mixed (code-switch) error rate — the CS-Dialogue / ASR metric
# ---------------------------------------------------------------------

# CJK Unified Ideographs + Ext-A + compatibility. Each such char is one token.
_CJK_RE = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
# A "word" chunk: runs of latin letters / digits / intra-word ' and -.
_WORD_CHARS_RE = re.compile(r"[0-9A-Za-z]")
_WS_RE = re.compile(r"\s+")


def normalize_asr(text: str, lower: bool = True) -> str:
    """Normalize a transcript for scoring: NFKC, drop punctuation and
    symbols (both ASCII and full-width), collapse whitespace, and (by
    default) lowercase Latin. Chinese characters and alphanumerics are
    kept; everything in Unicode category P* or S* is dropped."""
    s = unicodedata.normalize("NFKC", text or "")
    s = _T2S(s)                              # 繁 → 简 (no-op if no opencc)
    out = []
    for ch in s:
        cat = unicodedata.category(ch)
        if cat[0] in ("P", "S"):        # punctuation / symbol
            out.append(" ")
        else:
            out.append(ch)
    s = "".join(out)
    if lower:
        s = s.lower()
    return _WS_RE.sub(" ", s).strip()


def tokenize_mixed(text: str) -> List[str]:
    """Split code-switched text into scoring tokens: each Chinese char is
    its own token, each run of alphanumerics is one word token. Whitespace
    and anything else separates tokens. Feed already-normalized text."""
    tokens: List[str] = []
    buf: List[str] = []

    def flush() -> None:
        if buf:
            tokens.append("".join(buf))
            buf.clear()

    for ch in text:
        if _CJK_RE.match(ch):
            flush()
            tokens.append(ch)
        elif _WORD_CHARS_RE.match(ch):
            buf.append(ch)
        else:                            # space / leftover separator
            flush()
    flush()
    return tokens


@dataclass
class MixedErrorStats:
    """MER = (S+D+I) / N over mixed tokens, plus a language breakdown so a
    high number can be traced to the Chinese or the English side."""
    mer: float = 0.0
    n_ref_tokens: int = 0
    n_hyp_tokens: int = 0
    cer_zh: float = 0.0          # char error rate over the Chinese subset
    wer_en: float = 0.0          # word error rate over the English subset
    n_ref_zh: int = 0
    n_ref_en: int = 0


def _is_en_token(tok: str) -> bool:
    return bool(_WORD_CHARS_RE.match(tok[:1]))


def mixed_error_rate(ref: str, hyp: str, normalize: bool = True) -> MixedErrorStats:
    """Mixed Error Rate for code-switched ASR output. Tokenizes ref and hyp
    (Chinese by char, English by word), computes overall token edit-rate,
    and a per-language breakdown by filtering each side to its own tokens."""
    r_text = normalize_asr(ref) if normalize else ref
    h_text = normalize_asr(hyp) if normalize else hyp
    r = tokenize_mixed(r_text)
    h = tokenize_mixed(h_text)

    r_zh = [t for t in r if not _is_en_token(t)]
    h_zh = [t for t in h if not _is_en_token(t)]
    r_en = [t for t in r if _is_en_token(t)]
    h_en = [t for t in h if _is_en_token(t)]

    stats = MixedErrorStats(
        n_ref_tokens=len(r), n_hyp_tokens=len(h),
        n_ref_zh=len(r_zh), n_ref_en=len(r_en),
    )
    stats.mer = edit_distance(r, h) / len(r) if r else float(len(h))
    stats.cer_zh = edit_distance(r_zh, h_zh) / len(r_zh) if r_zh else (float(len(h_zh)) if h_zh else 0.0)
    stats.wer_en = edit_distance(r_en, h_en) / len(r_en) if r_en else (float(len(h_en)) if h_en else 0.0)
    return stats


# ---------------------------------------------------------------------
# Latency
# ---------------------------------------------------------------------

@dataclass
class LatencyStats:
    """Per-sample latency and stream rhythm.

    All durations are seconds, measured from the start of the audio
    upload. ``ttfb`` is the first delta; ``total`` is end-of-stream.
    ``turn_gaps`` are the inter-delta intervals within a single turn
    (catches stalls, useful for diagnosing stream smoothness).
    """
    ttfb: float = 0.0
    total: float = 0.0
    turn_gaps: List[float] = field(default_factory=list)
    delta_count: int = 0


def compute_latency(delta_timestamps: List[float]) -> LatencyStats:
    if not delta_timestamps:
        return LatencyStats()
    gaps = [b - a for a, b in zip(delta_timestamps, delta_timestamps[1:])]
    return LatencyStats(
        ttfb=delta_timestamps[0],
        total=delta_timestamps[-1],
        turn_gaps=gaps,
        delta_count=len(delta_timestamps),
    )


# ---------------------------------------------------------------------
# Stream health
# ---------------------------------------------------------------------

def missing_char_rate(ref: str, hyp: str) -> float:
    """Estimate chars in ref missing from hyp. Cheap char-set overlap
    proxy: returns 1 - (common / |ref|). Intentionally rough — exact
    alignment is what CER is for."""
    if not ref:
        return 0.0
    ref_counts: dict[str, int] = {}
    for c in ref:
        ref_counts[c] = ref_counts.get(c, 0) + 1
    matched = 0
    for c in hyp:
        if ref_counts.get(c, 0) > 0:
            matched += 1
            ref_counts[c] -= 1
    return 1.0 - matched / len(ref)


def repetition_rate(hyp: str, min_run: int = 4) -> float:
    """Fraction of hyp chars that are part of a stuck-on repeat run of
    at least ``min_run`` identical chars. Catches the model stuttering
    on the same token (e.g. '的的的的的')."""
    if not hyp:
        return 0.0
    bad = 0
    i = 0
    while i < len(hyp):
        j = i + 1
        while j < len(hyp) and hyp[j] == hyp[i]:
            j += 1
        run_len = j - i
        if run_len >= min_run:
            bad += run_len
        i = j
    return bad / len(hyp)


# ---------------------------------------------------------------------
# BLEU (optional via sacrebleu)
# ---------------------------------------------------------------------

def bleu_score(refs: List[str], hyps: List[str]) -> float:
    """Corpus BLEU-4. Falls back to 0.0 if sacrebleu isn't installed
    (so this module stays importable in slim envs)."""
    if not refs or not hyps or len(refs) != len(hyps):
        return 0.0
    try:
        import sacrebleu  # type: ignore
    except ImportError:
        return 0.0
    bleu = sacrebleu.corpus_bleu(hyps, [refs])
    return float(bleu.score) / 100.0  # normalize to 0..1


# ---------------------------------------------------------------------
# Aggregate
# ---------------------------------------------------------------------

def aggregate(sample_metrics: List[dict]) -> dict:
    """Average the per-sample metrics dicts into a single summary.
    Skips keys that aren't numeric or are present in fewer than half
    the samples."""
    if not sample_metrics:
        return {}
    keys = set()
    for m in sample_metrics:
        keys.update(m.keys())
    summary: dict = {}
    for k in keys:
        vals = [m[k] for m in sample_metrics
                if isinstance(m.get(k), (int, float)) and not isinstance(m.get(k), bool)]
        if len(vals) < len(sample_metrics) / 2:
            continue
        summary[f"avg_{k}"] = sum(vals) / len(vals)
        summary[f"min_{k}"] = min(vals)
        summary[f"max_{k}"] = max(vals)
    return summary
