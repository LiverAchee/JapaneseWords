"""合并词表、人工内容、例句，生成 APP 用的 words.json。

例句来源优先级：
  1. data/content/examples_n5.txt 里人工指定的（用于覆盖不合适的自动例句）—— ex_src = "manual"
  2. Tatoeba 例句（Tanaka 语料，JMdict 编辑标注过对应词条；带 ~ 的是人工校对过的），要求有中文翻译
     —— ex_src = "tatoeba:<句子id>"
  3. 都没有则留空，由 --missing 列出来补写

用法:
  python tools/build_words.py            # 生成 data/words_n5.json 和 assets/words.json
  python tools/build_words.py --missing  # 另外列出没有例句的词
"""
import bz2
import json
import os
import re
import sys
import tarfile

import zhconv

from common import ASSETS, DATA, display, first_alt, load_content, load_examples, load_raw, wid

TAT = os.path.join(DATA, "raw", "tatoeba")
KANA = re.compile(r"^[぀-ヿー～〜]+$")
KANJI = re.compile(r"[一-鿿々]")
TOKEN = re.compile(r"^([^\(\[\{~]+)(?:\(([^\)]*)\))?(?:\[\d+\])?(?:\{([^\}]*)\})?(~)?$")

ROMA = {
    "あ": "a", "い": "i", "う": "u", "え": "e", "お": "o",
    "か": "ka", "き": "ki", "く": "ku", "け": "ke", "こ": "ko",
    "さ": "sa", "し": "shi", "す": "su", "せ": "se", "そ": "so",
    "た": "ta", "ち": "chi", "つ": "tsu", "て": "te", "と": "to",
    "な": "na", "に": "ni", "ぬ": "nu", "ね": "ne", "の": "no",
    "は": "ha", "ひ": "hi", "ふ": "fu", "へ": "he", "ほ": "ho",
    "ま": "ma", "み": "mi", "む": "mu", "め": "me", "も": "mo",
    "や": "ya", "ゆ": "yu", "よ": "yo",
    "ら": "ra", "り": "ri", "る": "ru", "れ": "re", "ろ": "ro",
    "わ": "wa", "を": "o", "ん": "n",
    "が": "ga", "ぎ": "gi", "ぐ": "gu", "げ": "ge", "ご": "go",
    "ざ": "za", "じ": "ji", "ず": "zu", "ぜ": "ze", "ぞ": "zo",
    "だ": "da", "ぢ": "ji", "づ": "zu", "で": "de", "ど": "do",
    "ば": "ba", "び": "bi", "ぶ": "bu", "べ": "be", "ぼ": "bo",
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pu", "ぺ": "pe", "ぽ": "po",
    "ぁ": "a", "ぃ": "i", "ぅ": "u", "ぇ": "e", "ぉ": "o",
}
SMALL = {"ゃ": "ya", "ゅ": "yu", "ょ": "yo"}
# 词表写法和 JMdict 词头写法不同的词
ALIAS = {"終る": "終わる", "曲る": "曲がる", "朝御飯": "朝ご飯", "昼御飯": "昼ご飯", "晩御飯": "晩ご飯", "御飯": "ご飯",
         "綺麗": "奇麗", "入口": "入り口", "喫茶店": "喫茶店", "お菓子": "御菓子", "お皿": "皿", "お風呂": "風呂",
         "お弁当": "弁当", "お手洗い": "御手洗い", "おじいさん": "お祖父さん", "おばあさん": "お祖母さん",
         "さ来年": "再来年", "伯父": "伯父さん", "伯母さん": "伯母さん"}


def romaji(kana):
    """假名 → 罗马字（平文式；长音照写，如 がっこう → gakkou）。"""
    s = "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in kana)
    out = []
    i = 0
    sokuon = False
    while i < len(s):
        c = s[i]
        nxt = s[i + 1] if i + 1 < len(s) else ""
        if c == "っ":
            sokuon = True
            i += 1
            continue
        if c == "ー":
            if out and out[-1] and out[-1][-1] in "aiueo":
                out.append(out[-1][-1])
            i += 1
            continue
        if c in ROMA and nxt in SMALL:
            base = ROMA[c]
            y = SMALL[nxt]
            if base in ("shi", "chi", "ji"):
                r = base[:-1] + y[1:]
            else:
                r = base[:-1] + y
            i += 2
        elif c in ROMA:
            r = ROMA[c]
            i += 1
        else:
            out.append(c if c in " ／" else "")
            i += 1
            continue
        if sokuon:
            r = ("t" if r.startswith("ch") else r[0]) + r
            sokuon = False
        out.append(r)
    return "".join(out)


