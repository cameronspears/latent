#!/usr/bin/env python3
"""Writes jni/bluenoise.h: a 64x64 blue-noise threshold map (void-and-cluster, Ulichney 1993).

The viewfinder dithers its 16-bit picture with it: unlike an ordered (Bayer) pattern it has no
crosshatch, and shifted every frame it reads as fine moving film grain. Deterministic (seeded).
  python3 tools/bluenoise.py > jni/bluenoise.h"""
import numpy as np

N, SIGMA = 64, 1.9

def energy_kernel():
    d = np.minimum(np.arange(N), N - np.arange(N)).astype(float)
    return np.exp(-(d[:, None] ** 2 + d[None, :] ** 2) / (2 * SIGMA ** 2))

K = np.fft.rfft2(energy_kernel())

def energy(bits):
    return np.fft.irfft2(np.fft.rfft2(bits) * K, s=(N, N))

def main():
    rng = np.random.default_rng(1)
    bits = np.zeros((N, N))
    ones = int(N * N * 0.1)
    bits.flat[rng.choice(N * N, ones, replace=False)] = 1
    while True:   # relax the initial pattern: move the tightest cluster into the largest void
        e = energy(bits)
        c = np.argmax(np.where(bits == 1, e, -np.inf)); bits.flat[c] = 0
        e = energy(bits)
        v = np.argmin(np.where(bits == 0, e, np.inf))
        if v == c: bits.flat[c] = 1; break
        bits.flat[v] = 1
    rank = np.zeros(N * N, dtype=int)
    proto = bits.copy()
    b = proto.copy()
    for r in range(ones - 1, -1, -1):   # phase 1: remove clusters
        e = energy(b); c = np.argmax(np.where(b == 1, e, -np.inf)); b.flat[c] = 0; rank[c] = r
    b = proto.copy()
    for r in range(ones, N * N):        # phases 2 and 3: fill voids
        e = energy(b); v = np.argmin(np.where(b == 0, e, np.inf)); b.flat[v] = 1; rank[v] = r
    t = (rank * 256 // (N * N)).astype(int)
    print("/* 64x64 blue-noise thresholds 0..255, void-and-cluster (tools/bluenoise.py). */")
    print("#define FL_BN_BITS 6")
    print("static const unsigned char fl_bluenoise[64 * 64] = {")
    for y in range(N):
        print("    " + ", ".join(str(v) for v in t[y * N:(y + 1) * N]) + ",")
    print("};")

main()
