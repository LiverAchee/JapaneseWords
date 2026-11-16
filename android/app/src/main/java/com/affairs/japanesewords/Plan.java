package com.affairs.japanesewords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 每日计划：今天该复习哪些词、该学哪些新词，以及进度统计。 */
public final class Plan {
    private Plan() {}

    public static int dailyNew() {
        return Math.max(1, App.db.getInt("daily_new", 20));
    }

    /** 到期该复习的词（已学、没斩、素材齐全）。 */
    public static List<Word> due() {
        long t = Db.today();
        ArrayList<Word> out = new ArrayList<>();
        for (Word w : App.content.ready()) {
            Db.P p = App.db.p(w.id);
            // 到期的，以及看过首学卡但还没测过的
            if (p != null && !p.banned && (p.learned() ? p.due <= t : p.seen())) out.add(w);
        }
        Collections.shuffle(out);
        return out;
    }

    /** 今天还该学几个新词。 */
    public static int newQuota() {
        if (kanaOnly()) return 0;
        return Math.min(remaining(), Math.max(0, dailyNew() - App.db.newToday()));
    }

    /** 还没学、没斩的词数（目标词表 + 自添词一起算）。 */
    public static int remaining() {
        int n = 0;
        for (Word w : App.content.ready()) {
            Db.P p = App.db.p(w.id);
            if (p == null || (!p.seen() && !p.banned)) n++;
        }
        return n;
    }

