package com.affairs.japanesewords;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 主界面（竖屏）：学习（今日计划 + 打卡日历）/ 词表 / 设置 三个页签。 */
public class MainActivity extends Activity {
    private FrameLayout body;
    private LinearLayout nav;
    private int tab;
    private int monthOffset;        // 日历翻月
    private String filter = "全部"; // 词表筛选
    private String search = "";
    private boolean hadSync;
    private boolean katakana;       // 五十音图当前显示片假名

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        body = new FrameLayout(this);
        root.addView(body, Ui.lpw(Ui.MATCH, 0, 1));
        View ln = new View(this);
        ln.setBackgroundColor(Ui.LINE);
        root.addView(ln, Ui.lp(Ui.MATCH, 1));
        nav = Ui.row(this);
        root.addView(nav, Ui.lp(Ui.MATCH, Ui.dp(this, 52)));
        setContentView(root);
        hadSync = App.db.canSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刚授予文件权限后，重新读同步文件夹里的补充素材
        if (!hadSync && App.db.canSync()) {
            hadSync = true;
            App.content.reload();
        }
        showTab(tab);
    }

    @Override
    protected void onPause() {
        super.onPause();
        new Thread(() -> App.db.exportToSync()).start();
    }

    private void showTab(int t) {
        tab = t;
        nav.removeAllViews();
        String[] names = {"学习", "词表", "五十音", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView n = Ui.tv(this, names[i], 15, i == t ? Ui.TEAL : Ui.SUB);
            n.setGravity(Gravity.CENTER);
            n.setOnClickListener(v -> showTab(idx));
            nav.addView(n, Ui.lpw(0, Ui.MATCH, 1));
        }
        body.removeAllViews();
        View page = t == 0 ? home() : t == 1 ? wordList() : t == 2 ? Kana.build(this, () -> showTab(2)) : settings();
        body.addView(page,
                new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private LinearLayout page(ScrollView sv) {
        LinearLayout c = Ui.col(this);
        int p = Ui.dp(this, 18);
        c.setPadding(p, p, p, p);
        sv.addView(c);
        return c;
    }

    // ================= 学习 =================

    private View home() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = page(sv);
        int due = Plan.due().size();
        int quota = Plan.newQuota();
        int[] st = Plan.stats();
        int[] dc = App.db.dayCounts(Db.today());
        boolean kanaOnly = Plan.kanaOnly();
        int kQuota = Plan.kanaQuota(), kDue = Plan.kanaDue().size();
        int[] ks = Plan.kanaStats();
        boolean nothing = due == 0 && quota == 0 && kDue == 0 && kQuota == 0;
        boolean resume = App.db.hasSession();
        boolean done = nothing && !resume; // 今天的计划已全部完成

        LinearLayout title = Ui.row(this);
        title.addView(Ui.bold(this, "今日计划", 22, Ui.TXT));
        if (kanaOnly) {
            TextView badge = Ui.tv(this, "仅学假名", 12, Ui.AMBER);
            badge.setBackground(Ui.round(this, Ui.AMBER_BG, 10));
            badge.setPadding(Ui.dp(this, 10), Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3));
            title.addView(badge, Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 10, 0, 0, 0));
        }
        title.addView(new View(this), Ui.lpw(0, 1, 1));
        if (done && dc[0] + dc[1] + dc[2] > 0) title.addView(Ui.tv(this, "✓ 已打卡", 14, Ui.TEAL));
        c.addView(title);

        String words = done ? "单词：今天新学 " + dc[0] + " · 复习 " + dc[1]
                : "单词：" + (kanaOnly ? "新词暂停" : "待学新词 " + quota) + " · 待复习 " + due
                + " · 今天已学 " + dc[0] + " 新 / " + dc[1] + " 复习";
        c.addView(Ui.tv(this, words, 13, Ui.SUB), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 4, 0, 0));
        if (Plan.kanaOn()) {
            String kana = done ? "假名：已学 " + ks[1] + "/" + ks[0] + " · 今天答了 " + dc[2] + " 题"
                    : "假名：待学 " + kQuota + " · 待复习 " + kDue + " · 已学 " + ks[1] + "/" + ks[0] + " · 今天 " + dc[2] + " 题";
            c.addView(Ui.tv(this, kana, 13, Ui.SUB));
        }
        c.addView(Ui.gap(this, 1, 14));

        // 仅学假名时，最上面多一条假名进度
        if (kanaOnly) {
            c.addView(barLabel("假名进度", ks[1] + " / " + ks[0]));
            c.addView(Ui.progress(this, ks[1] / (float) ks[0], Ui.AMBER_MID));
            c.addView(Ui.gap(this, 1, 14));
        }
        // 进度条 1：已学占目标词典的比例
        c.addView(barLabel("已学 / 全部词", st[1] + " / " + st[0]));
        c.addView(Ui.progress(this, st[0] == 0 ? 0 : st[1] / (float) st[0], Ui.TEAL_MID));
        // 进度条 2：按最近 14 天错误率估算的词汇量
        int est = Plan.estimatedVocab();
        double acc = App.db.accuracy(14);
        c.addView(barLabel("估算词汇量" + (acc < 0 ? "" : "（近 14 天正确率 " + Math.round(acc * 100) + "%）"),
                "≈ " + est + " 词"), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 14, 0, 0));
        c.addView(Ui.progress(this, st[0] == 0 ? 0 : est / (float) st[0], Ui.PURPLE_MID));

        if (!App.db.canSync()) {
            TextView warn = Ui.tv(this, "还没有“所有文件访问”权限，数据库不会同步到电脑。点这里去授权。", 13, Ui.AMBER);
            warn.setBackground(Ui.round(this, Ui.AMBER_BG, 8));
            int p = Ui.dp(this, 10);
            warn.setPadding(p, p, p, p);
            warn.setOnClickListener(v -> askSync());
            c.addView(warn, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 14, 0, 0));
        }

        c.addView(calendar(), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 18, 0, 0));

        if (App.content.ready().isEmpty() && !Plan.kanaOn()) {
            c.addView(Ui.tv(this, "词库里还没有素材齐全的词。", 14, Ui.AMBER), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 16, 0, 0));
        } else if (!done) {
            // 今天有学到一半的一轮就接着学
            // 两个按钮：学习（新内容 + 复习，有断点就接着学）｜仅复习（只做到期的复习）
            LinearLayout two = Ui.row(this);
            TextView go = Ui.btn(this, resume ? "继续学习" : "开始学习", Ui.TEAL_BG, Ui.TEAL,
                    v -> startActivity(new Intent(this, QuizActivity.class)));
            go.setTextSize(17);
            two.addView(go, Ui.lpw(0, Ui.dp(this, 50), 1));
            // 仅复习 = 纯练习：已学过的内容随时反复练，不记成绩、不影响每天的复习计划
            int dueAll = Plan.practicePool().size();
            TextView rev = Ui.btn(this, "仅复习（" + dueAll + "）", dueAll > 0 ? Ui.BLUE_BG : Ui.CARD,
                    dueAll > 0 ? Ui.BLUE : Ui.LINE2, v -> {
                        if (dueAll > 0) {
                            startActivity(new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_REVIEW_ONLY, true));
                        } else {
                            Ui.toast(this, "还没有学过的内容可以练");
                        }
                    });
            rev.setTextSize(17);
            two.addView(rev, Ui.lpm(this, 0, Ui.dp(this, 50), 1, 10, 0, 0, 0));
            c.addView(two, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 18, 0, 0));
        } else {
            // 计划完成后：还有没学的就可以继续学新的；全部学完了才开放“一直复习”
            int moreWords = kanaOnly ? 0 : Math.min(Plan.remaining(), 10);
            int moreKana = Math.min(Plan.kanaUnlearned().size(), 5);
            c.addView(Ui.tv(this, "今天的计划已完成。" + (moreWords + moreKana > 0 ? "还想继续：" : "全部内容都学过了，可以一直复习："),
                    14, Ui.TEAL), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 16, 0, 0));
            LinearLayout row = Ui.row(this);
            if (moreWords > 0) {
                row.addView(Ui.ghost(this, "再学 " + moreWords + " 个新词", v -> startActivity(
                        new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_MORE, moreWords))),
                        Ui.lpm(this, 0, Ui.WRAP, 1, 0, 0, moreKana > 0 ? 8 : 0, 0));
            }
            if (moreKana > 0) {
                row.addView(Ui.ghost(this, "再学 " + moreKana + " 个假名", v -> startActivity(
                        new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_MORE_KANA, moreKana))),
                        Ui.lpw(0, Ui.WRAP, 1));
            }
            if (moreWords + moreKana == 0) {
                int n = Math.min(30, Plan.weakest(30, kanaOnly).size());
                if (n > 0) {
                    row.addView(Ui.ghost(this, "再复习 " + n + " 项（最容易忘的）", v -> startActivity(
                            new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_REVIEW, n))),
                            Ui.lpw(0, Ui.WRAP, 1));
                }
            }
            c.addView(row, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 10, 0, 0));
            // 计划完成后“仅复习”同样随时可用
            int pool = Plan.practicePool().size();
            if (pool > 0) {
                c.addView(Ui.btn(this, "仅复习（" + pool + "）· 纯练习，不影响复习计划", Ui.BLUE_BG, Ui.BLUE, v -> startActivity(
                        new Intent(this, QuizActivity.class).putExtra(QuizActivity.EXTRA_REVIEW_ONLY, true))),
                        Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 10, 0, 0));
            }
        }
        return sv;
    }

    private View barLabel(String l, String r) {
        LinearLayout row = Ui.row(this);
        row.addView(Ui.tv(this, l, 13, Ui.SUB), Ui.lpw(0, Ui.WRAP, 1));
        row.addView(Ui.tv(this, r, 13, Ui.TXT));
        row.setPadding(0, 0, 0, Ui.dp(this, 5));
        return row;
    }

    /** 打卡日历：实心绿 = 完成当日计划，绿框 = 学过但没完成，白框 = 今天。 */
    private View calendar() {
        LinearLayout box = Ui.col(this);
        Map<Long, Integer> marks = App.db.checkins();
        LocalDate today = LocalDate.ofEpochDay(Db.today()); // 和学习记录一样以凌晨 4 点为界
        YearMonth ym = YearMonth.from(today).plusMonths(monthOffset);

        long d = today.toEpochDay();
        Integer m0 = marks.get(d);
        if (m0 == null || m0 != 2) d--;
        int streak = 0;
        while (true) {
            Integer m = marks.get(d);
            if (m == null || m != 2) break;
            streak++;
            d--;
        }

        LinearLayout head = Ui.row(this);
        head.addView(Ui.ghost(this, "‹", v -> {
            monthOffset--;
            showTab(0);
        }));
        TextView title = Ui.tv(this, ym.getYear() + " 年 " + ym.getMonthValue() + " 月 · 连续打卡 " + streak + " 天", 14, Ui.TXT);
        title.setGravity(Gravity.CENTER);
        head.addView(title, Ui.lpw(0, Ui.WRAP, 1));
        head.addView(Ui.ghost(this, "›", v -> {
            if (monthOffset < 0) monthOffset++;
            showTab(0);
        }));
        box.addView(head);

        LinearLayout wk = Ui.row(this);
        for (String s : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
            TextView t = Ui.tv(this, s, 11, Ui.SUB);
            t.setGravity(Gravity.CENTER);
            wk.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        }
        box.addView(wk, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 2));

        int lead = ym.atDay(1).getDayOfWeek().getValue() - 1;
        int days = ym.lengthOfMonth();
        LinearLayout line = null;
        for (int i = 0; i < lead + days || i % 7 != 0; i++) {
            if (i % 7 == 0) {
                line = Ui.row(this);
                box.addView(line);
            }
            int day = i - lead + 1;
            TextView cell = Ui.tv(this, day >= 1 && day <= days ? String.valueOf(day) : "", 13, Ui.SUB);
            cell.setGravity(Gravity.CENTER);
            if (day >= 1 && day <= days) {
                LocalDate ld = ym.atDay(day);
                Integer m = marks.get(ld.toEpochDay());
                if (m != null && m == 2) {
                    cell.setBackground(Ui.round(this, Ui.TEAL_MID, 6));
                    cell.setTextColor(0xFF04342C);
                } else if (m != null) {
                    cell.setBackground(Ui.outline(this, Ui.BG, Ui.TEAL_MID, 1.5f, 6));
                    cell.setTextColor(Ui.TEAL);
                } else if (ld.equals(today)) {
                    cell.setBackground(Ui.outline(this, Ui.BG, Ui.TXT, 1, 6));
                    cell.setTextColor(Ui.TXT);
                }
                final long ed = ld.toEpochDay();
                cell.setOnClickListener(v -> {
                    int[] n = App.db.dayCounts(ed);
                    Ui.toast(this, ld + "：新学 " + n[0] + " 词，复习 " + n[1] + " 词，假名 " + n[2] + " 题");
                });
            }
            line.addView(cell, Ui.lpm(this, 0, Ui.dp(this, 34), 1, 2, 2, 2, 2));
        }
        return box;
    }

    // ================= 词表 =================

    private View wordList() {
        LinearLayout c = Ui.col(this);
        int p = Ui.dp(this, 14);
        c.setPadding(p, p, p, 0);

        // 筛选：全部 / 各级别 / 自添 / 已斩 / 已标勘误
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        ArrayList<String> names = new ArrayList<>();
        names.add("全部");
        names.addAll(App.content.levels());
        names.add("自添");
        names.add("已斩");
        names.add("已标勘误");
        names.add("待确认");
        for (String n : names) {
            boolean on = n.equals(filter);
            TextView chip = Ui.tv(this, n, 13, on ? Ui.TEAL : Ui.SUB);
            chip.setBackground(Ui.round(this, on ? Ui.TEAL_BG : Ui.CARD, 14));
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
            chip.setOnClickListener(v -> {
                filter = n;
                showTab(1);
            });
            chips.addView(chip, Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 0, 6, 0));
        }
        hs.addView(chips);
        c.addView(hs);

        LinearLayout bar = Ui.row(this);
        EditText se = Ui.edit(this, "搜索：日文 / 假名 / 中文");
        se.setText(search);
        bar.addView(se, Ui.lpw(0, Ui.WRAP, 1));
        bar.addView(Ui.btn(this, "+ 添加", Ui.BLUE_BG, Ui.BLUE, v -> addDialog()));
        c.addView(bar, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 6, 0, 0));

        TextView count = Ui.tv(this, "", 12, Ui.SUB);
        c.addView(count);
        ListView lv = new ListView(this);
        lv.setDivider(Ui.round(this, Ui.LINE, 0));
        lv.setDividerHeight(1);
        WordAdapter ad = new WordAdapter(count);
        lv.setAdapter(ad);
        lv.setOnItemClickListener((parent, v, pos, id) -> startActivity(
                new Intent(this, DetailActivity.class).putExtra(DetailActivity.EXTRA_ID, ad.items.get(pos).id)));
        c.addView(lv, Ui.lpw(Ui.MATCH, 0, 1));
        c.addView(Ui.tv(this, "圆点：绿 记牢 · 黄 快到期 · 红 常错 · 灰 未学", 11, 0xFF888780),
                Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 4, 0, 4));

        se.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {
            }

            public void onTextChanged(CharSequence s, int a, int b2, int c2) {
            }

            public void afterTextChanged(Editable s) {
                search = s.toString().trim();
                ad.refresh();
            }
        });
        return c;
    }

    private class WordAdapter extends BaseAdapter {
        final ArrayList<Word> items = new ArrayList<>();
        final TextView count;
        Set<String> errata, fixed;

        WordAdapter(TextView count) {
            this.count = count;
            refresh();
        }

        void refresh() {
            errata = App.db.openErrata();
            fixed = App.db.fixedErrata();
            items.clear();
            String q = search.toLowerCase(Locale.ROOT);
            for (Word w : App.content.all()) {
                boolean banned = App.db.banned(w.id);
                boolean ok;
                switch (filter) {
                    case "全部":
                        ok = true;
                        break;
                    case "自添":
                        ok = w.custom;
                        break;
                    case "已斩":
                        ok = banned;
                        break;
                    case "已标勘误":
                        ok = errata.contains(w.id);
                        break;
                    case "待确认":
                        ok = fixed.contains(w.id);
                        break;
                    default:
                        ok = !w.custom && filter.equals(w.level);
                }
                if (!ok) continue;
                if (!q.isEmpty() && !w.expr.contains(q) && !w.reading.contains(q) && !w.zh.contains(q)
                        && !w.romaji.toLowerCase(Locale.ROOT).contains(q)) continue;
                items.add(w);
            }
            count.setText("共 " + items.size() + " 词");
            notifyDataSetChanged();
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int i) {
            return items.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            MainActivity a = MainActivity.this;
            Word w = items.get(i);
            Db.P p = App.db.p(w.id);
            boolean banned = p != null && p.banned;
            LinearLayout row = Ui.row(a);
            row.setPadding(0, Ui.dp(a, 9), 0, Ui.dp(a, 9));

            LinearLayout l = Ui.col(a);
            TextView ex = Ui.tv(a, w.expr, 17, banned ? Ui.SUB : Ui.TXT);
            if (banned) ex.setPaintFlags(ex.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            l.addView(ex);
            TextView sub = Ui.tv(a, w.reading.isEmpty() ? w.zh : w.reading + " · " + w.zh, 12, Ui.SUB);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            l.addView(sub);
            row.addView(l, Ui.lpw(0, Ui.WRAP, 1));

            LinearLayout r = Ui.col(a);
            r.setGravity(Gravity.END);
            LinearLayout top = Ui.row(a);
            top.addView(Ui.tv(a, w.level, 11, w.custom ? Ui.PURPLE : Ui.SUB));
            if (w.ready() && !banned) {
                int color;
                if (p == null || !p.seen()) color = Ui.LINE2;
                else if (!p.learned()) color = Ui.AMBER_MID; // 看过首学卡、还没测
                else if (p.lapses >= 3 || p.lapses * 2 > p.reps) color = Ui.RED_MID;
                else if (p.due <= Db.today() + 1) color = Ui.AMBER_MID;
                else color = Ui.TEAL_MID;
                View dot = new View(a);
                dot.setBackground(Ui.round(a, color, 5));
                top.addView(dot, Ui.lpm(a, Ui.dp(a, 10), Ui.dp(a, 10), 0, 8, 0, 0, 0));
            }
            r.addView(top);
            TextView act;
            if (!w.ready()) {
                act = Ui.tv(a, "待补素材", 12, Ui.AMBER);
            } else if (banned) {
                act = Ui.tv(a, "已斩 · 恢复", 12, Ui.RED);
                act.setOnClickListener(v -> {
                    App.db.ban(w.id, false);
                    refresh();
                });
            } else {
                boolean marked = errata.contains(w.id), fx = fixed.contains(w.id);
                act = Ui.tv(a, fx ? "已勘误待确认" : marked ? "已标勘误" : "勘误", 12,
                        fx ? Ui.BLUE : marked ? Ui.RED : Ui.SUB);
                act.setOnClickListener(v -> Ui.errataDialog(a, w, this::refresh));
            }
            act.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), 0, Ui.dp(a, 2));
            r.addView(act);
            row.addView(r);
            return row;
        }
    }

    /** 自己添加词。新词缺插画和语音，先标“待补素材”，电脑端补全后才加入背诵。 */
    private void addDialog() {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText expr = Ui.edit(this, "日文写法（必填），如 推し");
        EditText reading = Ui.edit(this, "假名读音，如 おし");
        EditText zh = Ui.edit(this, "中文释义（必填）");
        EditText exJa = Ui.edit(this, "例句（可不填）");
        EditText exZh = Ui.edit(this, "例句翻译（可不填）");
        EditText mn = Ui.edit(this, "记忆点（可不填）");
        for (EditText e : new EditText[]{expr, reading, zh, exJa, exZh, mn}) box.addView(e);
        box.addView(Ui.tv(this, "添加后显示“待补素材”，等电脑端补好插画和语音并同步回来后，才会加入背诵。", 12, Ui.SUB));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("添加词").setView(sv)
                .setPositiveButton("添加", (d, i) -> {
                    String e = expr.getText().toString().trim(), z = zh.getText().toString().trim();
                    if (e.isEmpty() || z.isEmpty()) {
                        Ui.toast(this, "日文写法和中文释义必填");
                        return;
                    }
                    App.db.addCustom(e, reading.getText().toString().trim(), z, exJa.getText().toString().trim(),
                            exZh.getText().toString().trim(), mn.getText().toString().trim());
                    App.content.reload();
                    filter = "自添";
                    showTab(1);
                    new Thread(() -> App.db.exportToSync()).start();
                })
                .setNegativeButton("取消", null).show();
    }

    // ================= 设置 =================

    private View settings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout c = page(sv);
        c.addView(Ui.bold(this, "学习计划", 22, Ui.TXT));

        final int rem = Plan.remaining();
        int daily = Plan.dailyNew();
        NumberPicker npDaily = new NumberPicker(this);
        npDaily.setMinValue(1);
        npDaily.setMaxValue(Math.max(daily, Math.max(1, Math.min(500, rem))));
        npDaily.setValue(daily);
        npDaily.setWrapSelectorWheel(false);
        NumberPicker npDays = new NumberPicker(this);
        npDays.setMinValue(1);
        npDays.setMaxValue(Math.max(1, rem));
        npDays.setValue(daysFor(rem, daily));
        npDays.setWrapSelectorWheel(false);
        TextView info = Ui.tv(this, "", 13, Ui.SUB);
        info.setGravity(Gravity.CENTER);

        // 左：每日新词；右：完成天数；中间实时显示换算
        LinearLayout wheels = Ui.row(this);
        wheels.addView(wheel("每日新词", npDaily), Ui.lp(Ui.dp(this, 96), Ui.WRAP));
        wheels.addView(info, Ui.lpw(0, Ui.WRAP, 1));
        wheels.addView(wheel("完成天数", npDays), Ui.lp(Ui.dp(this, 96), Ui.WRAP));
        c.addView(wheels, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 12, 0, 0));

        Runnable upd = () -> {
            int n = npDaily.getValue();
            int dd = daysFor(rem, n);
            info.setText(rem == 0 ? "词表已全部学完"
                    : "剩余 " + rem + " 词\n÷ 每日 " + n + " 词\n≈ " + dd + " 天\n\n预计 "
                    + LocalDate.now().plusDays(dd - 1) + "\n学完");
        };
        upd.run();
        npDaily.setOnValueChangedListener((np, o, n) -> {
            App.db.set("daily_new", String.valueOf(n));
            npDays.setValue(Math.min(npDays.getMaxValue(), daysFor(rem, n)));
            upd.run();
        });
        npDays.setOnValueChangedListener((np, o, n) -> {
            int need = Math.max(1, (rem + n - 1) / n);
            need = Math.min(npDaily.getMaxValue(), need);
            App.db.set("daily_new", String.valueOf(need));
            npDaily.setValue(need);
            upd.run();
        });

        c.addView(Ui.line(this));
        int custom = 0, notReady = 0;
        for (Word w : App.content.all()) {
            if (w.custom) custom++;
            if (!w.ready()) notReady++;
        }
        int[] st = Plan.stats();
        c.addView(kv("目标词典", TextUtils.join(" / ", App.content.levels())));
        c.addView(kv("可背词数", st[0] + " 词（已斩 " + st[2] + "）"));
        c.addView(kv("自添词", custom + " 词"));
        c.addView(kv("待补素材", notReady + " 词"));

        c.addView(Ui.line(this));
        c.addView(Ui.bold(this, "五十音", 16, Ui.TXT));
        c.addView(toggle("五十音加入日常学习", "kana_on"));
        c.addView(toggle("仅学假名（暂不出新单词，已学单词照常复习）", "kana_only", "0"));
        LinearLayout kd = Ui.row(this);
        kd.addView(Ui.tv(this, "每日新假名", 14, Ui.TXT), Ui.lpw(0, Ui.WRAP, 1));
        TextView kv = Ui.tv(this, String.valueOf(Plan.kanaDaily()), 16, Ui.TXT);
        kv.setGravity(Gravity.CENTER);
        kd.addView(Ui.ghost(this, "−", v -> {
            App.db.set("kana_daily", String.valueOf(Math.max(1, Plan.kanaDaily() - 1)));
            kv.setText(String.valueOf(Plan.kanaDaily()));
        }));
        kd.addView(kv, Ui.lp(Ui.dp(this, 48), Ui.WRAP));
        kd.addView(Ui.ghost(this, "+", v -> {
            App.db.set("kana_daily", String.valueOf(Math.min(104, Plan.kanaDaily() + 1)));
            kv.setText(String.valueOf(Plan.kanaDaily()));
        }));
        c.addView(kd);
        int[] ks = Plan.kanaStats();
        c.addView(Ui.tv(this, "共 " + ks[0] + " 个音，已学 " + ks[1] + "。每个音先看平假名、片假名、罗马音，再做四选一；"
                + "平假名和片假名各记各的成绩，掌握情况在五十音图里用颜色显示。", 12, 0xFF888780));

        c.addView(Ui.line(this));
        c.addView(toggle("每道题自动朗读", "auto_read"));
        c.addView(toggle("选对 / 选错提示音", "fx_sound"));
        c.addView(toggle("允许外放（关闭时只在耳机里播放）", "speaker_on", "0"));
        c.addView(Ui.tv(this, "连着耳机时只从耳机播放。没连耳机时，打开“允许外放”才会外放，不管手机是否静音。"
                + "媒体音量为 0 时任何情况都不出声。", 12, 0xFF888780));

        c.addView(Ui.line(this));
        c.addView(Ui.bold(this, "数据库", 16, Ui.TXT));
        boolean can = App.db.canSync();
        long last = 0;
        try {
            last = Long.parseLong(App.db.get("last_sync", "0"));
        } catch (NumberFormatException ignored) {
        }
        String when = last == 0 ? "还没同步过" : "上次同步 " + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new java.util.Date(last));
        c.addView(Ui.tv(this, "全部用户数据在一个文件 user.db 里，每次学习后自动复制到\n内部存储/1/SyncProjects/JapaneseWords/\n"
                + (can ? when : "未授权，无法同步"), 12, Ui.SUB), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 4, 0, 8));
        if (!can) c.addView(Ui.btn(this, "授予“所有文件访问”权限", Ui.AMBER_BG, Ui.AMBER, v -> askSync()));
        LinearLayout row = Ui.row(this);
        row.addView(Ui.ghost(this, "立即同步", v -> {
            Ui.toast(this, App.db.exportToSync() ? "已同步" : "同步失败，检查权限");
            showTab(3);
        }), Ui.lpw(0, Ui.WRAP, 1));
        row.addView(Ui.gap(this, 8, 1));
        row.addView(Ui.ghost(this, "从同步文件夹导入", v -> new AlertDialog.Builder(this)
                .setMessage("用同步文件夹里的 user.db 覆盖本机的学习数据？本机现有数据会被替换。")
                .setPositiveButton("导入", (d, i) -> {
                    boolean ok = App.db.importFromSync();
                    App.content.reload();
                    Ui.toast(this, ok ? "已导入" : "导入失败：没有权限或找不到 user.db");
                    showTab(3);
                }).setNegativeButton("取消", null).show()), Ui.lpw(0, Ui.WRAP, 1));
        c.addView(row, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        c.addView(Ui.ghost(this, "重新读取词库和补充素材", v -> {
            App.content.reload();
            Ui.toast(this, "已重新读取");
            showTab(3);
        }), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        return sv;
    }

    private static int daysFor(int rem, int daily) {
        return Math.max(1, (rem + daily - 1) / Math.max(1, daily));
    }

    private View wheel(String label, NumberPicker np) {
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Ui.tv(this, label, 13, Ui.SUB));
        c.addView(np, Ui.lp(Ui.dp(this, 80), Ui.dp(this, 150)));
        return c;
    }

    private View kv(String k, String v) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        row.addView(Ui.tv(this, k, 14, Ui.SUB), Ui.lpw(0, Ui.WRAP, 1));
        row.addView(Ui.tv(this, v, 14, Ui.TXT));
        return row;
    }

    private View toggle(String label, String key) {
        return toggle(label, key, "1");
    }

    private View toggle(String label, String key, String def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TXT);
        s.setTextSize(14);
        s.setChecked("1".equals(App.db.get(key, def)));
        s.setOnCheckedChangeListener((b, on) -> App.db.set(key, on ? "1" : "0"));
        s.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return s;
    }

    private void askSync() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }
}
