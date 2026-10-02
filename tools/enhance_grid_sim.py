"""Output-length simulation of the enhancer's frame-time grid (VideoEnhancer.frameIntervalUs / snapTimes).

Usage: python tools/enhance_grid_sim.py <times.txt>
  times.txt = the recording's video packet times in seconds, one per line:
  ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 <recording.mkv> > times.txt

Prints, for the current grid and for a grid step shortened by a fixed ratio, how much longer the output
gets than the recording, the largest lag of a frame behind its own time, and the share of empty slots.
A port of the Kotlin code (same numbers on the 2026-09-30 recording: +2475 ms, max lag 2538 ms), written
2026-10-01 when the 41 min recording failed [VideoEnhancer.verify] (limit 1000 ms) after a full render.
The fix in the app is the lag limit in snapTimes (VideoEnhancer.GRID_MAX_LAG_STEPS), not a fixed ratio:
a ratio only works while the rate drifts by less than itself.
"""
import math
import sys

R = sys.argv[1]
raw = [round(float(x) * 1000) * 1000 for x in open(R).read().split()]  # whole ms -> us
t0 = raw[0]
rel = [x - t0 for x in raw]


def jround(x):  # Kotlin roundToLong: half up
    return math.floor(x + 0.5)


def frame_interval(times):
    d = sorted(b - a for a, b in zip(times, times[1:]) if b - a > 0)
    med = d[len(d) // 2]
    reg = [x for x in d if x <= 2 * med]
    return min(max(sum(reg) / len(reg), 1e6 / 120), 1e6)


def snap(times, step):
    out = []
    last = -1
    for x in times:
        slot = max(jround(x / step), last + 1)
        out.append(jround(slot * step))
        last = slot
    return out


def report(label, step):
    out = snap(rel, step)
    lags = [o - r for o, r in zip(out, rel)]
    span_diff = (out[-1] - out[0]) - (rel[-1] - rel[0])
    # empty slots = slots without a frame between first and last
    slots = jround(out[-1] / step) + 1
    empty = slots - len(out)
    marks = []
    for minute in (5, 10, 20, 30, 40):
        i = next((k for k, r in enumerate(rel) if r >= minute * 60e6), None)
        if i is not None:
            marks.append('%dm:%d' % (minute, lags[i] // 1000))
    first_over = next((k for k, l in enumerate(lags) if l > 1_000_000), None)
    print('%-34s step=%.1fus span diff=%6d ms  maxLag=%5d ms  empty slots=%5d (%.1f%%)  lag@[%s]  first lag>1s at %s' % (
        label, step, span_diff // 1000, max(lags) // 1000, empty, 100.0 * empty / len(out), ' '.join(marks),
        ('%.1f min' % (rel[first_over] / 60e6)) if first_over is not None else 'never'))


base = frame_interval(rel)
print('frames', len(rel), 'regular-mean step %.3f us' % base)
report('current (regular mean)', base)
for eps in (0.002, 0.005, 0.01, 0.02):
    report('step x (1 - %.1f%%)' % (eps * 100), base * (1 - eps))
# overall mean including stalls (slower than the grid -> restoring drift)
overall = (rel[-1] - rel[0]) / (len(rel) - 1)
report('overall mean (incl. stalls)', overall)
print('overall mean %.3f us, 1 - base/overall = %.2f%%' % (overall, 100 * (1 - base / overall)))
