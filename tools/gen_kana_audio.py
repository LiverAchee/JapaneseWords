"""合成五十音图的读音：assets/audio/kana_<key>.mp3（APP 的 Kana.java 按 key 播放）。

注意：清音 / 浊音 / 半浊音（71 个）已换成 `data/raw/kana_recordings/` 里的录音（0.6.5 起），本脚本默认只做 33 个拗音，
不会覆盖那 71 个；确实要用合成音覆盖时，在命令行里写明 key。

单个假名交给语音引擎时发声只有约 0.15 秒，容易断裂、带杂音，所以每个音合成几个候选
（片假名 / 平假名 × 两种放慢程度），用 audio_util 客观挑选：发声段中间没有断裂、元音饱满、时长合适；
挑中的再裁掉首尾静音、统一音量。用片假名优先，是为了避免「は」「へ」被当成助词读成 wa / e。

用法:
  python tools/gen_kana_audio.py            # 重做 33 个拗音（覆盖旧文件）
  python tools/gen_kana_audio.py i u o shu  # 只重做指定的 key
"""
import asyncio
import os
import shutil
import sys
import tempfile

import edge_tts

from audio_util import analyze, trim
from common import ASSETS

VOICE = "ja-JP-NanamiNeural"
OUT = os.path.join(ASSETS, "audio")

# key（文件名）→ 片假名。key 与 Kana.java 里的读音 key 一致。
KANA = """
a ア|i イ|u ウ|e エ|o オ|ka カ|ki キ|ku ク|ke ケ|ko コ|sa サ|shi シ|su ス|se セ|so ソ
ta タ|chi チ|tsu ツ|te テ|to ト|na ナ|ni ニ|nu ヌ|ne ネ|no ノ|ha ハ|hi ヒ|fu フ|he ヘ|ho ホ
ma マ|mi ミ|mu ム|me メ|mo モ|ya ヤ|yu ユ|yo ヨ|ra ラ|ri リ|ru ル|re レ|ro ロ|wa ワ|wo ヲ|n ン
ga ガ|gi ギ|gu グ|ge ゲ|go ゴ|za ザ|ji ジ|zu ズ|ze ゼ|zo ゾ|da ダ|di ヂ|du ヅ|de デ|do ド
ba バ|bi ビ|bu ブ|be ベ|bo ボ|pa パ|pi ピ|pu プ|pe ペ|po ポ
kya キャ|kyu キュ|kyo キョ|sha シャ|shu シュ|sho ショ|cha チャ|chu チュ|cho チョ
nya ニャ|nyu ニュ|nyo ニョ|hya ヒャ|hyu ヒュ|hyo ヒョ|mya ミャ|myu ミュ|myo ミョ
rya リャ|ryu リュ|ryo リョ|gya ギャ|gyu ギュ|gyo ギョ|ja ジャ|ju ジュ|jo ジョ
bya ビャ|byu ビュ|byo ビョ|pya ピャ|pyu ピュ|pyo ピョ
"""
# 平假名会被读成助词的音，只用片假名
KATA_ONLY = {"ha", "he", "wo"}
# 语音引擎单独读某个假名总出毛病时，额外试读音完全相同的汉字（「い」单独读会断成两截，「胃」读 い 则正常）
EXTRA = {"i": ["胃", "イ。"]}


# 单独合成怎么都不对的音：改为让引擎按整行朗读（它最熟悉的读法），再把其中第 n 段切出来。
# 值是 (朗读文本, 第几段, 语速)；末尾多放一个「あ」，免得要切的音落在句尾、被读得又短又降调。
FROM_ROW = {
    "u": ("あ、い、う、え、お、あ。", 2, "-35%"),
    "o": ("あ、い、う、え、お、あ。", 4, "-35%"),
}


def segments(path):
    """整行朗读的音频里，各个发声段的 (起, 止)。"""
    import re
    import subprocess

    from audio_util import FF
    out = subprocess.run([FF, "-hide_banner", "-nostdin", "-i", path, "-af", "silencedetect=noise=-35dB:d=0.08",
                          "-f", "null", "-"], capture_output=True, text=True, errors="replace").stderr
    marks = [(k, float(v)) for k, v in re.findall(r"silence_(start|end): (-?[\d.]+)", out)]
    segs, begin = [], 0.0
    for kind, t in marks:
        if kind == "start":
            if t - begin > 0.05:
                segs.append((begin, t))
        else:
            begin = t
    return segs