    /**
     * 抽 n 个新词：按权重随机，不按固定顺序。越基础常用（freq 高）的词权重越大，先学到的概率越高。
     */
    public static List<Word> pickNew(int n) {
        ArrayList<Word> pool = new ArrayList<>();
        for (Word w : App.content.ready()) {
            Db.P p = App.db.p(w.id);
            if (p == null || (!p.seen() && !p.banned)) pool.add(w);
        }
        ArrayList<Word> out = new ArrayList<>();
        Random rnd = new Random();
        while (out.size() < n && !pool.isEmpty()) {
            double sum = 0;
            for (Word w : pool) sum += weight(w);
            double x = rnd.nextDouble() * sum;
            int pick = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                x -= weight(pool.get(i));
                if (x <= 0) {
                    pick = i;
                    break;
                }
            }
            out.add(pool.remove(pick));
        }
        return out;
    }

    private static double weight(Word w) {
        int f = Math.max(1, Math.min(5, w.freq));
        return f * f * f;
    }

    // ---------- 五十音 ----------

    /** 仅学假名：不出新单词（已学单词的到期复习照常），假名照常。五十音没开时这个开关不起作用。 */
    public static boolean kanaOnly() {
        return kanaOn() && "1".equals(App.db.get("kana_only", "0"));
    }

    /**
     * 全部学完后“一直复习”用：挑出此刻最容易忘的 n 项（按记忆模型算出的可提取概率从低到高）。
     * 每项是 {Word 或 Kana.Entry, 假名的 'h' / 'k'（单词为 0）}。kanaOnly 时只挑假名。
     */
    public static List<Object[]> weakest(int n, boolean kanaOnly) {
        long t = Db.today();
        ArrayList<Object[]> all = new ArrayList<>();
        ArrayList<Double> score = new ArrayList<>();
        if (!kanaOnly) {
            for (Word w : App.content.ready()) {
                Db.P p = App.db.p(w.id);
                if (p == null || !p.learned() || p.banned) continue;
                all.add(new Object[]{w, (char) 0});
                score.add(Fsrs.retrievability(Math.max(0, t - p.last), p.s));
            }
        }
        if (kanaOn()) {
            for (Kana.Entry e : Kana.all()) {
                for (char track : new char[]{'h', 'k'}) {
                    Db.P p = App.db.p(e.id(track));
                    if (p == null || !p.learned() || p.banned) continue;
                    all.add(new Object[]{e, track});
                    score.add(Fsrs.retrievability(Math.max(0, t - p.last), p.s));
                }
            }
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) order.add(i);
        Collections.shuffle(order); // 概率相同的（比如都是今天刚复习过）随机挑
        order.sort((a, b) -> Double.compare(score.get(a), score.get(b)));
        ArrayList<Object[]> out = new ArrayList<>();
        for (int i = 0; i < order.size() && out.size() < n; i++) out.add(all.get(order.get(i)));
        return out;
    }

    /**
     * “仅复习”（纯练习）可以练的全部内容：已学、没斩的假名（平、片各一项），不是“仅学假名”时再加上已学的单词。
     * 每项是 {Word 或 Kana.Entry, 假名的 'h' / 'k'（单词为 0）, 可提取概率（越低越容易忘）}。
     */
    public static List<Object[]> practicePool() {
        long t = Db.today();
        ArrayList<Object[]> out = new ArrayList<>();
        if (kanaOn()) {
            for (Kana.Entry e : Kana.all()) {
                Db.P h = App.db.p(e.id('h'));
                if (h == null || !h.seen() || h.banned) continue;
                for (char track : new char[]{'h', 'k'}) {
                    Db.P p = App.db.p(e.id(track));
                    double r = p != null && p.learned() ? Fsrs.retrievability(Math.max(0, t - p.last), p.s) : 0;
                    out.add(new Object[]{e, track, r});
                }
            }
        }
        if (!kanaOnly()) {
            for (Word w : App.content.ready()) {
                Db.P p = App.db.p(w.id);
                if (p == null || !p.seen() || p.banned) continue;
                double r = p.learned() ? Fsrs.retrievability(Math.max(0, t - p.last), p.s) : 0;
                out.add(new Object[]{w, (char) 0, r});
            }
        }
        return out;
    }

    public static boolean kanaOn() {
        return "1".equals(App.db.get("kana_on", "1"));
    }

    public static int kanaDaily() {
        return Math.max(1, App.db.getInt("kana_daily", 10));
    }

    /** 还没学的音，按五十音顺序（清音 → 浊音 → 拗音）。 */
    public static List<Kana.Entry> kanaUnlearned() {
        ArrayList<Kana.Entry> out = new ArrayList<>();
        if (!kanaOn()) return out;
        for (Kana.Entry e : Kana.all()) {
            Db.P p = App.db.p(e.id('h'));
            if (p == null || (!p.seen() && !p.banned)) out.add(e);
        }
        return out;
    }

    /** 今天还该学几个新的音。 */
    public static int kanaQuota() {
        if (!kanaOn()) return 0;
        return Math.min(kanaUnlearned().size(), Math.max(0, kanaDaily() - App.db.kanaNewToday()));
    }

    /**
     * 该复习的假名，每项是 {音, 'h' 或 'k'}：平假名、片假名两条进度各自到期。
     * 一个音首学后，哪条进度还没测过也算到期（比如上次学到一半退出了）。
     */
    public static List<Object[]> kanaDue() {
        ArrayList<Object[]> out = new ArrayList<>();
        if (!kanaOn()) return out;
        long t = Db.today();
        for (Kana.Entry e : Kana.all()) {
            Db.P h = App.db.p(e.id('h'));
            if (h == null || !h.seen() || h.banned) continue;
            for (char track : new char[]{'h', 'k'}) {
                Db.P p = App.db.p(e.id(track));
                if (p == null || !p.learned() || p.due <= t) out.add(new Object[]{e, track});
            }
        }
        Collections.shuffle(out);
        return out;
    }

    /** [总数, 已学的音（含已斩）]。 */
    public static int[] kanaStats() {
        int learned = 0;
        List<Kana.Entry> all = Kana.all();
        for (Kana.Entry e : all) {
            Db.P p = App.db.p(e.id('h'));
            if (p != null && (p.seen() || p.banned)) learned++;
        }
        return new int[]{all.size(), learned};
    }

    /** [可背总词数, 已学（含已斩）, 已斩]。 */
    public static int[] stats() {
        int total = 0, learned = 0, banned = 0;
        for (Word w : App.content.ready()) {
            total++;
            Db.P p = App.db.p(w.id);
            if (p == null) continue;
            if (p.banned) {
                banned++;
                learned++;
            } else if (p.seen()) {
                learned++;
            }
        }
        return new int[]{total, learned, banned};
    }

    /**
     * 估算词汇量：斩掉的词算已掌握；其余已学词 × 最近 14 天的一次选对率。
     */
    public static int estimatedVocab() {
        int[] s = stats();
        double acc = App.db.accuracy(14);
        if (acc < 0) acc = 0;
        return s[2] + (int) Math.round((s[1] - s[2]) * acc);
    }
}
