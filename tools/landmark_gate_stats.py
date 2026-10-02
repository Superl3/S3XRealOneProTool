"""Landmark-log statistics for the not-ready gate (HandReadiness).

Usage: python tools/landmark_gate_stats.py <dir with landmarks-*.jsonl>
Pull the logs first (setting "Landmark log" on):
  adb pull /sdcard/Android/data/io.github.xrealeyetools/files/landmarks/<file>.jsonl <dir>

Per file: how many hand frames the gate (edge margin 0.02 on all 21 image landmarks, or handedness
score < 0.6) would block, which landmarks trigger it, and how many frames stay ready if the wrist,
or everything but the fingertips, were left out of the edge test. Written 2026-10-01 for review
finding M3 (docs/superpowers/specs/2026-09-30-code-review-532983e.md).
"""
import glob
import json
import os
import sys
from collections import Counter

D = sys.argv[1] if len(sys.argv) > 1 else '.'
M = 0.02
HI = 1 - M
TIPS = (4, 8, 12, 16, 20)


def edge_idx(lm):
    """Indices of landmarks within the edge margin (HandReadiness.EDGE_MARGIN)."""
    out = []
    for i in range(21):
        x, y = lm[3 * i], lm[3 * i + 1]
        if x < M or x > HI or y < M or y > HI:
            out.append(i)
    return out


tot = Counter()
for p in sorted(glob.glob(os.path.join(D, '*.jsonl'))):
    c = Counter()
    trig = Counter()
    wrist_y = []
    sc_hist = Counter()
    bottom_only = 0
    for line in open(p, encoding='utf-8'):
        try:
            o = json.loads(line)
        except Exception:
            c['badline'] += 1
            continue
        if o.get('lost'):
            c['lost'] += 1
            continue
        lm = o.get('lm')
        if not lm or len(lm) != 63:
            c['nolm'] += 1
            continue
        c['hand'] += 1
        score = o.get('score')
        low = score is not None and score < 0.6
        if score is not None:
            sc_hist[round(score, 1)] += 1
        e = edge_idx(lm)
        wrist_y.append(lm[1])
        if e:
            c['edge'] += 1
            for i in e:
                trig[i] += 1
            if set(e) == {0}:
                c['edge_wrist_only'] += 1
            if all(i == 0 or i in () for i in e):
                pass
            if not [i for i in e if i != 0]:
                c['edge_only_wrist_ok_without_wrist'] += 1
            if not [i for i in e if i not in (0,)] and low:
                pass
            if [i for i in e if i in TIPS]:
                c['edge_tip'] += 1
            ys = [lm[3 * i + 1] for i in e]
            xs = [lm[3 * i] for i in e]
            if all(y > HI for y in ys) and all(M <= x <= HI for x in xs):
                bottom_only += 1
        if low:
            c['low'] += 1
        if e or low:
            c['not_ready'] += 1
        if (not [i for i in e if i != 0]) and not low:
            c['ready_if_wrist_excluded'] += 1
        if (not [i for i in e if i in TIPS]) and not low:
            c['ready_if_tips_only'] += 1
        if not low:
            c['ready_if_edge_gate_off'] += 1
    n = c['hand']
    print('==', os.path.basename(p), 'hand frames', n, 'lost', c['lost'])
    if not n:
        continue
    pct = lambda k: '%.1f%%' % (100.0 * c[k] / n)
    print('  not-ready (edge or low score):', pct('not_ready'),
          '| edge:', pct('edge'), '| low score:', pct('low'))
    print('  edge only due to wrist (idx 0):', pct('edge_wrist_only'),
          '| edge, only bottom edge:', '%.1f%%' % (100.0 * bottom_only / n),
          '| edge touching a fingertip:', pct('edge_tip'))
    print('  ready if wrist excluded:', pct('ready_if_wrist_excluded'),
          '| ready if only fingertips checked:', pct('ready_if_tips_only'),
          '| ready if edge gate off:', pct('ready_if_edge_gate_off'))
    print('  top triggering landmark idx:', trig.most_common(6))
    ys = sorted(wrist_y)
    print('  wrist y p10/p50/p90/p99: %.3f %.3f %.3f %.3f' % (
        ys[int(0.10 * (len(ys) - 1))], ys[int(0.50 * (len(ys) - 1))],
        ys[int(0.90 * (len(ys) - 1))], ys[int(0.99 * (len(ys) - 1))]))
    tot.update(c)
print()
print('TOTAL hand frames', tot['hand'])
