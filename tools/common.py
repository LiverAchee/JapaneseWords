"""各脚本共用：读取词表原始 CSV 和人工编写的内容文件。"""
import csv
import glob
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "data")
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets")
LEVEL = "n5"


def wid(idx):
    """词条 id：级别 + 词表内序号，如 n5_0001。序号取自 data/raw/n5.csv 的行顺序，不要改动该文件的行序。"""
    return "%s_%04d" % (LEVEL, idx)


def load_raw():
    """返回 [(idx, expr, reading, english)]。"""
    out = []
    with open(os.path.join(DATA, "raw", LEVEL + ".csv"), encoding="utf-8") as f:
        rows = list(csv.reader(f))[1:]
    for i, r in enumerate(rows):
        out.append((i + 1, r[0].strip(), r[1].strip(), r[2].strip()))
    return out


def load_content():
    """读 data/content/n5_*.txt：序号|中文|词性|常用度|互斥组|记忆点|插画提示词。返回 {idx: dict}。"""
    out = {}
    for path in sorted(glob.glob(os.path.join(DATA, "content", LEVEL + "_*.txt"))):
        with open(path, encoding="utf-8") as f:
            for ln, line in enumerate(f, 1):
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                p = line.split("|")
                if len(p) != 7:
                    raise SystemExit("%s 第 %d 行应有 7 列，实际 %d 列: %s" % (path, ln, len(p), line))
                out[int(p[0])] = dict(zh=p[1], pos=p[2], freq=int(p[3]), grp=p[4], mn=p[5], prompt=p[6])
    return out


def load_examples():
    """读 data/content/examples_n5.txt（人工补写的例句）：序号|日文例句|中文翻译。"""
    out = {}
    path = os.path.join(DATA, "content", "examples_" + LEVEL + ".txt")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip().lstrip("\ufeff")
                if not line or line.startswith("#"):
                    continue
                p = line.split("|")
                out[int(p[0])] = (p[1], p[2])
    return out


def first_alt(s):
    """「足; 脚」→「足」；「けっこん (する)」→「けっこん」；去掉 ～ 和括号说明。朗读和匹配用。"""
    s = s.split(";")[0]
    s = re.sub(r"[（(].*?[）)]", "", s)
    return s.replace("～", "").replace("〜", "").strip()


def display(s):
    """界面显示用：多个写法用 ／ 隔开。"""
    s = s.replace("(〜を) ", "").replace(" (する)", "")
    return re.sub(r"\s*;\s*", "／", s).strip()
