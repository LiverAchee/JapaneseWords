"""音频小工具（基于 imageio-ffmpeg 自带的 ffmpeg）：找出发声段、裁掉首尾静音、统一音量。
edge-tts 合成的每个 mp3 开头约有 0.26 秒静音、结尾约 1 秒静音，直接用会让“点读音”显得反应慢。
"""
import re
import subprocess

import imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()


def _run(args):
    return subprocess.run([FF, "-hide_banner", "-nostdin"] + args, capture_output=True, text=True,
                          errors="replace").stderr


def analyze(path, noise="-35dB", min_sil=0.03):
    """返回 dict：dur 总时长；start/end 发声段起止；gaps 发声段内部 ≥50ms 的静音个数；
    mean/max 发声段音量(dB)；low 发声段 600Hz 以下的平均音量(dB，元音越饱满越高)。"""
    out = _run(["-i", path, "-af", "silencedetect=noise=%s:d=%s" % (noise, min_sil), "-f", "null", "-"])
    m = re.search(r"Duration: (\d+):(\d+):([\d.]+)", out)
    dur = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else 0.0
    sil = []
    cur = None
    for kind, val in re.findall(r"silence_(start|end): (-?[\d.]+)", out):
        if kind == "start":
            cur = max(0.0, float(val))
        elif cur is not None:
            sil.append((cur, float(val)))
            cur = None
    if cur is not None:
        sil.append((cur, dur))
    start = sil[0][1] if sil and sil[0][0] <= 0.02 else 0.0
    end = sil[-1][0] if sil and sil[-1][1] >= dur - 0.05 else dur
    gaps = sum(1 for a, b in sil if a > start + 0.01 and b < end - 0.01 and b - a >= 0.05)
    info = dict(dur=dur, start=start, end=end, gaps=gaps, mean=-99.0, max=-99.0, low=-99.0)
    if end - start > 0.02:
        seg = ["-ss", "%.3f" % start, "-to", "%.3f" % end, "-i", path]
        o = _run(seg + ["-af", "volumedetect", "-f", "null", "-"])
        mm = re.search(r"mean_volume: (-?[\d.]+)", o)
        mx = re.search(r"max_volume: (-?[\d.]+)", o)
        o2 = _run(seg + ["-af", "lowpass=f=600,volumedetect", "-f", "null", "-"])
        lo = re.search(r"mean_volume: (-?[\d.]+)", o2)
        info.update(mean=float(mm.group(1)) if mm else -99.0, max=float(mx.group(1)) if mx else -99.0,
                    low=float(lo.group(1)) if lo else -99.0)
    return info


def trim(src, dst, info=None, lead=0.03, tail=0.12, peak=-2.0):
    """裁掉首尾静音（开头留 lead 秒、结尾留 tail 秒），把峰值调到 peak dB，重新编码成单声道 mp3。"""
    info = info or analyze(src)
    a = max(0.0, info["start"] - lead)
    b = min(info["dur"], info["end"] + tail)
    gain = max(-6.0, min(12.0, peak - info["max"])) if info["max"] > -90 else 0.0
    out = _run(["-y", "-ss", "%.3f" % a, "-to", "%.3f" % b, "-i", src, "-af", "volume=%.1fdB" % gain,
                "-ac", "1", "-ar", "24000", "-codec:a", "libmp3lame", "-b:a", "64k", dst])
    return "Error" not in out and "Invalid" not in out
