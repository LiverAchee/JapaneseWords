# 词库与素材流水线

所有命令都在 `F:\PROJECTS\JapaneseWords` 下运行，Python 用 `D:\Python\python.exe`（已装 pillow、zhconv、edge-tts）。
这里的脚本只写 `data\` 和 `android\app\src\main\assets\`（`words.json`、`img\`、`audio\`），不碰 APP 代码。

## 目录

| 路径 | 内容 |
|---|---|
| `data\raw\n5.csv` | Tanos N5 词表（718 词）。**行顺序决定词条 id（`n5_0001`…），不能改行序**；追加只能加在末尾 |
| `data\raw\kana_recordings\` | 清音 / 浊音 / 半浊音 71 个假名的录音原件（`001_あア_a.mp3`…；Lingua Libre，录音者 Fukujinzuke，CC BY 4.0，见根目录 `CREDITS.md`），原样拷到 `assets\audio\kana_<key>.mp3`（ぢ→`kana_di`、づ→`kana_du`）；拗音仍由 `gen_kana_audio.py` 合成 |
| `data\raw\tatoeba\` | Tatoeba 日/中句、链接、jpn_indices（`tools\download_tatoeba.py` 下载） |
| `data\content\n5_a.txt`…`n5_f.txt` | 人工内容，每行 `序号\|中文释义\|词性\|常用度1-5\|互斥组\|记忆点\|插画提示词`。`load_content()` 用 `n5_*.txt` 通配，此目录别放其他 `n5_` 开头的文件 |
| `data\content\examples_n5.txt` | 人工例句 `序号\|日文\|中文`，优先级高于 Tatoeba；同一序号写多行时以后面的为准（文件末尾追加即可覆盖） |
| `data\words_n5.json` | 构建中间产物（与 `assets\words.json` 相同） |
| `data\img_raw\` | 插画原图（含水印），`gen_images.py` 再裁剪压缩到 `assets\img\` |
| `data\gen_images.log` / `gen_audio.log` | 生成日志，失败序号以 `FAIL` 标出 |

## 脚本用法

```bash
python tools\build_words.py            # 合并词表+内容+例句 → data\words_n5.json 和 assets\words.json
python tools\build_words.py --missing  # 同上，并列出没有例句的词（正常应为空）
python tools\gen_audio.py              # 合成缺失的语音：assets\audio\<id>.mp3（读假名）和 <id>_s.mp3（例句）
python tools\gen_audio.py --redo 37 52 # 重新合成指定序号（改了读音/例句后）
python tools\gen_images.py             # 生成缺失的插画（Pollinations 免费通道，每 IP 45 秒 1 张，718 张约 9 小时）
python tools\gen_images.py --redo 37 52  # 换随机种子重画指定序号（先改好 content 里的提示词）
python tools\gen_images.py --convert   # 不联网，只把 img_raw 里的原图重新裁剪压缩成 384px WebP
python tools\download_tatoeba.py       # 重新下载 Tatoeba 数据
python tools\make_fx.py                # 生成答对/答错提示音到 android res\raw
```

注意：
- 出图脚本同一时间只能跑一个（同 IP 限速）；可中断重跑，已有图自动跳过。
- 想不限速、更好画质：到 https://enter.pollinations.ai 申请免费 key，设环境变量 `POLLINATIONS_KEY` 再运行。
- 例句 `ex_src`：`manual` = `examples_n5.txt` 人工写的；`tatoeba:<id>` = 自动挑的。

## 常规改动流程

1. **改某词的释义/记忆点/提示词**：直接改 `data\content\n5_*.txt` 对应行（序号、7 列不变）→ `build_words.py`。提示词改了要 `gen_images.py --redo <序号>`。
2. **改某词例句**：在 `examples_n5.txt` 末尾追加 `序号|日文|中文`（N5 语法词汇、20 字内、必须含该词，译文自然简体）→ `build_words.py` → `gen_audio.py --redo <序号>`。
3. **重配音**：`gen_audio.py --redo <序号…>`（单词和例句两个 mp3 一起重做）。

## 新增一个级别（如 N4）

1. 取得 N4 词表 CSV，格式同 `n5.csv`（列：词形、读音、英文释义），放到 `data\raw\n4.csv`，**行序即 id 序，定稿后不要再动**。
2. 把 `tools\common.py` 的 `LEVEL = "n5"` 改成 `"n4"`（目前各脚本靠这个常量区分级别；输出文件名 `words_n5.json`、`assets\words.json` 也要按级别处理——若 APP 要同时包含 N5+N4，需让 `build_words.py` 合并多个级别的输出，并改 `wid()` 前缀，这部分需与 APP 端的加载方式一起确认）。
3. 参照 N5 写 `data\content\n4_a.txt`…（7 列格式），以及 `data\content\examples_n4.txt`。
4. 依次：`build_words.py --missing` → 补例句 → 后台跑 `gen_images.py` → `gen_audio.py`。
5. 校对：对 `ex_src` 为 `tatoeba:` 的例句通读一遍（词义是否对得上、是否过难、有无死亡/暴力内容、译文是否通顺），不合适的用 `examples_n4.txt` 覆盖。

## 处理 APP 里导出的勘误

APP 的“勘误”按钮会把标记写进用户库，同步到 `F:\Affairs_Sync\SyncProjects\JapaneseWords\user.db`（Syncthing）的 `errata` 表：

```
errata(id, word_id, expr, ts, type, note, status)   -- status: open / withdrawn
```

处理：

```bash
python -c "import sqlite3;c=sqlite3.connect(r'F:\Affairs_Sync\SyncProjects\JapaneseWords\user.db');[print(r) for r in c.execute(\"select word_id,expr,type,note from errata where status='open' order by word_id\")]"
```

1. 逐条看 `type`（释义/例句/读音/插画等）和 `note`，按上面的“常规改动流程”改对应文件。
2. 例句或读音有改 → `gen_audio.py --redo`；插画有问题 → 改提示词后 `gen_images.py --redo`。
3. 改完 `build_words.py` 重建 `assets\words.json`，再让 APP 端重新打包 APK。
4. 不要直接改用户的 `user.db`（它在用户手机/电脑间同步）；处理完的勘误可由用户在 APP 里撤回，或自己确认后再标记。
