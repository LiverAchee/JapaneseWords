package com.affairs.japanesewords;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** 界面小工具：颜色、圆角背景、按钮，以及勘误对话框。全部界面用代码搭，不依赖第三方库。 */
public final class Ui {
    public static final int BG = 0xFF1E1E1C, CARD = 0xFF2C2C2A, LINE = 0xFF444441, LINE2 = 0xFF5F5E5A,
            TXT = 0xFFF1EFE8, SUB = 0xFFB4B2A9,
            TEAL_BG = 0xFF085041, TEAL = 0xFF9FE1CB, TEAL_MID = 0xFF1D9E75,
            RED_BG = 0xFF791F1F, RED = 0xFFF7C1C1, RED_MID = 0xFFE24B4A,
            GREEN_BG = 0xFF27500A, GREEN = 0xFFC0DD97,
            AMBER_BG = 0xFF412402, AMBER = 0xFFFAC775, AMBER_MID = 0xFFEF9F27,
            BLUE_BG = 0xFF0C447C, BLUE = 0xFFB5D4F4,
            PURPLE_MID = 0xFF7F77DD, PURPLE = 0xFFCECBF6;

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String[] ERRATA_TYPES = {"中文释义", "读音 / 假名", "例句", "记忆点", "插画", "语音", "其他"};

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable outline(Context c, int fill, int stroke, float widthDp, float radiusDp) {
        GradientDrawable g = round(c, fill, radiusDp);
        g.setStroke(Math.max(1, dp(c, widthDp)), stroke);
        return g;
    }

