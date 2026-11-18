#!/usr/bin/env python3
"""Encode README previews from the two full-screen Android emulator recordings.

Run: python3 tools/render-readme-demo.py   (requires ffmpeg, ffprobe, img2webp)

The MP4s in assets/video are the masters. Capture public content in the installed
release app with `adb emu screenrecord start --size 720x1600 --fps 24 take.webm`
and `adb emu screenrecord stop`. Join the shelf/reader, import, video and Chrome
lookup takes without changing their speed or screen geometry. Never record setup,
private content, credentials or notifications. The poster is a frame of the MP4.
"""

from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

MEDIA = Path(__file__).resolve().parents[1] / "assets/video"
WEBP_FPS = 10


def ffmpeg(master, *args):
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", master, "-an", *args], check=True)


def encode(master, poster):
    duration_ms = round(1000 * float(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
        "stream=duration", "-of", "csv=p=0", master,
    ])))
    with TemporaryDirectory() as tmp:
        # File names retain the recorder's timestamps. Hold an unchanged screen
        # until the next visible change, including the hold at the end of the clip.
        ffmpeg(master, "-vf", f"fps={WEBP_FPS},scale=432:-2:flags=lanczos,mpdecimate",
               "-fps_mode", "passthrough", "-enc_time_base", f"1:{WEBP_FPS}",
               "-frame_pts", "1", "-compression_level", "1", Path(tmp) / "%08d.png")
        frames = sorted(Path(tmp).glob("*.png"))
        starts = [int(frame.stem) * 1000 // WEBP_FPS for frame in frames]
        if not starts or starts[0] != 0 or starts[-1] >= duration_ms:
            raise ValueError(f"Invalid capture timestamps in {master}")
        cmd = ["img2webp", "-loop", "0", "-sharp_yuv", "-lossy", "-q", "90", "-m", "3"]
        for frame, start, stop in zip(frames, starts, starts[1:] + [duration_ms]):
            cmd.extend(["-d", str(stop - start), frame])
        subprocess.run([*cmd, "-o", master.with_suffix(".webp")], check=True)

    # The GIF is a compatibility download; the MP4 retains the original 24 fps.
    ffmpeg(master, "-filter_complex",
           "fps=6,scale=396:-2:flags=lanczos,split[a][b];"
           "[a]palettegen=stats_mode=diff[p];"
           "[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle[out]",
           "-map", "[out]", "-loop", "0", master.with_suffix(".gif"))
    ffmpeg(master, "-ss", "7.5", "-frames:v", "1", "-vf", "scale=576:-2:flags=lanczos",
           "-q:v", "2", "-update", "1", poster)


if __name__ == "__main__":
    for suffix in ("", "-zh"):
        encode(MEDIA / f"peekturn-intro{suffix}.mp4", MEDIA / f"peekturn-intro-cover{suffix}.jpg")
