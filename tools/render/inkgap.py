"""Ink-coverage diagnostic shared by compare.py and selftest.py (numpy only)."""
import numpy as np

def dilate1(g):
    pad = np.pad(g, 1, mode='constant')
    return np.max([pad[y:y + g.shape[0], x:x + g.shape[1]] for y in range(3) for x in range(3)], axis=0)

def ink_gap(src, other):
    """Ink pixels (darkness >= 128) of `src` that have no ink (>= 64) within 1px in `other`."""
    return (src >= 128) & (dilate1(other) < 64)