def load_tatoeba():
    jpn = {}
    with bz2.open(os.path.join(TAT, "jpn_sentences.tsv.bz2"), "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3:
                jpn[int(p[0])] = p[2]
    cmn = {}
    with bz2.open(os.path.join(TAT, "cmn_sentences.tsv.bz2"), "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3:
                cmn[int(p[0])] = p[2]
    links = {}
    with bz2.open(os.path.join(TAT, "jpn-cmn_links.tsv.bz2"), "rt", encoding="utf-8") as f:
        for line in f:
            p = line.split("\t")
            if len(p) >= 2:
                a, b = int(p[0]), int(p[1])
                if b in cmn:
                    links.setdefault(a, []).append(b)
    # 词条索引：headword -> [(句子id, 读音, 句中写法, 是否校对过)]
    index = {}
    with tarfile.open(os.path.join(TAT, "jpn_indices.tar.bz2")) as t:
        f = t.extractfile(t.getmembers()[0])
        for raw in f:
            p = raw.decode("utf-8").rstrip("\n").split("\t")
            if len(p) < 3:
                continue
            sid = int(p[0])
            if sid not in jpn or sid not in links:
                continue
            for tok in p[2].split(" "):
                m = TOKEN.match(tok)
                if m:
                    item = (sid, m.group(2) or "", m.group(3) or "", bool(m.group(4)))
                    index.setdefault(m.group(1), []).append(item)
                    # 句中实际写法也建索引：词表写假名（ここ）而词典词头是汉字（此処）时靠它匹配
                    if m.group(3) and m.group(3) != m.group(1):
                        index.setdefault("=" + m.group(3), []).append(item)
    return jpn, cmn, links, index


def pick_example(expr, reading, jpn, cmn, links, index, easy_kanji):
    head = first_alt(expr)
    rd = first_alt(reading)
    cands = []
    keys = {head, ALIAS.get(head, head)}
    if KANA.match(head):
        keys.add(rd)
        keys.add("=" + head)
    for k in keys:
        for sid, r, surf, checked in index.get(k, []):
            # 汉字词：索引里若标了读音，必须和词表读音一致（区分同形异读，如 一日 いちにち / ついたち）
            if not KANA.match(head) and r and r != rd:
                continue
            text = jpn[sid]
            n = len(text)
            if n < 5 or n > 24:
                continue
            hard = sum(1 for c in KANJI.findall(text) if c not in easy_kanji)
            score = (0 if checked else 6) + hard * 3 + abs(n - 12) * 0.4
            cands.append((score, sid))
    if not cands:
        return None
    cands.sort()
    sid = cands[0][1]
    zh = min((cmn[c] for c in links[sid]), key=len)
    return jpn[sid], zhconv.convert(zh, "zh-cn"), "tatoeba:%d" % sid


def main():
    raw = load_raw()
    content = load_content()
    manual = load_examples()
    jpn, cmn, links, index = load_tatoeba()
    easy_kanji = set(KANJI.findall("".join(r[1] for r in raw)))
    words, missing, no_content = [], [], []
    for idx, expr, reading, en in raw:
        c = content.get(idx)
        if not c:
            no_content.append(idx)
            continue
        ex = None
        if idx in manual:
            ex = (manual[idx][0], manual[idx][1], "manual")
        if not ex:
            ex = pick_example(expr, reading, jpn, cmn, links, index, easy_kanji)
        if not ex:
            missing.append((idx, expr, reading, c["zh"]))
            ex = ("", "", "")
        rd = display(reading)
        words.append({
            "id": wid(idx), "level": "N5", "expr": display(expr), "reading": rd,
            "romaji": romaji(first_alt(reading)), "pos": c["pos"], "zh": c["zh"], "en": en,
            "ex_ja": ex[0], "ex_zh": ex[1], "ex_src": ex[2], "mn": c["mn"], "freq": c["freq"], "grp": c["grp"],
        })
    with open(os.path.join(DATA, "words_n5.json"), "w", encoding="utf-8") as f:
        json.dump(words, f, ensure_ascii=False, indent=1)
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "words.json"), "w", encoding="utf-8") as f:
        json.dump(words, f, ensure_ascii=False, separators=(",", ":"))
    src = {}
    for w in words:
        k = w["ex_src"].split(":")[0] or "none"
        src[k] = src.get(k, 0) + 1
    print("words=%d  examples=%s  no_content=%d" % (len(words), src, len(no_content)))
    if "--missing" in sys.argv:
        sys.stdout.reconfigure(encoding="utf-8")
        for m in missing:
            print("%d|%s|%s|%s" % m)


if __name__ == "__main__":
    main()