async def from_row(sem, tmp, key):
    text, idx, rate = FROM_ROW[key]
    p = os.path.join(tmp, "row_%s.mp3" % key)
    if not await synth(sem, text, rate, p):
        return key, None
    segs = segments(p)
    if len(segs) != text.count("、") + 1:
        return key, None  # 没切成预期的段数，宁可失败也不要切错音
    a, b = segs[idx]
    piece = os.path.join(tmp, "piece_%s.mp3" % key)
    import subprocess

    from audio_util import FF
    subprocess.run([FF, "-hide_banner", "-nostdin", "-y", "-ss", "%.3f" % max(0, a - 0.06), "-to", "%.3f" % (b + 0.06),
                    "-i", p, "-codec:a", "libmp3lame", "-b:a", "96k", piece], capture_output=True)
    info = analyze(piece)
    ok = trim(piece, os.path.join(OUT, "kana_%s.mp3" % key), info)
    return key, dict(ok=ok, score=0 if info["gaps"] == 0 else 100, text=text, rate=rate,
                     voiced=info["end"] - info["start"], gaps=info["gaps"], low=info["low"] - info["mean"])


def to_hira(kata):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in kata)


async def synth(sem, text, rate, path):
    async with sem:
        for attempt in range(4):
            try:
                await edge_tts.Communicate(text, VOICE, rate=rate).save(path)
                if os.path.getsize(path) > 800:
                    return True
            except Exception:  # noqa: BLE001
                await asyncio.sleep(2 + attempt * 3)
        return False


def score(info):
    """分数越小越好。断裂、元音不饱满（低频能量低，说明元音被吞掉）、太短太长都扣分。"""
    voiced = info["end"] - info["start"]
    s = info["gaps"] * 100
    if info["low"] < info["mean"] - 9:
        s += 40
    if voiced < 0.22:
        s += 30
    if voiced > 0.75:
        s += 15
    return s, voiced


async def one(sem, tmp, key, kata):
    if key in FROM_ROW:
        return await from_row(sem, tmp, key)
    cands = []
    texts = ([kata] if key in KATA_ONLY else [kata, to_hira(kata)]) + EXTRA.get(key, [])
    for ti, text in enumerate(texts):
        for rate in ("-30%", "-50%"):
            p = os.path.join(tmp, "%s_%d_%s.mp3" % (key, ti, rate.strip("-%")))
            if await synth(sem, text, rate, p):
                info = analyze(p)
                sc, voiced = score(info)
                cands.append((sc, len(cands), p, text, rate, info, voiced))
    if not cands:
        return key, None
    cands.sort(key=lambda c: (c[0], c[1]))
    sc, _, p, text, rate, info, voiced = cands[0]
    ok = trim(p, os.path.join(OUT, "kana_%s.mp3" % key), info)
    return key, dict(ok=ok, score=sc, text=text, rate=rate, voiced=voiced, gaps=info["gaps"],
                     low=info["low"] - info["mean"])


async def main():
    os.makedirs(OUT, exist_ok=True)
    items = [c.split(" ") for line in KANA.strip().splitlines() for c in line.split("|")]
    only = set(sys.argv[1:])
    if only:
        items = [it for it in items if it[0] in only]
    else:
        items = [it for it in items if len(it[1]) == 2]  # 只做拗音（片假名两个字），其余是录音
    tmp = tempfile.mkdtemp(prefix="kana_")
    try:
        sem = asyncio.Semaphore(4)
        res = await asyncio.gather(*(one(sem, tmp, k, t) for k, t in items))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    sys.stdout.reconfigure(encoding="utf-8")
    bad = []
    for key, r in res:
        if r is None or not r["ok"]:
            bad.append(key)
            print("FAIL", key)
        elif r["score"] > 0:
            bad.append(key)
            print("疑似有问题 %-4s 选用 %s %s 发声 %.2fs 断裂 %d 低频差 %.0fdB"
                  % (key, r["text"], r["rate"], r["voiced"], r["gaps"], r["low"]))
    good = [r for _, r in res if r and r["ok"]]
    print("done %d/%d，平均发声 %.2fs，需要人工听一下的：%s"
          % (len(good), len(res), sum(r["voiced"] for r in good) / max(1, len(good)), " ".join(bad) or "无"))


if __name__ == "__main__":
    asyncio.run(main())
