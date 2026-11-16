# 绘词日语 Etango

看图背日语单词的安卓 APP（英文名 Etango，取自日语「絵単語」= 图画单词；0.6.8 前叫“日语斩”。仓库、包名和 APK 文件名仍用 JapaneseWords，以免下载链接失效、旧版无法覆盖安装）。玩法类似百词斩，核心是“看词选图”。目前调通 JLPT N5（Tanos 词表，718 词）。

## 下载安装

安卓 8.0 及以上，手机浏览器打开下面任一链接，下载后点开安装（首次需允许“安装未知来源应用”）：

- 国内加速：https://ghfast.top/https://github.com/LiverAchee/JapaneseWords/releases/latest/download/JapaneseWords.apk
- 备用加速：https://gh-proxy.com/https://github.com/LiverAchee/JapaneseWords/releases/latest/download/JapaneseWords.apk
- GitHub 原地址：https://github.com/LiverAchee/JapaneseWords/releases/latest

加速链接是第三方公益镜像，打不开就换另一个。在微信里点开链接下载不了时，点右上角“在浏览器打开”。

## 目录

| 路径 | 内容 |
|---|---|
| `android/` | 安卓工程（纯 Java，无第三方依赖，minSdk 26） |
| `android/app/src/main/assets/` | 打进 APK 的词库：`words.json`、`img/<id>.webp`、`audio/<id>.mp3`（单词）和 `<id>_s.mp3`（例句） |
| `data/raw/` | 原始词表 `n5.csv`（行顺序决定词条 id，勿改）、Tatoeba 例句数据 |
| `data/content/` | 人工内容：`n5_*.txt`（释义、词性、常用度、互斥组、记忆点、插画提示词）、`examples_n5.txt`（人工例句） |
| `tools/` | 词库和素材生成脚本，用法见 `data/README.md` |
| `build-apk.bat` | 打包，APK 按版本号存到 `dist/`（不往手机同步文件夹放 APK） |

## 打包

双击 `build-apk.bat`（或 `build-apk.bat nopause`）。改版时先改 `android/app/build.gradle` 里的 `versionCode` / `versionName`。

发布给别人用：`gh release create v<版本> dist\JapaneseWords-<版本>.apk <同一文件复制成 JapaneseWords.apk>`。每个版本都要带一个固定名字的 `JapaneseWords.apk`，上面“下载安装”里的 `releases/latest/download/JapaneseWords.apk` 链接才能一直指向最新版。

## 代码结构（`android/app/src/main/java/com/affairs/japanesewords/`）

| 文件 | 作用 |
|---|---|
| `MainActivity` | 竖屏主界面：学习（今日计划、两个进度条、打卡日历）/ 词表（筛选、搜索、勘误、添加词）/ 设置（每日新词⇄完成天数滚轮、开关、数据库同步） |
| `QuizActivity` | 横屏看词选图：新词首学卡 → 出题 → 揭示记忆点；斩 / 恢复；选错的词本轮再测 |
| `DetailActivity` | 竖屏词条详情：朗读、勘误、斩 / 恢复、从相册换图 |
| `Plan` | 每日计划：到期复习、按权重随机抽新词、进度统计、估算词汇量 |
| `Fsrs` | FSRS v4 记忆算法；每个词的目标记忆率随常用度和错误次数变化 |
| `Db` | 用户数据库 `user.db`（进度、答题记录、打卡、设置、勘误、自添词、换的图片）及同步文件夹导出 / 导入 |
| `Content` | 词库加载：APK 内置 + 同步文件夹补充包 + 自添词 |
| `Sound` | 读音和提示音（全部走媒体通道）及静音规则 |
| `Ui` | 界面小工具、词条信息块、勘误对话框 |

## 关键规则

- **出题**：4 张图，3 张干扰图每次从整个图库重新随机抽；同写法、同释义、同“互斥组”的词不互相当干扰项。
- **复习**：一次选对记 Good，选错过记 Again，按 FSRS 排下次复习。越基础常用、错得越多的词目标记忆率越高（0.875–0.96），间隔更短、出现更频繁。新词按常用度加权随机抽取。
- **缺素材的词不背**：释义、插画、单词读音三者齐全才参与背诵；自添词先显示“待补素材”。
- **静音**：媒体音量为 0 不出声；戴耳机时只看媒体音量；没戴耳机时要打开“允许外放”才出声，外放默认不管手机静音 / 振动模式，打开“手机静音 / 振动时外放也静音”（`speaker_ringer`）后才跟随。
- **音效**：选对 / 选错提示音，“斩”有挥刀风声 + 金属余响的刀光音效（`fx_ban.wav`，`tools/make_fx.py` 合成），都受“选对 / 选错提示音”开关控制。
- **新词首学卡朗读**：词 → 例句 → 词。
- **打卡**：当天到期复习和新词额度都完成记为“完成”（日历实心），只学了一部分为空心框。
- **估算词汇量**：已斩词数 + 其余已学词数 × 最近 14 天复习的一次选对率。

- **五十音**（`Kana`）：104 个音（清音 46、浊音 / 半浊音 25、拗音 33），按此顺序学，默认每天 10 个新音，和单词分块交替出现在同一轮学习里，不占每日新词名额。首学卡只有平假名、片假名、罗马音和读音；检查是四选一，题型随机（罗马音 → 假名、假名 → 罗马音、平假名 ⇄ 片假名）。每个音有两条独立进度：`kh_<key>`（平假名）和 `kk_<key>`（片假名），和单词共用 `progress` 表和 FSRS。五十音图按当前显示的那种假名的进度上色（记牢 / 较稳 / 不稳 / 常错 / 还没学）。读音文件 `audio/kana_<key>.mp3`：清音 / 浊音 / 半浊音 71 个用 `data/raw/kana_recordings/` 里的录音（ぢ→`kana_di`、づ→`kana_du`），拗音 33 个由 `tools/gen_kana_audio.py` 合成。假名题出题时先念读音（选之前）；只有“选罗马音”的题出题时不念、答对前不给“读音”按钮，选对后停在答案页念读音，点“下一个”继续。0.6.5 升级时清空过一次全部假名进度（数据库 v3，备份在 `data/backup/`）。
- **屏幕方向**：只有学习页可以横屏。系统自动旋转开着时跟着手机转；关着时用左上角按钮切换（设置项 `quiz_orient`，默认横屏）。横屏是左信息右选项，竖屏是上信息下选项。

## 手机和电脑之间的数据

同步文件夹：手机 `内部存储/1/SyncProjects/JapaneseWords/` ⇄ 电脑 `F:\Affairs_Sync\SyncProjects\JapaneseWords\`（Syncthing）。APP 需要“所有文件访问”权限。

| 文件 | 方向 | 说明 |
|---|---|---|
| `user.db` | 手机 → 电脑 | 全部用户数据。APP 内部存工作副本，每次学习后整文件复制到这里；重装后可在设置里“从同步文件夹导入” |
| `content/words.json` | 电脑 → 手机 | 补充包：和内置 `words.json` 同格式，同 id 覆盖内置词条（用于勘误更正、补全自添词） |
| `content/img/<id>.webp`、`content/audio/<id>.mp3`、`<id>_s.mp3` | 电脑 → 手机 | 补充素材，优先于内置素材 |

- **勘误**：在 `user.db` 的 `errata` 表（`word_id, expr, type, note, status`）。
- **自添词**：在 `custom_words` 表，id 形如 `c_<时间戳>`。电脑端在补充包里写入同 id 的词条并放好插画和读音后，该词自动加入背诵。
- **手机上换的图**：在 `user_images` 表（JPEG），优先级最高。
