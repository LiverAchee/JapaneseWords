package com.affairs.japanesewords;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 五十音：清音、浊音 / 半浊音、拗音共 104 个音。
 * 既是可点读的五十音图（带掌握情况热力），也是日常学习的内容：
 * 每个音有两条独立的学习进度 —— 平假名（进度 id = kh_<key>）和片假名（kk_<key>）。
 * 读音文件是 assets/audio/kana_<key>.mp3（tools/gen_kana_audio.py 生成），平假名和片假名共用。
 */
public final class Kana {
    private Kana() {}

    /** 一个音。 */
    public static class Entry {
        public final String key;   // 读音文件和进度 id 用，唯一
        public final String hira, kata, roma;
        public final int section;  // 0 清音 1 浊音/半浊音 2 拗音

        Entry(String key, String hira, String roma, int section) {
            this.key = key;
            this.hira = hira;
            this.kata = toKatakana(hira);
            this.roma = roma;
            this.section = section;
        }

        /** form：'h' 平假名、'k' 片假名、'r' 罗马音。 */
        public String text(char form) {
            return form == 'h' ? hira : form == 'k' ? kata : roma;
        }

        /** track：'h' 平假名、'k' 片假名。 */
        public String id(char track) {
            return "k" + track + "_" + key;
        }
    }

    // 每格：假名 罗马字 [读音 key，省略则同罗马字]；"-" 是空格子
    private static final String[] SEION = {
            "あ a|い i|う u|え e|お o",
            "か ka|き ki|く ku|け ke|こ ko",
            "さ sa|し shi|す su|せ se|そ so",
            "た ta|ち chi|つ tsu|て te|と to",
            "な na|に ni|ぬ nu|ね ne|の no",
            "は ha|ひ hi|ふ fu|へ he|ほ ho",
            "ま ma|み mi|む mu|め me|も mo",
            "や ya|-|ゆ yu|-|よ yo",
            "ら ra|り ri|る ru|れ re|ろ ro",
            "わ wa|-|-|-|を o wo",
            "ん n|-|-|-|-",
    };
    private static final String[] DAKUON = {
            "が ga|ぎ gi|ぐ gu|げ ge|ご go",
            "ざ za|じ ji|ず zu|ぜ ze|ぞ zo",
            "だ da|ぢ ji di|づ zu du|で de|ど do",
            "ば ba|び bi|ぶ bu|べ be|ぼ bo",
            "ぱ pa|ぴ pi|ぷ pu|ぺ pe|ぽ po",
    };
    private static final String[] YOON = {
            "きゃ kya|きゅ kyu|きょ kyo",
            "しゃ sha|しゅ shu|しょ sho",
            "ちゃ cha|ちゅ chu|ちょ cho",
            "にゃ nya|にゅ nyu|にょ nyo",
            "ひゃ hya|ひゅ hyu|ひょ hyo",
            "みゃ mya|みゅ myu|みょ myo",
            "りゃ rya|りゅ ryu|りょ ryo",
            "ぎゃ gya|ぎゅ gyu|ぎょ gyo",
            "じゃ ja|じゅ ju|じょ jo",
            "びゃ bya|びゅ byu|びょ byo",
            "ぴゃ pya|ぴゅ pyu|ぴょ pyo",
    };
    private static final String[][] SECTIONS = {SEION, DAKUON, YOON};
    private static final String[] TITLES = {"清音", "浊音 · 半浊音", "拗音"};
    private static List<Entry> all;
    private static final int BANNED = 0xFF0C447C; // 已斩的格子颜色

    private static String toKatakana(String hira) {
        StringBuilder sb = new StringBuilder();
        for (char c : hira.toCharArray()) sb.append(c >= 'ぁ' && c <= 'ゖ' ? (char) (c + 0x60) : c);
        return sb.toString();
    }

    private static Entry parse(String cell, int section) {
        String[] f = cell.split(" ");
        return new Entry(f.length > 2 ? f[2] : f[1], f[0], f[1], section);
    }

    /** 全部 104 个音，按学习顺序：清音 → 浊音 / 半浊音 → 拗音。 */
    public static synchronized List<Entry> all() {
        if (all == null) {
            all = new ArrayList<>();
            for (int s = 0; s < SECTIONS.length; s++) {
                for (String row : SECTIONS[s]) {
                    for (String cell : row.split("\\|")) if (!cell.equals("-")) all.add(parse(cell, s));
                }
            }
        }
        return all;
    }

    /**
     * 热力颜色：还没学 = 灰；常错 = 红；不稳 = 琥珀；较稳 = 浅绿；记牢（或已斩）= 深绿。
     * 依据是这条进度的错误比例和记忆稳定度（天）。
     */
    public static int heat(Entry e, char track) {
        Db.P p = App.db.p(e.id(track));
        if (p == null) return Ui.CARD;
        if (p.banned) return BANNED; // 已斩单独一种颜色，不和“记牢”混在一起
        if (!p.seen()) return Ui.CARD;
        if (!p.learned()) return 0xFF854F0B; // 刚看过首学卡、还没测：先算“不稳”
        double wrong = p.lapses / (double) p.reps;
        if (wrong >= 0.5) return 0xFFA32D2D;
        if (wrong >= 0.25 || p.s < 3) return 0xFF854F0B;
        if (p.s < 10) return 0xFF3B6D11;
        return Ui.GREEN_BG;
    }

