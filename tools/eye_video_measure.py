"""Measure an Eye MKV (V_MJPEG): JPEG quality factor, size, brightness, noise, broken frames.

usage: python eye_video_measure.py recording.mkv [max_frames]
Extracts the JPEGs losslessly with ffmpeg (-c:v copy), then analyses each frame. Broken frames
are found by decoding the whole file single-threaded with ffmpeg and pairing each decoder error
with the next frame number from the showinfo filter (the Eye's JPEGs have no restart markers,
so the structural check below reports UNVERIFIED for them).
"""
import glob
import json
import os
import statistics
import subprocess
import sys
import tempfile

from PIL import Image, ImageFilter

# IJG standard luminance quantization table (natural order is irrelevant for the fit below).
STD_LUMA = [16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99]


def ijg_table(q):
    s = 5000 / q if q < 50 else 200 - 2 * q
    return [min(255, max(1, (v * s + 50) // 100)) for v in STD_LUMA]


def estimate_quality(luma_table):
    """Closest IJG quality for the luminance table (sum of values is order-independent)."""
    target = sorted(luma_table)
    best = min(range(1, 101), key=lambda q: sum(abs(a - b) for a, b in zip(sorted(ijg_table(q)), target)))
    return best


def integrity(data):
    """Python port of JpegIntegrity.check (restart-marker consistency)."""
    if len(data) < 4 or data[0] != 0xFF or data[1] != 0xD8:
        return "BAD_STRUCTURE"
    w = h = 0
    hmax = vmax = 1
    ri = 0
    i = 2
    while True:
        if i + 4 > len(data):
            return "TRUNCATED"
        if data[i] != 0xFF:
            return "BAD_STRUCTURE"
        m = data[i + 1]
        if m == 0xFF:
            i += 1
            continue
        seg = (data[i + 2] << 8) | data[i + 3]
        body = i + 4
        if m == 0xDD:
            ri = (data[body] << 8) | data[body + 1]
        elif 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
            h = (data[body + 1] << 8) | data[body + 2]
            w = (data[body + 3] << 8) | data[body + 4]
            for c in range(data[body + 5]):
                hv = data[body + 6 + c * 3 + 1]
                hmax, vmax = max(hmax, hv >> 4), max(vmax, hv & 15)
        i += 2 + seg
        if m == 0xDA:
            break
    rst = 0
    p = i
    eoi = False
    while p + 1 < len(data):
        if data[p] != 0xFF:
            p += 1
            continue
        m = data[p + 1]
        if m == 0x00:
            p += 2
        elif m == 0xFF:
            p += 1
        elif 0xD0 <= m <= 0xD7:
            if m - 0xD0 != rst % 8:
                return "RESTART_SEQUENCE"
            rst += 1
            p += 2
        elif m == 0xD9:
            eoi = True
            break
        else:
            return "BAD_STRUCTURE"
    if not eoi:
        return "TRUNCATED"
    if ri == 0:
        return "UNVERIFIED"
    mcus = -(-w // (8 * hmax)) * -(-h // (8 * vmax))
    return "OK" if rst == -(-mcus // ri) - 1 else "RESTART_COUNT"


def noise_sigma(gray):
    """Immerkaer fast noise estimate on the Laplacian-like kernel (sigma in 8-bit levels)."""
    k = ImageFilter.Kernel((3, 3), [1, -2, 1, -2, 4, -2, 1, -2, 1], scale=1, offset=128)
    lap = gray.filter(k)
    hist = lap.histogram()
    n = sum(hist)
    mad = sum(abs(v - 128) * c for v, c in enumerate(hist)) / max(n, 1)
    return mad * (3.14159265 / 2) ** 0.5 / 6


def broken_frames(src):
    """1-based frame numbers (and times) whose decode logged an mjpeg error."""
    import re
    log = subprocess.run(["ffmpeg", "-hide_banner", "-threads", "1", "-i", src, "-vf", "showinfo", "-f", "null", "-"],
                         capture_output=True, text=True, errors="replace").stderr.splitlines()
    out, pending = [], False
    for line in log:
        if "[mjpeg" in line and ("error" in line or "overread" in line or "bad vlc" in line):
            pending = True
        m = re.search(r"n:\s*(\d+) pts:\s*\d+ pts_time:([\d.]+)", line)
        if m and pending:
            out.append((int(m.group(1)) + 1, float(m.group(2))))
            pending = False
    return out


def main():
    src = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 600
    out = tempfile.mkdtemp(prefix="eyeframes_")
    subprocess.run(["ffmpeg", "-v", "error", "-i", src, "-map", "0:v:0", "-c:v", "copy",
                    "-frames:v", str(limit), os.path.join(out, "%05d.jpg")], check=True)
    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                       "stream=width,height,avg_frame_rate,r_frame_rate:packet=pts_time",
                                       "-read_intervals", "%+30", "-of", "json", src],
                                      capture_output=True, text=True, check=True).stdout)
    pts = [float(p["pts_time"]) for p in probe.get("packets", []) if "pts_time" in p]
    gaps = [b - a for a, b in zip(pts, pts[1:])]

    sizes, quals, lumas, dark, bright, sigmas = [], [], [], [], [], []
    status = {}
    for f in sorted(glob.glob(os.path.join(out, "*.jpg"))):
        data = open(f, "rb").read()
        sizes.append(len(data))
        st = integrity(data)
        status[st] = status.get(st, 0) + 1
        im = Image.open(f)
        q = getattr(im, "quantization", None) or {}
        if 0 in q:
            quals.append(estimate_quality(list(q[0])))
        g = im.convert("L").crop((0, 0, im.width, im.height - 8))  # skip the 8 noisy rows
        hist = g.histogram()
        n = sum(hist)
        lumas.append(sum(v * c for v, c in enumerate(hist)) / n)
        dark.append(sum(hist[:16]) / n)
        bright.append(sum(hist[240:]) / n)
        sigmas.append(noise_sigma(g))

    def s(v, fmt="%.1f"):
        return "n/a" if not v else "min " + fmt % min(v) + " / median " + fmt % statistics.median(v) + " / max " + fmt % max(v)

    st0 = probe["streams"][0]
    print(f"stream {st0.get('width')}x{st0.get('height')} r_frame_rate={st0.get('r_frame_rate')} avg={st0.get('avg_frame_rate')}")
    if gaps:
        print(f"frame interval ms (first 30s): {s([g * 1000 for g in gaps])}")
    print(f"frames analysed: {len(sizes)}")
    print(f"JPEG size KB: {s([x / 1024 for x in sizes])}")
    print(f"IJG quality estimate: {s(quals, '%d')}")
    print(f"mean luma (0-255): {s(lumas)}")
    print(f"share of pixels <16: {s([d * 100 for d in dark])} %   >=240: {s([b * 100 for b in bright])} %")
    print(f"noise sigma (8-bit levels): {s(sigmas, '%.2f')}")
    print(f"integrity: {status}")
    broken = broken_frames(src)
    print(f"frames with decoder errors (whole file): {len(broken)}" +
          (" at " + ", ".join("#%d %.2fs" % b for b in broken[:20]) + (" ..." if len(broken) > 20 else "") if broken else ""))
    print(f"frames kept in {out}")


if __name__ == "__main__":
    main()