    public static TextView tv(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView bold(Context c, CharSequence s, float sp, int color) {
        TextView t = tv(c, s, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 圆角按钮（其实是可点的 TextView，方便控制深色样式）。 */
    public static TextView btn(Context c, CharSequence s, int bg, int fg, View.OnClickListener l) {
        TextView t = tv(c, s, 15, fg);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(c, bg, 10));
        int h = dp(c, 14), v = dp(c, 9);
        t.setPadding(h, v, h, v);
        t.setMinWidth(dp(c, 48));
        t.setOnClickListener(l);
        return t;
    }

    public static TextView ghost(Context c, CharSequence s, View.OnClickListener l) {
        TextView t = btn(c, s, CARD, SUB, l);
        t.setBackground(outline(c, CARD, LINE2, 1, 10));
        return t;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 带外边距的布局参数（单位 dp：左、上、右、下）。 */
    public static LinearLayout.LayoutParams lpm(Context c, int w, int h, float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static View gap(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    public static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(MATCH, Math.max(1, dp(c, 0.5f)));
        p.setMargins(0, dp(c, 10), 0, dp(c, 10));
        v.setLayoutParams(p);
        return v;
    }

    /** 进度条：frac 为 0..1。 */
    public static View progress(Context c, float frac, int color) {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
        bar.setBackground(round(c, LINE, 4));
        LinearLayout inner = row(c);
        frac = Math.max(0f, Math.min(1f, frac));
        View fill = new View(c);
        fill.setBackground(round(c, color, 4));
        inner.addView(fill, lpw(0, MATCH, frac));
        inner.addView(new View(c), lpw(0, MATCH, 1 - frac));
        bar.addView(inner, new android.widget.FrameLayout.LayoutParams(MATCH, MATCH));
        bar.setLayoutParams(lp(MATCH, dp(c, 8)));
        return bar;
    }

    public static EditText edit(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextColor(TXT);
        e.setHintTextColor(0xFF888780);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 词条信息块：大字单词、假名和罗马字、词性释义、例句（点一下朗读）、记忆点。
     * 答题页的新词首学 / 揭示记忆点，以及词表点开的详情页共用。
     */
    /** 让一段文字可以长按复制到剪贴板。 */
    public static void copyable(TextView t, String text) {
        t.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("word", text));
            toast(v.getContext(), "已复制：" + text);
            return true;
        });
    }

    /** 按词的长度选字号：短词大字，长词缩小（放不下会自动换行）。 */
    public static float wordSize(String expr, float max) {
        int n = expr.length();
        float sp = n <= 4 ? 46 : n <= 6 ? 38 : n <= 9 ? 30 : 24;
        return Math.min(max, sp);
    }

    /**
     * 词条头部，全部居中竖排：词语（长按复制）、假名 · 罗马音、词性 · 释义、读音按钮。
     * 每项各占一行，长词不会把其他内容挤掉。
     */
    public static LinearLayout wordHead(Activity a, Word w, float maxSp) {
        LinearLayout box = col(a);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView word = tv(a, w.expr, wordSize(w.expr, maxSp), TXT);
        word.setGravity(Gravity.CENTER);
        copyable(word, w.expr);
        box.addView(word, lp(MATCH, WRAP));
        TextView rd = tv(a, w.readingLine(), 15, SUB);
        rd.setGravity(Gravity.CENTER);
        copyable(rd, w.reading);
        box.addView(rd, lp(MATCH, WRAP));
        TextView zh = tv(a, (w.pos.isEmpty() ? "" : w.pos + " · ") + w.zh, 16, TXT);
        zh.setGravity(Gravity.CENTER);
        box.addView(zh, lpm(a, MATCH, WRAP, 0, 0, 4, 0, 0));
        if (w.hasAudio) {
            box.addView(btn(a, "读音", BLUE_BG, BLUE, v -> App.sound.play(w.id)), lpm(a, WRAP, WRAP, 0, 0, 8, 0, 0));
        }
        return box;
    }

    /** 例句卡（点一下朗读，长按复制）和记忆点卡。 */
    public static LinearLayout wordCards(Activity a, Word w) {
        LinearLayout box = col(a);
        if (!w.exJa.isEmpty()) {
            LinearLayout ex = col(a);
            ex.setBackground(round(a, CARD, 8));
            int p = dp(a, 10);
            ex.setPadding(p, p, p, p);
            ex.addView(tv(a, w.exJa, 16, TXT));
            if (!w.exZh.isEmpty()) ex.addView(tv(a, w.exZh, 13, SUB));
            if (App.content.hasSentenceAudio(w)) {
                ex.addView(tv(a, "点这里朗读例句", 11, BLUE));
                ex.setOnClickListener(v -> App.sound.play(w.id + "_s"));
            }
            ex.setOnLongClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        a.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("sentence", w.exJa));
                toast(a, "已复制例句");
                return true;
            });
            box.addView(ex, lpm(a, MATCH, WRAP, 0, 0, 10, 0, 0));
        }
        if (!w.mnemonic.isEmpty()) {
            LinearLayout mn = col(a);
            mn.setBackground(round(a, AMBER_BG, 8));
            int p = dp(a, 10);
            mn.setPadding(p, p, p, p);
            mn.addView(bold(a, "记忆点", 13, AMBER));
            mn.addView(tv(a, w.mnemonic, 15, AMBER));
            box.addView(mn, lpm(a, MATCH, WRAP, 0, 0, 10, 0, 0));
        }
        return box;
    }

    /** 词条信息块 = 头部 + 例句和记忆点。揭示答案、词表详情页用。 */
    public static LinearLayout wordInfo(Activity a, Word w, float wordSp) {
        LinearLayout box = col(a);
        box.addView(wordHead(a, w, wordSp));
        box.addView(wordCards(a, w));
        return box;
    }

    /** 勘误按钮上的字：没标过“勘误”，标了“已标勘误”，电脑端处理完“已勘误待确认”。 */
    public static String errataLabel(Word w) {
        if (App.db.fixedErrata().contains(w.id)) return "已勘误待确认";
        if (App.db.openErrata().contains(w.id)) return "已标勘误";
        return "勘误";
    }

    /** 勘误：选哪里有问题 + 备注，存进用户数据库，电脑端据此校对词表。 */
    public static void errataDialog(Activity a, Word w, Runnable after) {
        // 电脑端已经更正过、等用户确认的：先问改得对不对
        if (App.db.fixedErrata().contains(w.id)) {
            new AlertDialog.Builder(a)
                    .setTitle("已勘误待确认 · " + w.expr)
                    .setMessage("你之前标记的问题：\n" + App.db.fixedErrataText(w.id) + "\n\n电脑端已更正，现在的内容对了吗？")
                    .setPositiveButton("改好了", (d, i) -> {
                        App.db.resolveFixedErrata(w.id, true);
                        toast(a, "已确认");
                        if (after != null) after.run();
                    })
                    .setNegativeButton("还是不对", (d, i) -> {
                        App.db.resolveFixedErrata(w.id, false);
                        if (after != null) after.run();
                        errataDialog(a, w, after);
                    })
                    .setNeutralButton("先不管", null)
                    .show();
            return;
        }
        LinearLayout box = col(a);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        Spinner sp = new Spinner(a);
        sp.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, ERRATA_TYPES));
        box.addView(sp);
        EditText note = edit(a, "备注：哪里不对、应该是什么（可不填）");
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setMinLines(2);
        box.addView(note);
        boolean marked = App.db.openErrata().contains(w.id);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("勘误 · " + w.expr)
                .setView(box)
                .setPositiveButton("标记", (d, i) -> {
                    App.db.addErrata(w, ERRATA_TYPES[sp.getSelectedItemPosition()], note.getText().toString().trim());
                    toast(a, "已标记勘误");
                    if (after != null) after.run();
                })
                .setNegativeButton("取消", null);
        if (marked) {
            b.setNeutralButton("撤销已有标记", (d, i) -> {
                App.db.clearErrata(w.id);
                toast(a, "已撤销");
                if (after != null) after.run();
            });
        }
        b.show();
    }
}