    /** 一个音在热力图里的严重程度：数字越大越该注意。 */
    private static int rank(int color) {
        if (color == BANNED) return 9;
        if (color == 0xFFA32D2D) return 4;
        if (color == 0xFF854F0B) return 3;
        if (color == 0xFF3B6D11) return 2;
        if (color == Ui.GREEN_BG) return 1;
        return 0;
    }

    /** 同一个音只用一个颜色：取平假名、片假名两条进度里掌握较差的那个。 */
    public static int heat(Entry e) {
        int h = heat(e, 'h'), k = heat(e, 'k');
        return rank(h) >= rank(k) ? h : k;
    }

    /**
     * 五十音图页面：每格同时显示平假名、片假名和罗马音，整格一个热力颜色。
     * 点格子朗读，长按斩掉 / 恢复；refresh 在数据变化后回调（由调用方重建页面）。
     */
    public static View build(Activity a, Runnable refresh) {
        ScrollView sv = new ScrollView(a);
        LinearLayout c = Ui.col(a);
        int p = Ui.dp(a, 14);
        c.setPadding(p, p, p, p);
        sv.addView(c);
        c.addView(Ui.bold(a, "五十音图", 22, Ui.TXT));

        // 热力图例
        LinearLayout legend = Ui.row(a);
        int[] colors = {Ui.GREEN_BG, 0xFF3B6D11, 0xFF854F0B, 0xFFA32D2D, Ui.CARD, BANNED};
        String[] names = {"记牢", "较稳", "不稳", "常错", "没学", "已斩"};
        for (int i = 0; i < colors.length; i++) {
            View dot = new View(a);
            dot.setBackground(Ui.outline(a, colors[i], Ui.LINE2, 0.5f, 3));
            legend.addView(dot, Ui.lpm(a, Ui.dp(a, 12), Ui.dp(a, 12), 0, i == 0 ? 0 : 8, 0, 3, 0));
            legend.addView(Ui.tv(a, names[i], 11, Ui.SUB));
        }
        c.addView(legend, Ui.lpm(a, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        c.addView(Ui.tv(a, "每格：平假名 · 片假名 / 罗马音。颜色取平、片假名里掌握较差的那个。点一个音朗读，长按可以斩掉或恢复。",
                12, 0xFF888780), Ui.lpm(a, Ui.MATCH, Ui.WRAP, 0, 0, 4, 0, 2));
        for (int s = 0; s < SECTIONS.length; s++) {
            c.addView(Ui.bold(a, TITLES[s], 14, Ui.SUB), Ui.lpm(a, Ui.MATCH, Ui.WRAP, 0, 0, 12, 0, 4));
            int cols = s == 2 ? 3 : 5;
            float kanaSp = s == 2 ? 19 : 20;
            for (String r : SECTIONS[s]) {
                LinearLayout line = Ui.row(a);
                String[] cells = r.split("\\|");
                for (int i = 0; i < cols; i++) {
                    String cell = i < cells.length ? cells[i] : "-";
                    View v;
                    if (cell.equals("-")) {
                        v = new View(a);
                    } else {
                        Entry e = parse(cell, s);
                        int color = heat(e);
                        boolean banned = App.db.banned(e.id('h'));
                        LinearLayout box = Ui.col(a);
                        box.setGravity(Gravity.CENTER);
                        box.setBackground(Ui.round(a, color, 8));
                        // 上面一行：平假名 片假名；下面一行：罗马音
                        TextView kana = center(Ui.tv(a, e.hira + " " + e.kata, kanaSp, Ui.TXT));
                        kana.setMaxLines(1);
                        box.addView(kana);
                        box.addView(center(Ui.tv(a, banned ? e.roma + " · 已斩" : e.roma, 12,
                                color == Ui.CARD ? Ui.SUB : Ui.TXT)));
                        box.setOnClickListener(x -> {
                            App.sound.play("kana_" + e.key);
                            // 点过的格子闪一下，确认点到了哪个
                            box.setBackground(Ui.round(a, Ui.BLUE_BG, 8));
                            box.postDelayed(() -> box.setBackground(Ui.round(a, color, 8)), 350);
                        });
                        box.setOnLongClickListener(x -> {
                            App.db.ban(e.id('h'), !banned);
                            App.db.ban(e.id('k'), !banned);
                            if (!banned) App.sound.fxBan();
                            Ui.toast(a, (banned ? "已恢复 " : "已斩 ") + e.hira + "／" + e.kata);
                            refresh.run();
                            return true;
                        });
                        v = box;
                    }
                    line.addView(v, Ui.lpm(a, 0, Ui.dp(a, 62), 1, 2, 3, 2, 3));
                }
                c.addView(line);
            }
        }
        return sv;
    }

    private static TextView center(TextView t) {
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
