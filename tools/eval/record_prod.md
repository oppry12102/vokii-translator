# Recording paired prod audio

What this is: a checklist for capturing what a real Vokii session
*actually hears* so you can run a credible `audio_diff` against the
CS-Dialogue eval. Synthesising mic noise is a fallback, not a
substitute — only a real recording tells you whether the eval pipeline
understates production MER.

## When to do this

Run this whenever you suspect the eval-vs-prod gap is large but can't
prove it. Specifically: whenever you change the Android audio capture
path (AEC / NS / AGC, sample rate, codec), or whenever a tier2
regression disappears on retest in a quiet room — a clue that prod
ambient noise is the missing variable.

## What you need

- A **playback** device — your usual Android phone will do. Disable
  any audio enhancement (Dolby, Huawei Histen, etc — they shape the
  spectrum in ways prod never sees).
- A **recording** device — a *different* phone (preferably with a
  different mic / form factor than the playback device, so the round
  trip covers worst-case hardware paths). Ideally your target Harmony
  / Huawei phone, since the prod eval is targeting that class of mic.
- A quiet room — close the door, kill fans / AC for the duration.
  Phone on silent.
- About 8–10 minutes of your time for 30 clips × ~12 s per clip.

## Step-by-step

1. **Pick the subset**. The first 20–30 rows of `manifest.tier1.jsonl`
   are stratified and diverse enough. Anything tier2 is overkill for a
   first pass.

2. **Print a playlist**:

   ```bash
   python build_paired_manifest.py \
       --source-manifest manifest.tier1.jsonl \
       --prod-dir ./recordings \
       --print-playlist --limit 30
   ```

   Write down the IDs in order. Or just leave the terminal up — you'll
   read the filenames off it as you go.

3. **Set up the recorder**. Use the highest available sample rate
   (44.1 kHz or 48 kHz), mono if offered. Most recorders save as M4A
   or WAV — either works (`build_paired_manifest.py` tries both).

4. **Place the phones**:
   - Playback phone: speaker-up on the table, screen facing up.
   - Recording phone: mic-side down, ~30 cm above the playback phone,
     ~10 cm to the side. The exact geometry doesn't matter much; just
     keep it consistent across all clips so you don't introduce
     geometry noise.

5. **Press record, then play each clip in order**, with ~3 seconds of
   silence between clips. The silence is so the recorder sees a clean
   gap — same logic `--trailing-silence` uses in the eval harness.

6. **Save each clip** as `<id>.wav` in `./recordings/`, where `<id>` is
   exactly the eval manifest id (e.g., `ZH-CN_U0023_S0_91.wav`). The
   matching is by stem, not by order — so filenames matter more than
   order does.

7. **Build the paired manifest**:

   ```bash
   python build_paired_manifest.py \
       --source-manifest manifest.tier1.jsonl \
       --prod-dir ./recordings \
       --out manifest.tier1.paired.jsonl \
       --limit 30
   ```

   Re-run with `--strict` once you've verified the filenames. The
   script will tell you which IDs are missing if any.

8. **Run the diff**:

   ```bash
   python audio_diff.py \
       --manifest manifest.tier1.paired.jsonl \
       --instructions v1 \
       --model qwen3.5-omni-plus-realtime-2026-03-15 \
       --report report.diff.paired.json
   ```

9. **Read the summary table**. The two numbers that matter:

   | ΔMER | Interpretation |
   |------|---------------|
   | < +0.005 | eval is representative. Optimise the prompt / model. |
   | +0.005 to +0.02 | small drift. Optimise the capture path lightly. |
   | > +0.02 | significant drift — fix the capture path before further prompt work. |

   And pay attention to `top N prod-worst clips` — those are the
   samples whose recording conditions are most different from the
   eval baseline. If they're all short utterances or all loud ones,
   you've identified the specific acoustic regime to fix.

## If you can't find 30 quiet minutes

Use `--limit 10` on the playlist. The signal is still informative if a
handful of clips show a clear ΔMER direction; you'll lose statistical
power but not the diagnostic.

## Recording quality checklist

- [ ] Recorder sample rate ≥ 44.1 kHz, mono
- [ ] Recorder format WAV or M4A (not 3gp / amr)
- [ ] Playback device audio enhancements OFF
- [ ] Phones 30 cm apart, geometry held constant
- [ ] No voice / movement during the recording
- [ ] Filenames match `<id>.wav` exactly
- [ ] No clipping in the recorded levels (clipping means your
      playback gain was too high)
