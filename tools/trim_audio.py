"""裁掉 assets/audio 里单词和例句读音的首尾静音，并统一音量（假名读音由 gen_kana_audio.py 自己处理）。
edge-tts 合成的文件开头约有 0.26 秒静音，不裁的话点“读音”会显得慢半拍。
已经裁过的（开头静音不到 0.06 秒）自动跳过，所以新合成了音频之后可以放心重跑。

用法: python tools/trim_audio.py
"""
import os
from concurrent.futures import ThreadPoolExecutor

from audio_util import analyze, trim
from common import ASSETS

OUT = os.path.join(ASSETS, "audio")


def work(name):
    path = os.path.join(OUT, name)
    try:
        info = analyze(path)
        if info["start"] < 0.06 and info["dur"] - info["end"] < 0.3:
            return "skip"
        if info["end"] - info["start"] < 0.1:
            return "bad " + name  # 几乎没有声音，别动它
        tmp = path + ".trim.mp3"
        if trim(path, tmp, info) and os.path.getsize(tmp) > 800:
            os.replace(tmp, path)
            return "ok"
        if os.path.exists(tmp):
            os.remove(tmp)
        return "fail " + name
    except Exception as e:  # noqa: BLE001
        return "fail %s %r" % (name, e)


def main():
    names = [n for n in sorted(os.listdir(OUT)) if n.endswith(".mp3") and not n.startswith("kana_")
             and not n.endswith(".trim.mp3")]
    before = sum(os.path.getsize(os.path.join(OUT, n)) for n in names)
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(work, names))
    after = sum(os.path.getsize(os.path.join(OUT, n)) for n in names)
    print("ok=%d skip=%d other=%d  %.1fMB -> %.1fMB" % (res.count("ok"), res.count("skip"),
          len(res) - res.count("ok") - res.count("skip"), before / 1e6, after / 1e6))
    for r in res:
        if r not in ("ok", "skip"):
            print(r)


if __name__ == "__main__":
    main()
