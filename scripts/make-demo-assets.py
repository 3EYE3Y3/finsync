#!/usr/bin/env python3
"""Builds the sample library for FinSync's demo mode (app/src/main/assets/demo/).

Everything is generated here: chords, sweeps and clicks synthesised with numpy, cover art drawn
with Pillow. No third-party recordings or images, so it is all CC0 (see docs/DEMO-ASSETS.md).
Needs: numpy, soundfile (libsndfile 1.1+ for MP3), mutagen, Pillow.
Run:   python3 scripts/make-demo-assets.py
"""
import json, math, os, shutil
import numpy as np
import soundfile as sf
from mutagen.flac import FLAC
from mutagen.easyid3 import EasyID3
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "demo")
RATE = 22050
SECONDS = 9

def note(f):  # semitones from A4
    return 440.0 * 2 ** (f / 12)

def tone(freqs, seconds=SECONDS, kind="sine", sweep=None):
    t = np.arange(int(RATE * seconds)) / RATE
    out = np.zeros_like(t)
    for i, f in enumerate(freqs):
        if sweep:
            f0, f1 = sweep
            phase = 2 * np.pi * (f0 * t + (f1 - f0) * t * t / (2 * seconds))
        else:
            phase = 2 * np.pi * f * t
        out += (np.sin(phase) if kind == "sine" else np.sign(np.sin(phase)) * 0.3) / (i + 1.5)
    env = np.minimum(1, t / 0.4) * np.minimum(1, (seconds - t) / 1.2)  # fade in and out
    return (out / max(1e-9, np.abs(out).max()) * 0.6 * env).astype(np.float32)

def clicks(bpm, seconds=SECONDS, pitch=1000):
    t = np.arange(int(RATE * seconds)) / RATE
    out = np.zeros_like(t)
    beat = 60.0 / bpm
    for k in range(int(seconds / beat)):
        s = int(k * beat * RATE)
        n = int(0.05 * RATE)
        p = pitch * (1.5 if k % 4 == 0 else 1)
        out[s:s + n] += np.sin(2 * np.pi * p * t[:n]) * np.exp(-t[:n] * 60)
    return (out * 0.6).astype(np.float32)

LIBRARY = [
    # artist, album, year, format, tracks [(title, audio)]
    ("The Sine Waves", "Pure Tones", 2024, "flac", [
        ("A Major Chord", lambda: tone([note(0), note(4), note(7)])),
        ("Slow Sweep", lambda: tone([0], sweep=(220, 880))),
        ("Octaves", lambda: tone([note(-12), note(0), note(12)])),
        ("Perfect Fifths", lambda: tone([note(-5), note(2), note(9)])),
    ]),
    ("The Sine Waves", "Second Harmonic", 2025, "mp3", [
        ("Minor Thoughts", lambda: tone([note(0), note(3), note(7)])),
        ("Rising", lambda: tone([0], sweep=(330, 660))),
        ("Square Dance", lambda: tone([note(-12), note(-5)], kind="square")),
    ]),
    ("Chord Quartet", 'Questions: "Answers"?', 2023, "flac", [
        ("Why? Because: Yes*", lambda: tone([note(-3), note(0), note(4)])),  # characters SD cards reject
        ("Suspended", lambda: tone([note(0), note(5), note(7)])),
        ("Resolution", lambda: tone([note(-12), note(0), note(4), note(7)])),
    ]),
    ("Metronome Collective", "Tempo Studies", 2022, "mp3", [
        ("Largo (60 BPM)", lambda: clicks(60)),
        ("Andante (90 BPM)", lambda: clicks(90, pitch=800)),
        ("Presto (160 BPM)", lambda: clicks(160, pitch=1200)),
    ]),
]
LYRICS = {"A Major Chord": ["(a sample tone: no words)", "Three notes, played together", "Fading out"],
          "Largo (60 BPM)": ["One", "Two", "Three", "Four"]}
COLOURS = [(0, 56, 76), (120, 40, 60), (40, 90, 50), (90, 70, 20)]

def cover(path, title, artist, colour):
    img = Image.new("RGB", (400, 400), colour)
    d = ImageDraw.Draw(img)
    for i in range(12):  # concentric waves
        r = 30 + i * 28
        d.ellipse((200 - r, 200 - r, 200 + r, 200 + r), outline=tuple(min(255, c + 60 + i * 10) for c in colour), width=3)
    d.rectangle((0, 330, 400, 400), fill=(255, 255, 255))
    d.text((14, 342), title[:40], fill=(0, 0, 0))
    d.text((14, 364), f"{artist}  (FinSync sample)", fill=(60, 60, 60))
    img.save(path, "JPEG", quality=80)

def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    tracks, albums = [], []
    n = 0
    for a, (artist, album, year, fmt, items) in enumerate(LIBRARY):
        album_id = f"al{a + 1}"
        cover(os.path.join(OUT, f"{album_id}.jpg"), album, artist, COLOURS[a % len(COLOURS)])
        albums.append({"id": album_id, "name": album, "artist": artist, "year": year})
        for i, (title, make) in enumerate(items, 1):
            n += 1
            tid = f"t{n:02d}"
            file = f"{tid}.{fmt}"
            path = os.path.join(OUT, file)
            audio = make()
            if fmt == "flac":
                sf.write(path, audio, RATE, format="FLAC", subtype="PCM_16")
                tags = FLAC(path)
                tags.update({"title": title, "artist": artist, "albumartist": artist, "album": album,
                             "tracknumber": str(i), "date": str(year), "comment": "FinSync sample (CC0)"})
                tags.save()
            else:
                sf.write(path, audio, RATE, format="MP3", subtype="MPEG_LAYER_III")
                tags = EasyID3()
                tags.update({"title": title, "artist": artist, "albumartist": artist, "album": album,
                             "tracknumber": str(i), "date": str(year)})
                tags.save(path)
            tracks.append({"id": tid, "name": title, "artist": artist, "album": album, "albumId": album_id,
                           "track": i, "year": year, "file": file, "container": fmt,
                           "path": f"/music/{artist}/{album} ({year})/{i:02d} {title}.{fmt}",
                           "runTimeTicks": int(len(audio) / RATE * 10_000_000),
                           "lyrics": [{"text": l, "start": k * 20_000_000} for k, l in enumerate(LYRICS.get(title, []))]})
    playlists = [{"id": "pl1", "name": "Sample Mix", "trackIds": ["t02", "t06", "t11", "t08", "t01"]}]
    with open(os.path.join(OUT, "library.json"), "w") as f:
        json.dump({"server": "FinSync sample library", "albums": albums, "tracks": tracks, "playlists": playlists}, f, indent=1, ensure_ascii=False)
    total = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT))
    print(f"{len(tracks)} tracks, {len(albums)} albums, {total / 1e6:.2f} MB in {OUT}")

if __name__ == "__main__":
    main()
