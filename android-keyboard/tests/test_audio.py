import array
import hashlib
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
REPO = PROJECT.parent
SCRIPT = PROJECT / "scripts/extract-clicks.py"
SOURCE = REPO / ".hoplite/attachments/art_upload_600136080d6747ec939eed3469b16a22/u_a4gfvwagf1-iphone-keyboard-typing-sound-effect-336778.mp3"
RAW_DIR = PROJECT / "res/raw"
PREVIEW = PROJECT / "build/evidence/typing-sound-preview.wav"
OUTPUTS = ("key_click.wav", "delete_click.wav", "space_click.wav")
EXPECTED_SHA256 = "c0b22cea8db27239d17d9fe12beac2b69cc52f850cd990bdab7cbd25c962d1cd"
SAMPLE_RATE = 44_100
CHANNELS = 2
CROP_START_FRAME = 29_400
CROP_END_FRAME = 30_020
FADE_FRAMES = 22
PREVIEW_CADENCE_FRAMES = 6_615
PREVIEW_REPEAT_COUNT = 8


def read_pcm(path):
    with wave.open(str(path), "rb") as source:
        params = source.getparams()
        pcm = array.array("h")
        pcm.frombytes(source.readframes(source.getnframes()))
    if sys.byteorder != "little":
        pcm.byteswap()
    return params, pcm


def decode_source_pcm():
    result = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-v", "error", "-i", str(SOURCE),
            "-map", "0:a:0", "-f", "s16le", "-acodec", "pcm_s16le",
            "-ar", str(SAMPLE_RATE), "-ac", str(CHANNELS), "pipe:1",
        ],
        check=True,
        stdout=subprocess.PIPE,
    )
    pcm = array.array("h")
    pcm.frombytes(result.stdout)
    if sys.byteorder != "little":
        pcm.byteswap()
    return pcm


def expected_crop():
    decoded = decode_source_pcm()
    start = CROP_START_FRAME * CHANNELS
    end = CROP_END_FRAME * CHANNELS
    click = array.array("h", decoded[start:end])
    frames = len(click) // CHANNELS
    for offset in range(FADE_FRAMES):
        for frame in (offset, frames - 1 - offset):
            numerator = offset
            for channel in range(CHANNELS):
                index = frame * CHANNELS + channel
                product = click[index] * numerator
                click[index] = (
                    (product + FADE_FRAMES // 2) // FADE_FRAMES
                    if product >= 0
                    else -((-product + FADE_FRAMES // 2) // FADE_FRAMES)
                )
    return click


@unittest.skipUnless(SOURCE.is_file(), "user-supplied source MP3 is not available")
class SourceAudioTests(unittest.TestCase):
    def test_pinned_source_fingerprint_and_exact_source_crop(self):
        self.assertEqual(hashlib.sha256(SOURCE.read_bytes()).hexdigest(), EXPECTED_SHA256)
        expected = expected_crop()
        for name in OUTPUTS:
            params, pcm = read_pcm(RAW_DIR / name)
            self.assertEqual((params.nchannels, params.sampwidth, params.framerate), (2, 2, SAMPLE_RATE))
            self.assertEqual(params.nframes, CROP_END_FRAME - CROP_START_FRAME)
            self.assertEqual(len(pcm), len(expected))
            self.assertLessEqual(max(abs(actual - source) for actual, source in zip(pcm, expected)), 2)

    def test_outputs_share_one_unlabelled_real_click(self):
        contents = [(RAW_DIR / name).read_bytes() for name in OUTPUTS]
        self.assertEqual(contents[0], contents[1])
        self.assertEqual(contents[0], contents[2])

    def test_preview_repeats_the_source_click_at_fixed_cadence(self):
        params, preview = read_pcm(PREVIEW)
        click = expected_crop()
        self.assertEqual((params.nchannels, params.sampwidth, params.framerate), (2, 2, SAMPLE_RATE))
        click_frames = CROP_END_FRAME - CROP_START_FRAME
        expected_frames = (PREVIEW_REPEAT_COUNT - 1) * PREVIEW_CADENCE_FRAMES + click_frames
        self.assertEqual(params.nframes, expected_frames)
        for repeat in range(PREVIEW_REPEAT_COUNT):
            start = repeat * PREVIEW_CADENCE_FRAMES * CHANNELS
            self.assertEqual(preview[start:start + len(click)], click)
            if repeat:
                gap_start = (repeat - 1) * PREVIEW_CADENCE_FRAMES * CHANNELS + len(click)
                self.assertFalse(any(preview[gap_start:start]))

    def test_extraction_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary:
            out_dir = Path(temporary) / "raw"
            preview = Path(temporary) / "preview.wav"
            subprocess.run(
                [sys.executable, str(SCRIPT), "--source", str(SOURCE), "--output-dir", str(out_dir), "--preview", str(preview)],
                check=True,
                stdout=subprocess.PIPE,
            )
            for name in OUTPUTS:
                self.assertEqual((out_dir / name).read_bytes(), (RAW_DIR / name).read_bytes())
            self.assertEqual(preview.read_bytes(), PREVIEW.read_bytes())

    def test_default_preview_path_is_inside_android_keyboard(self):
        with tempfile.TemporaryDirectory() as temporary:
            out_dir = Path(temporary) / "raw"
            PREVIEW.unlink(missing_ok=True)
            subprocess.run(
                [sys.executable, str(SCRIPT), "--source", str(SOURCE), "--output-dir", str(out_dir)],
                check=True,
                stdout=subprocess.PIPE,
            )
            self.assertTrue(PREVIEW.is_file())

    def test_changed_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            wrong_source = Path(temporary) / "wrong.mp3"
            wrong_source.write_bytes(b"not the pinned MP3")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--source", str(wrong_source), "--output-dir", temporary, "--preview", str(Path(temporary) / "preview.wav")],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"source SHA-256 mismatch", result.stderr)


class AssetSanityTests(unittest.TestCase):
    def test_assets_are_short_pcm16_non_silent_and_unclipped(self):
        for name in OUTPUTS:
            params, pcm = read_pcm(RAW_DIR / name)
            self.assertEqual((params.nchannels, params.sampwidth, params.framerate), (2, 2, SAMPLE_RATE))
            self.assertEqual(params.nframes, CROP_END_FRAME - CROP_START_FRAME)
            self.assertGreater(max(abs(sample) for sample in pcm), 1_000)
            self.assertLess(max(abs(sample) for sample in pcm), 32_767)
            self.assertEqual(pcm[:CHANNELS], array.array("h", [0, 0]))
            self.assertEqual(pcm[-CHANNELS:], array.array("h", [0, 0]))


if __name__ == "__main__":
    unittest.main()
