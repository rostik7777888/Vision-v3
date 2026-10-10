#!/usr/bin/env python3
"""Extract one unaltered typing transient from the supplied MP3."""

from __future__ import annotations

import argparse
import array
import hashlib
import shutil
import subprocess
import sys
import wave
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = REPO_ROOT / ".hoplite/attachments/art_upload_600136080d6747ec939eed3469b16a22/u_a4gfvwagf1-iphone-keyboard-typing-sound-effect-336778.mp3"
EXPECTED_SHA256 = "c0b22cea8db27239d17d9fe12beac2b69cc52f850cd990bdab7cbd25c962d1cd"
SAMPLE_RATE = 44_100
CHANNELS = 2
CROP_START_FRAME = 29_400
CROP_END_FRAME = 30_020
FADE_FRAMES = 22
PREVIEW_REPEAT_COUNT = 8
PREVIEW_CADENCE_FRAMES = 6_615
OUTPUT_NAMES = ("key_click.wav", "delete_click.wav", "space_click.wav")


def source_digest(source: Path) -> str:
    return hashlib.sha256(source.read_bytes()).hexdigest()


def decode_source(source: Path) -> array.array[int]:
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required to decode the supplied MP3")
    result = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-v", "error", "-i", str(source),
            "-map", "0:a:0", "-f", "s16le", "-acodec", "pcm_s16le",
            "-ar", str(SAMPLE_RATE), "-ac", str(CHANNELS), "pipe:1",
        ],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    pcm = array.array("h")
    pcm.frombytes(result.stdout)
    if sys.byteorder != "little":
        pcm.byteswap()
    if len(pcm) % CHANNELS:
        raise RuntimeError("ffmpeg returned an incomplete PCM frame")
    return pcm


def fade_edges(pcm: array.array[int]) -> None:
    frames = len(pcm) // CHANNELS
    if frames <= 2 * FADE_FRAMES:
        raise ValueError("crop is too short for the edge fades")
    for offset in range(FADE_FRAMES):
        for frame, gain_numerator in (
            (offset, offset),
            (frames - 1 - offset, offset),
        ):
            for channel in range(CHANNELS):
                index = frame * CHANNELS + channel
                product = pcm[index] * gain_numerator
                pcm[index] = (
                    (product + FADE_FRAMES // 2) // FADE_FRAMES
                    if product >= 0
                    else -((-product + FADE_FRAMES // 2) // FADE_FRAMES)
                )


def write_wav(path: Path, pcm: array.array[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = array.array("h", pcm)
    if sys.byteorder != "little":
        encoded.byteswap()
    with wave.open(str(path), "wb") as output:
        output.setnchannels(CHANNELS)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(encoded.tobytes())


def build_preview(click: array.array[int]) -> array.array[int]:
    preview = array.array("h")
    click_frames = len(click) // CHANNELS
    silence = array.array("h", [0]) * ((PREVIEW_CADENCE_FRAMES - click_frames) * CHANNELS)
    for index in range(PREVIEW_REPEAT_COUNT):
        if index:
            preview.extend(silence)
        preview.extend(click)
    return preview


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parents[1] / "res/raw")
    parser.add_argument("--preview", type=Path, default=Path(__file__).resolve().parents[1] / "build/evidence/typing-sound-preview.wav")
    args = parser.parse_args()

    source = args.source.expanduser().resolve()
    if not source.is_file():
        parser.error(f"source MP3 not found: {source}")
    digest = source_digest(source)
    if digest != EXPECTED_SHA256:
        parser.error(f"source SHA-256 mismatch: expected {EXPECTED_SHA256}, got {digest}")

    try:
        decoded = decode_source(source)
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        parser.error(f"could not decode source MP3: {error}")
    if len(decoded) // CHANNELS < CROP_END_FRAME:
        parser.error("source MP3 is shorter than the pinned crop")

    start = CROP_START_FRAME * CHANNELS
    end = CROP_END_FRAME * CHANNELS
    click = array.array("h", decoded[start:end])
    fade_edges(click)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name in OUTPUT_NAMES:
        write_wav(args.output_dir / name, click)
    write_wav(args.preview, build_preview(click))

    duration_ms = (CROP_END_FRAME - CROP_START_FRAME) * 1_000 / SAMPLE_RATE
    print(
        f"source sha256={digest}; crop frames={CROP_START_FRAME}:{CROP_END_FRAME} "
        f"({duration_ms:.2f} ms); outputs={len(OUTPUT_NAMES)}; "
        f"preview={PREVIEW_REPEAT_COUNT} clicks at 150 ms cadence"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
