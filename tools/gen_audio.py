"""用微软 Edge 的日语神经语音（edge-tts，免费）批量合成读音，放进 APK 的 assets/audio/。
  <id>.mp3    单词读音 —— 朗读的是假名读音，保证读法和词表一致（汉字交给语音引擎可能读成别的读法）
  <id>_s.mp3  例句读音 —— 朗读整句原文
已有的跳过，可随时中断重跑。先运行 build_words.py 生成 data/words_n5.json。

用法:
  python tools/gen_audio.py              # 合成全部缺失的
  python tools/gen_audio.py --redo 37    # 重新合成某个词（改了例句之后）
"""
import asyncio
import json
import os
import sys

import edge_tts

from common import ASSETS, DATA, first_alt, wid

VOICE = "ja-JP-NanamiNeural"
OUT = os.path.join(ASSETS, "audio")


async def synth(sem, text, path, rate):
    async with sem:
        for attempt in range(4):
            try:
                await edge_tts.Communicate(text, VOICE, rate=rate).save(path + ".part")
                if os.path.getsize(path + ".part") < 800:
                    raise ValueError("empty audio")
                os.replace(path + ".part", path)
                return True
            except Exception as e:  # noqa: BLE001
                await asyncio.sleep(2 + attempt * 3)
                err = e
        print("FAIL", os.path.basename(path), text, repr(err)[:80], flush=True)
        return False


async def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(DATA, "words_n5.json"), encoding="utf-8") as f:
        words = json.load(f)
    redo = set()
    if "--redo" in sys.argv:
        redo = {wid(int(a)) for a in sys.argv[1:] if a.isdigit()}
    sem = asyncio.Semaphore(4)
    jobs = []
    for w in words:
        if redo and w["id"] not in redo:
            continue
        p = os.path.join(OUT, w["id"] + ".mp3")
        if redo or not os.path.exists(p):
            # 「いく／ゆく」只读第一个；去掉 ～
            jobs.append(synth(sem, first_alt(w["reading"].replace("／", ";")), p, "-10%"))
        p = os.path.join(OUT, w["id"] + "_s.mp3")
        if w["ex_ja"] and (redo or not os.path.exists(p)):
            jobs.append(synth(sem, w["ex_ja"], p, "-5%"))
    res = await asyncio.gather(*jobs)
    print("synth ok=%d fail=%d" % (sum(res), len(res) - sum(res)), flush=True)


if __name__ == "__main__":
    asyncio.run(main())
