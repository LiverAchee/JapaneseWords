"""生成选对 / 选错的短提示音（各约 0.15 秒）和“斩”的刀光音效（约 0.22 秒）到 android res/raw。
用法: python tools/make_fx.py
"""
import math
import os
import random
import struct
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android", "app", "src", "main", "res", "raw")
RATE = 22050


def tone(freq, ms, vol=0.5, square=False):
    n = int(RATE * ms / 1000)
    out = []
    for i in range(n):
        t = i / RATE
        v = math.sin(2 * math.pi * freq * t)
        if square:
            v = 0.6 * (1 if v > 0 else -1) + 0.4 * v
        fade = min(1.0, i / (RATE * 0.005), (n - i) / (RATE * 0.02))  # 淡入淡出，避免爆音
        out.append(v * vol * fade)
    return out


def slash():
    """斩：一声快速上扫的挥刀风声，接一下金属“锵”的余响。全部合成，不取自任何现成音效。"""
    rnd = random.Random(7)
    n_whoosh = int(RATE * 0.09)
    out = []
    # 风声：白噪声过一个中心频率 300 → 1800 Hz 上扫的带通滤波器
    y1 = y2 = x1 = x2 = 0.0
    for i in range(n_whoosh):
        k = i / n_whoosh
        fc = 300 * (1800 / 300) ** k
        q = 2.5
        w0 = 2 * math.pi * fc / RATE
        alpha = math.sin(w0) / (2 * q)
        b0, a0, a1, a2 = alpha, 1 + alpha, -2 * math.cos(w0), 1 - alpha
        x = rnd.uniform(-1, 1)
        y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0
        x2, x1, y2, y1 = x1, x, y1, y
        env = (k / 0.7) ** 2 if k < 0.7 else (1 - k) / 0.3
        out.append(y * env * 2.2)
    # 金属余响：几条不成谐波关系的分音，各自指数衰减
    n_ring = int(RATE * 0.14)
    partials = [(820, 0.34, 0.05), (1270, 0.24, 0.035), (1760, 0.15, 0.025), (2390, 0.08, 0.018)]
    ring = []
    for i in range(n_ring):
        t = i / RATE
        v = sum(a * math.sin(2 * math.pi * f * t + f) * math.exp(-t / d) for f, a, d in partials)
        v += rnd.uniform(-1, 1) * 0.25 * math.exp(-t / 0.008)  # 撞击瞬间的一点“嚓”
        ring.append(v * min(1.0, i / (RATE * 0.002)))
    start = n_whoosh - int(RATE * 0.015)  # 余响压在风声尾巴上，衔接更紧
    total = [0.0] * (start + n_ring)
    for i, v in enumerate(out):
        total[i] += v
    for i, v in enumerate(ring):
        total[start + i] += v
    peak = max(abs(v) for v in total)
    return [v / peak * 0.85 for v in total]


def save(name, samples):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


if __name__ == "__main__":
    save("fx_ok.wav", tone(988, 60) + tone(1319, 90))        # 上扬的“叮咚”
    save("fx_bad.wav", tone(196, 150, vol=0.45, square=True))  # 低沉的“嘟”
    save("fx_ban.wav", slash())                               # 斩：挥刀风声 + 金属“锵”
    print("ok")
