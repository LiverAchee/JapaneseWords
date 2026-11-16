"""下载 Tatoeba 例句数据（CC-BY 2.0 FR）到 data/raw/tatoeba。
用法: python tools/download_tatoeba.py
"""
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "data", "raw", "tatoeba")
BASE = "https://downloads.tatoeba.org/exports/"
FILES = [
    "per_language/jpn/jpn_sentences.tsv.bz2",
    "per_language/cmn/cmn_sentences.tsv.bz2",
    "per_language/jpn/jpn-cmn_links.tsv.bz2",
    "jpn_indices.tar.bz2",
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in FILES:
        dst = os.path.join(OUT, os.path.basename(f))
        if os.path.exists(dst) and os.path.getsize(dst) > 1000:
            print("skip", dst)
            continue
        try:
            req = urllib.request.Request(BASE + f, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as r, open(dst + ".part", "wb") as o:
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    o.write(chunk)
            os.replace(dst + ".part", dst)
            print("ok", f, os.path.getsize(dst))
        except Exception as e:  # noqa: BLE001
            print("FAIL", f, e)
    sys.stdout.flush()


if __name__ == "__main__":
    main()
