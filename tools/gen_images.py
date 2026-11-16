"""用 Pollinations.ai 免费通道按提示词批量生成记忆插画。
免费通道限速：每个 IP 每 45 秒 1 张，所以 718 张约需 9 小时；脚本按这个节奏排队，可随时中断重跑。
原图存 data/img_raw/<id>.jpg；裁掉右下角水印后压成 384px WebP，放进 APK 的 assets/img/。

用法:
  python tools/gen_images.py            # 生成全部缺失的（边跑边重新读取内容文件，新写的词会自动排上）
  python tools/gen_images.py --redo 37 52   # 重画指定序号（换随机种子）
  python tools/gen_images.py --convert      # 只把已有原图重新裁剪压缩，不联网

想要更好的画质：在 https://enter.pollinations.ai 注册免费 key 后，设置环境变量 POLLINATIONS_KEY 再运行
（会改用 flux 模型、不限速、无水印）。
"""
import io
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image

from common import ASSETS, DATA, load_content, wid

STYLE = ("cute flat vector illustration, simple bold shapes, clean thick outlines, soft pastel colors, "
         "plain light background, centered single subject, no text, no letters, no words")
RAW = os.path.join(DATA, "img_raw")
OUT = os.path.join(ASSETS, "img")
KEY = os.environ.get("POLLINATIONS_KEY", "")
INTERVAL = 2 if KEY else 46


def fetch(prompt, seed):
    url = ("https://image.pollinations.ai/prompt/" + urllib.parse.quote(prompt + ", " + STYLE)
           + "?width=512&height=512&seed=%d" % seed)
    headers = {"User-Agent": "Mozilla/5.0"}
    if KEY:
        url += "&model=flux&nologo=true"
        headers["Authorization"] = "Bearer " + KEY
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
        data = r.read()
    img = Image.open(io.BytesIO(data))
    img.load()
    if img.width < 256:
        raise ValueError("image too small")
    return data


def convert(i):
    """原图 → 裁掉底部水印条（免费通道才有）→ 384px WebP。"""
    img = Image.open(os.path.join(RAW, wid(i) + ".jpg")).convert("RGB")
    if not os.path.exists(os.path.join(RAW, wid(i) + ".nologo")):
        w, h = img.size
        side = int(h * 0.915)
        left = (w - side) // 2
        img = img.crop((left, 0, left + side, side))
    img.resize((384, 384), Image.LANCZOS).save(os.path.join(OUT, wid(i) + ".webp"), "WEBP", quality=80)


def generate(i, prompt, fresh_seed):
    for attempt in range(6):
        try:
            seed = random.randint(1, 10 ** 6) if (fresh_seed or attempt) else 1000 + i
            data = fetch(prompt, seed)
            with open(os.path.join(RAW, wid(i) + ".jpg"), "wb") as f:
                f.write(data)
            marker = os.path.join(RAW, wid(i) + ".nologo")
            if KEY:
                open(marker, "w").close()
            elif os.path.exists(marker):
                os.remove(marker)
            convert(i)
            print("ok", i, flush=True)
            return True
        except urllib.error.HTTPError as e:
            wait = int(e.headers.get("Retry-After") or 45) + 2 if e.code in (402, 429) else 20
            print("wait", i, e.code, wait, flush=True)
            time.sleep(wait)
        except Exception as e:  # noqa: BLE001
            print("retry", i, repr(e)[:100], flush=True)
            time.sleep(15)
    print("FAIL", i, flush=True)
    return False


def main():
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    args = sys.argv[1:]
    if "--convert" in args:
        n = 0
        for i in sorted(load_content()):
            if os.path.exists(os.path.join(RAW, wid(i) + ".jpg")):
                convert(i)
                n += 1
        print("converted", n)
        return
    if "--redo" in args:
        content = load_content()
        for a in args:
            if a.isdigit():
                generate(int(a), content[int(a)]["prompt"], True)
                time.sleep(INTERVAL)
        return
    failed = set()
    while True:
        content = load_content()  # 每张都重新读，新写的内容文件会自动排进来
        todo = [i for i in sorted(content) if i not in failed
                and not os.path.exists(os.path.join(RAW, wid(i) + ".jpg"))]
        if not todo:
            break
        i = todo[0]
        if not generate(i, content[i]["prompt"], False):
            failed.add(i)
        time.sleep(INTERVAL)
    print("all done, failed:", sorted(failed), flush=True)


if __name__ == "__main__":
    main()
