package com.affairs.japanesewords;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * 学习页。一轮学习里假名和单词穿插出现。
 *
 * 排队：新内容每 5 个一组，先看这一组的首学卡，再做这一组的题；还有没学完的假名时，新假名排在新单词前面。
 *       到期复习（假名和单词混在一起）均匀穿插进每一组的题里。
 * 单词：看词选图。4 张图里 3 张干扰图每次都从整个图库重新随机抽。
 * 假名：只有文字和读音。四选一，罗马音、平假名、片假名三者互相对应，题型随机；平、片假名各记各的成绩。
 * 共同规则：选错可以继续选，直到选对；选对后揭示答案；选错过的稍后在本轮再测一次。
 * “上一个”可以回看前面的卡片（不会重复计分）。
 *
 * 断点续学：整轮的队列和位置存在设置项 session 里，当天退出再进来从上次的位置继续。
 * 屏幕方向：系统开着自动旋转时跟着手机转；关着时用左上角按钮在横屏 / 竖屏间切换并记住。
 */
public class QuizActivity extends Activity {
    // 计划完成后加学的一轮：再学几个新词 / 再学几个新假名 / 再复习几项最容易忘的（全部学完后才有）
    public static final String EXTRA_MORE = "more";
    public static final String EXTRA_MORE_KANA = "more_kana";
    public static final String EXTRA_REVIEW = "review";
    // 仅复习：纯练习，反复练已学过的内容，不记成绩、不改排期。不占用也不改动“学习”那一轮的断点
    public static final String EXTRA_REVIEW_ONLY = "review_only";

    private static class Item {
        final Word w;          // 单词项
        final Kana.Entry k;    // 假名项（w 为 null）
        final char track;      // 假名检查的是哪条进度：'h' 平假名 / 'k' 片假名；首学卡为 0
        final boolean intro;   // 首学卡
        final boolean retry;   // 本轮选错后的再测，不重复计入进度
        boolean done;          // 已选对（首学卡：已看过）
        int wrongs;
        final HashSet<String> wrongIds = new HashSet<>();
        List<Word> options;          // 单词题的 4 张图
        List<Kana.Entry> kOptions;   // 假名题的 4 个选项
        char promptForm, optionForm; // 假名题：题面和选项各用哪种写法（h / k / r）

        Item(Word w, boolean intro, boolean retry) {
            this.w = w;
            this.k = null;
            this.track = 0;
            this.intro = intro;
            this.retry = retry;
        }

        Item(Kana.Entry k, char track, boolean intro, boolean retry) {
            this.w = null;
            this.k = k;
            this.track = track;
            this.intro = intro;
            this.retry = retry;
        }

        boolean kana() {
            return k != null;
        }

        boolean same(Item o) {
            return kana() ? k == o.k : w == o.w;
        }

        String name() {
            return kana() ? k.hira + "／" + k.kata : w.expr;
        }
    }

    /** 正方形容器，放图片 / 选项区：横屏时边长取高度，竖屏时取宽度。 */
    private static class SquareBox extends FrameLayout {
        final boolean byWidth;

        SquareBox(Activity a, boolean byWidth) {
            super(a);
            this.byWidth = byWidth;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int side = MeasureSpec.getSize(byWidth ? w : h);
            int spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
            super.onMeasure(spec, spec);
        }
    }

    private final ArrayList<Item> queue = new ArrayList<>();
    private final Random rnd = new Random();
    private int pos;
    private Item cur;
    private boolean finished, extraRound, reviewOnly;
    private boolean advancing; // 刚选对、正在等自动跳到下一题的那半秒
    private String hint;      // 刚选错的那个选项其实是什么
    private Item lastBanned;  // 上一项被斩时，在下一题显示“恢复”
    private final ArrayList<Item> bannedCards = new ArrayList<>();     // 刚被斩掉的卡片
    private final ArrayList<Integer> bannedIndex = new ArrayList<>();  // 它们原来在队列里的位置
    private int bannedPos;                                             // 被斩时所在的位置
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        applyOrientation();
        root = Ui.row(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        int more = getIntent().getIntExtra(EXTRA_MORE, 0);
        int moreKana = getIntent().getIntExtra(EXTRA_MORE_KANA, 0);
        int review = getIntent().getIntExtra(EXTRA_REVIEW, 0);
        reviewOnly = getIntent().getBooleanExtra(EXTRA_REVIEW_ONLY, false);
        extraRound = more + moreKana + review > 0;
        if (reviewOnly) buildReviewOnly();
        else if (extraRound || !restoreSession()) buildQueue(more, moreKana, review);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyOrientation();
        // 离开期间系统的自动旋转开关可能变了，重画一次让左上角的切换按钮跟着显示 / 隐藏
        if (root != null && cur != null && !finished) render();
    }

    // ---------- 屏幕方向 ----------

    private boolean autoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private void applyOrientation() {
        if (autoRotate()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        } else if ("p".equals(App.db.get("quiz_orient", "l"))) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private boolean portrait() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (finished) return;
        render();
    }

    // ---------- 排队 ----------

    /**
     * 仅复习 = 纯练习：随时可以反复练已学过的内容，不记成绩、不改排期、不算打卡，
     * 所以不影响以后每天系统安排的复习计划。
     * 一轮最多 40 题：已学内容不超过 40 项时全部出；更多时一半挑最容易忘的，一半随机。
     */
    private void buildReviewOnly() {
        queue.clear();
        pos = 0;
        List<Object[]> pool = Plan.practicePool();
        Collections.shuffle(pool);
        int max = 40;
        if (pool.size() > max) {
            ArrayList<Object[]> weak = new ArrayList<>(pool);
            weak.sort((a, b) -> Double.compare((double) a[2], (double) b[2]));
            ArrayList<Object[]> pick = new ArrayList<>(weak.subList(0, max / 2));
            for (Object[] d : pool) {
                if (pick.size() >= max) break;
                if (!pick.contains(d)) pick.add(d);
            }
            pool = pick;
            Collections.shuffle(pool);
        }
        for (Object[] d : pool) {
            queue.add(d[0] instanceof Word ? new Item((Word) d[0], false, false)
                    : new Item((Kana.Entry) d[0], (char) d[1], false, false));
        }
    }

    /**
     * 正常一轮：三个参数都是 0，新词和新假名按每日额度。
     * 加学的一轮：more = 再学几个新词；moreKana = 再学几个新假名；review = 再复习几项最容易忘的。
     */
    private void buildQueue(int more, int moreKana, int review) {
        queue.clear();
        pos = 0;
        int chunk = 5;
        boolean normal = more + moreKana + review == 0;

        // 到期复习：假名和单词混在一起打乱
        ArrayList<Item> reviews = new ArrayList<>();
        for (Word w : Plan.due()) reviews.add(new Item(w, false, false));
        for (Object[] d : Plan.kanaDue()) reviews.add(new Item((Kana.Entry) d[0], (char) d[1], false, false));
        // 全部学完后的“一直复习”：再加上此刻最容易忘的几项（同一天重复复习，答对不会改变排期）
        if (review > 0) {
            for (Object[] d : Plan.weakest(review, Plan.kanaOnly())) {
                Item it = d[0] instanceof Word ? new Item((Word) d[0], false, false)
                        : new Item((Kana.Entry) d[0], (char) d[1], false, false);
                boolean dup = false;
                for (Item r : reviews) if (r.same(it) && r.track == it.track) dup = true;
                if (!dup) reviews.add(it);
            }
        }
        Collections.shuffle(reviews);

        // 新内容分组：每组 = 首学卡 + 这一组的题。没学完的假名排在新单词前面。
        ArrayList<List<Item>> intros = new ArrayList<>(), quizzes = new ArrayList<>();
        if (normal || moreKana > 0) {
            List<Kana.Entry> un = Plan.kanaUnlearned();
            List<Kana.Entry> kNew = un.subList(0, Math.min(un.size(), normal ? Plan.kanaQuota() : moreKana));
            for (int c = 0; c * chunk < kNew.size(); c++) {
                ArrayList<Item> in = new ArrayList<>(), qz = new ArrayList<>();
                for (Kana.Entry e : kNew.subList(c * chunk, Math.min(kNew.size(), (c + 1) * chunk))) {
                    in.add(new Item(e, (char) 0, true, false));
                    qz.add(new Item(e, 'h', false, false));
                    qz.add(new Item(e, 'k', false, false));
                }
                intros.add(in);
                quizzes.add(qz);
            }
        }
        List<Word> fresh = Plan.pickNew(normal ? Plan.newQuota() : more);
        for (int c = 0; c * chunk < fresh.size(); c++) {
            ArrayList<Item> in = new ArrayList<>(), qz = new ArrayList<>();
            for (Word w : fresh.subList(c * chunk, Math.min(fresh.size(), (c + 1) * chunk))) {
                in.add(new Item(w, true, false));
                qz.add(new Item(w, false, false));
            }
            intros.add(in);
            quizzes.add(qz);
        }

        // 复习题均匀穿插进每一组
        int groups = intros.size();
        int per = groups == 0 ? 0 : (reviews.size() + groups - 1) / groups;
        int ri = 0;
        for (int g = 0; g < groups; g++) {
            queue.addAll(intros.get(g));
            ArrayList<Item> mix = new ArrayList<>(quizzes.get(g));
            for (int i = 0; i < per && ri < reviews.size(); i++) mix.add(reviews.get(ri++));
            Collections.shuffle(mix);
            queue.addAll(mix);
        }
        while (ri < reviews.size()) queue.add(reviews.get(ri++));
        saveSession();
    }

    // ---------- 断点续学 ----------

    /** 把整轮队列和当前位置存起来（退出、切到后台、每前进一步时）。 */
    private void saveSession() {
        if (reviewOnly) return; // 仅复习不存断点，也不覆盖“学习”那一轮的断点
        try {
            JSONArray arr = new JSONArray();
            for (Item it : queue) {
                JSONObject o = new JSONObject();
                if (it.kana()) {
                    o.put("k", it.k.key);
                    o.put("t", String.valueOf(it.track == 0 ? '-' : it.track));
                } else {
                    o.put("w", it.w.id);
                }
                o.put("i", it.intro ? 1 : 0);
                o.put("r", it.retry ? 1 : 0);
                o.put("d", it.done ? 1 : 0);
                o.put("x", it.wrongs);
                arr.put(o);
            }
            JSONObject s = new JSONObject();
            s.put("day", Db.today());
            s.put("pos", pos);
            s.put("items", arr);
            App.db.set("session", s.toString());
        } catch (Exception ignored) {
        }
    }

    /** 读回今天没学完的那一轮。成功返回 true；没有、已过期或已学完返回 false。 */
    private boolean restoreSession() {
        try {
            String raw = App.db.get("session", "");
            if (raw.isEmpty()) return false;
            JSONObject s = new JSONObject(raw);
            if (s.getLong("day") != Db.today()) return false;
            int savedPos = s.getInt("pos");
            JSONArray arr = s.getJSONArray("items");
            queue.clear();
            pos = 0;
            // 这一轮排好之后设置可能改过：打开了“仅学假名”就去掉还没学的新词，关掉了五十音就去掉假名
            boolean kanaOnly = Plan.kanaOnly(), kanaOn = Plan.kanaOn();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = null;
                boolean intro = o.optInt("i") == 1, retry = o.optInt("r") == 1;
                if (o.has("k") && !kanaOn) continue;
                if (!o.has("k") && kanaOnly && !App.db.seen(o.optString("w"))) continue;
                // 这道复习题今天已经在别处（“仅复习”）答过了，就不再重复出
                if (!intro && !retry && o.optInt("d") == 0) {
                    String pid = o.has("k") ? "k" + o.optString("t", "h") + "_" + o.getString("k") : o.optString("w");
                    Db.P pp = App.db.p(pid);
                    if (pp != null && pp.learned() && pp.last == Db.today()) continue;
                }
                if (o.has("k")) {
                    for (Kana.Entry e : Kana.all()) {
                        if (e.key.equals(o.getString("k")) && !App.db.banned(e.id('h'))) {
                            char t = o.optString("t", "-").charAt(0);
                            it = new Item(e, t == '-' ? (char) 0 : t, intro, retry);
                        }
                    }
                } else {
                    Word w = App.content.get(o.optString("w"));
                    if (w != null && w.ready() && !App.db.banned(w.id)) it = new Item(w, intro, retry);
                }
                if (it == null) continue; // 这期间被斩掉或素材没了的项跳过
                it.done = o.optInt("d") == 1;
                it.wrongs = o.optInt("x");
                queue.add(it);
                if (i < savedPos) pos = queue.size();
            }
            return pos < queue.size();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 流程 ----------

    private boolean autoRead() {
        return "1".equals(App.db.get("auto_read", "1"));
    }

    private int total() {
        int n = 0;
        for (Item it : queue) if (!it.intro && !it.retry) n++;
        return n;
    }

    private int doneCount() {
        int n = 0;
        for (Item it : queue) if (!it.intro && !it.retry && it.done) n++;
        return n;
    }

    private void show() {
        if (pos >= queue.size()) {
            finishRound();
            return;
        }
        cur = queue.get(pos);
        hint = null;
        if (!cur.intro) {
            if (cur.kana() && cur.kOptions == null) pickKanaOptions();
            if (!cur.kana() && cur.options == null) pickOptions();
        }
        render();
        if (cur.done || !autoRead()) return; // 回看已做过的卡片不自动朗读
        if (cur.kana()) {
            // 选项是罗马音时先不念，否则读音就把答案说出来了；其余题型选之前先念
            if (cur.intro || cur.optionForm != 'r') App.sound.play("kana_" + cur.k.key);
        } else if (cur.intro) {
            App.sound.play(cur.w.id, cur.w.id + "_s", cur.w.id); // 新词首学卡：词 → 例句 → 词
        } else {
            App.sound.play(cur.w.id);
        }
    }

    /** 3 张干扰图每次都从整个图库随机抽，避免固定搭配加深错误印象。 */
    private void pickOptions() {
        List<Word> pool = App.content.ready();
        Collections.shuffle(pool, rnd);
        ArrayList<Word> opts = new ArrayList<>();
        opts.add(cur.w);
        for (Word w : pool) {
            if (opts.size() >= 4) break;
            boolean clash = false;
            // 会混淆的（同一个词、同写法、同释义、同互斥组）不当干扰项
            for (Word o : opts) {
                if (o == w || o.expr.equals(w.expr) || o.zh.equals(w.zh)
                        || (!w.grp.isEmpty() && w.grp.equals(o.grp))) clash = true;
            }
            if (!clash) opts.add(w);
        }
        Collections.shuffle(opts, rnd);
        cur.options = opts;
    }

    /**
     * 假名题型随机三选一（target = 这条进度的写法，平假名或片假名）：
     * 罗马音 → target；target → 罗马音；另一种假名 → target。
     * “选罗马音”的题出题时不念（念了就等于说出答案），选对后停在答案页念读音，点“下一个”继续。
     * 干扰项取同一类（清音 / 浊音 / 拗音）的音，罗马音相同的（じ / ぢ）不同时出现。
     */
    private void pickKanaOptions() {
        char target = cur.track, other = target == 'h' ? 'k' : 'h';
        switch (rnd.nextInt(3)) {
            case 0:
                cur.promptForm = 'r';
                cur.optionForm = target;
                break;
            case 1:
                cur.promptForm = target;
                cur.optionForm = 'r';
                break;
            default:
                cur.promptForm = other;
                cur.optionForm = target;
        }
        ArrayList<Kana.Entry> pool = new ArrayList<>();
        for (Kana.Entry e : Kana.all()) if (e.section == cur.k.section) pool.add(e);
        Collections.shuffle(pool, rnd);
        ArrayList<Kana.Entry> opts = new ArrayList<>();
        opts.add(cur.k);
        for (Kana.Entry e : pool) {
            if (opts.size() >= 4) break;
            boolean clash = false;
            for (Kana.Entry o : opts) if (o == e || o.roma.equals(e.roma)) clash = true;
            if (!clash) opts.add(e);
        }
        Collections.shuffle(opts, rnd);
        cur.kOptions = opts;
    }

    private void advance() {
        lastBanned = null;
        if (cur.intro && !cur.done) {
            cur.done = true;
            // 点了“记住了”就立刻记为已学，词表和五十音图马上更新，不用等这一组的题做完
            if (cur.kana()) {
                App.db.introduce(cur.k.id('h'));
                App.db.introduce(cur.k.id('k'));
            } else {
                App.db.introduce(cur.w.id);
            }
        }
        pos++;
        saveSession();
        show();
    }

    /** 上一个：回看前一张卡片。已做过的题直接显示答案，不会重复计分。 */
    private void back() {
        if (pos == 0 || advancing) return;
        lastBanned = null;
        App.sound.stop();
        pos--;
        saveSession();
        show();
    }

    /** 选对：记成绩（再测不重复记）、选错过的稍后再测、揭示答案。 */
    private void correct() {
        App.sound.fx(true);
        cur.done = true;
        if (!cur.retry && !reviewOnly) { // 纯练习不记成绩
            if (cur.kana()) App.db.review(cur.k.id(cur.track), 5, cur.wrongs == 0, cur.wrongs);
            else App.db.review(cur.w, cur.wrongs == 0, cur.wrongs);
        }
        if (cur.wrongs > 0) {
            int at = Math.min(queue.size(), pos + 5 + rnd.nextInt(3));
            queue.add(at, cur.kana() ? new Item(cur.k, cur.track, false, true) : new Item(cur.w, false, true));
        }
        saveSession();
        // 已经答了下一题，上一项的“恢复”横幅就收起（之后要恢复去词表“已斩”或五十音页）
        lastBanned = null;
        bannedCards.clear();
        bannedIndex.clear();
        // 选错过的题不自动跳：停在答案页（记忆点 / 三种写法），等用户看完点“下一个”。
        // 本轮最后一题也不自动跳，留在答案页，点“完成”再进结算。
        // “选罗马音”的假名题也不自动跳：停在答案页念读音，点“下一个”继续。
        boolean romaOpt = cur.kana() && cur.optionForm == 'r';
        if (romaOpt && autoRead()) App.sound.play("kana_" + cur.k.key);
        if (cur.wrongs > 0 || romaOpt || pos == queue.size() - 1) {
            render();
            return;
        }
        // 一次选对：答案亮绿约半秒，然后自动进入下一题（想回看记忆点可以点“上一个”）
        advancing = true;
        render();
        Item it = cur;
        root.postDelayed(() -> {
            advancing = false;
            if (!finished && !isFinishing() && cur == it) advance();
        }, 550);
    }

    private void onTile(Word o) {
        if (advancing) return;
        if (cur.done) {
            new AlertDialog.Builder(this)
                    .setTitle(o.expr + "  " + o.reading)
                    .setMessage(o.zh + (o.mnemonic.isEmpty() ? "" : "\n\n记忆点：" + o.mnemonic))
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (o == cur.w) {
            correct();
        } else if (cur.wrongIds.add(o.id)) {
            cur.wrongs++;
            hint = "这张图是「" + o.expr + " " + o.reading + "」" + o.zh + (o.mnemonic.isEmpty() ? "" : "\n" + o.mnemonic);
            App.sound.fx(false);
            render();
        }
    }

    private void onKanaTile(Kana.Entry o) {
        if (advancing) return;
        if (cur.done) {
            App.sound.play("kana_" + o.key);
            return;
        }
        if (o == cur.k) {
            correct();
        } else if (cur.wrongIds.add(o.key)) {
            cur.wrongs++;
            hint = "这个是 " + o.hira + " ／ " + o.kata + " = " + o.roma;
            App.sound.fx(false);
            render();
        }
    }

    /** 斩：以后不再出现。下一题顶部提供“恢复”，防止误触。假名的平、片两条进度一起斩。 */
    private void ban() {
        if (advancing) return;
        Item it0 = cur;
        setBanned(it0, true);
        App.sound.stop();
        App.sound.fxBan();
        // 这一项在本轮里的所有卡片（包括当前这张）都拿掉；记下原样，“恢复”时原位放回
        bannedCards.clear();
        bannedIndex.clear();
        bannedPos = pos;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).same(it0)) {
                bannedCards.add(queue.get(i));
                bannedIndex.add(i);
            }
        }
        for (int i = bannedIndex.size() - 1; i >= 0; i--) {
            int idx = bannedIndex.get(i);
            queue.remove(idx);
            if (idx < pos) pos--;
        }
        saveSession();
        lastBanned = it0;
        show(); // 斩掉的是最后一项时会直接进结算页，那里也有“恢复”
    }

    private void setBanned(Item it, boolean banned) {
        if (it.kana()) {
            App.db.ban(it.k.id('h'), banned);
            App.db.ban(it.k.id('k'), banned);
        } else {
            App.db.ban(it.w.id, banned);
        }
    }

    private void restore() {
        Item it = lastBanned;
        lastBanned = null;
        if (it == null) return;
        setBanned(it, false);
        if (finished) {
            // 从结算页恢复：回到学习状态
            finished = false;
            root.removeAllViews();
        }
        // 原样放回：每张卡片回到原来的位置，已做过的仍算做过（不会重复计分），并回到被斩时的那一张
        for (int i = 0; i < bannedCards.size(); i++) {
            queue.add(Math.min(queue.size(), bannedIndex.get(i)), bannedCards.get(i));
        }
        pos = Math.min(bannedPos, queue.size() - 1);
        bannedCards.clear();
        bannedIndex.clear();
        saveSession();
        Ui.toast(this, "已恢复「" + it.name() + "」");
        show();
    }

    private void finishRound() {
        finished = true;
        App.sound.stop();
        int answered = total();
        if (!reviewOnly) App.db.set("session", "");
        long t = Db.today();
        boolean planDone = !reviewOnly && Plan.due().isEmpty() && Plan.newQuota() == 0
                && Plan.kanaDue().isEmpty() && Plan.kanaQuota() == 0;
        if (planDone) App.db.markDone(t);
        new Thread(() -> App.db.exportToSync()).start();

        root.removeAllViews();
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER);
        int[] dc = App.db.dayCounts(t);
        String title = answered == 0 && !extraRound ? (reviewOnly ? "还没有学过的内容可以练" : "今天没有要学的内容")
                : planDone ? "今日计划完成，已打卡" : reviewOnly ? "这一轮练完了（" + answered + " 题）" : "这一轮学完了";
        box.addView(center(Ui.bold(this, title, 24, Ui.TEAL)));
        String sub = reviewOnly ? "纯练习，不记成绩，不影响每天的复习计划"
                : "今天新学 " + dc[0] + " 词 · 复习 " + dc[1] + " 词 · 假名 " + dc[2] + " 题";
        box.addView(center(Ui.tv(this, sub, 16, Ui.SUB)), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 10, 0, 20));
        if (reviewOnly && answered > 0) {
            // 想一直练就接着再来一轮（重新抽题）
            box.addView(Ui.btn(this, "再练一轮", Ui.BLUE_BG, Ui.BLUE, v -> {
                finished = false;
                lastBanned = null;
                root.removeAllViews();
                buildReviewOnly();
                show();
            }), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 0, 0, 12));
        }
        box.addView(Ui.btn(this, "返回首页", Ui.TEAL_BG, Ui.TEAL, v -> finish()));
        if (lastBanned != null && !bannedCards.isEmpty()) {
            // 刚斩掉的正好是最后一项：在结算页也能反悔
            box.addView(Ui.ghost(this, "恢复刚才斩掉的「" + lastBanned.name() + "」", v -> restore()),
                    Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        }
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.addView(box, Ui.lp(Ui.MATCH, Ui.MATCH));
    }

    private TextView center(TextView t) {
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ---------- 界面 ----------

    /**
     * 横屏：左边信息区，右边一列 = 正方形（图片 / 选项）+ 按钮条（右下方）。
     * 竖屏：上面信息区，中间正方形，最下面按钮条。
     */
    private void render() {
        if (finished) return;
        boolean port = portrait();
        root.removeAllViews();
        root.setOrientation(port ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.TOP);
        int p = Ui.dp(this, 12);
        root.setPadding(p, p, p, p);

        LinearLayout info = Ui.col(this);
        if (port) info.setPadding(0, 0, 0, p);
        else info.setPadding(0, 0, p, 0);
        info.addView(header());
        if (lastBanned != null) info.addView(banner(), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 8, 0, 0));
        // 刚选对的那半秒仍显示题面（带“答对”提示），回看已做过的题才显示完整答案
        boolean reveal = cur.done && !advancing;
        if (cur.kana()) {
            if (cur.intro) fillKanaIntro(info);
            else if (reveal) fillKanaReveal(info);
            else fillKanaQuestion(info);
        } else {
            if (cur.intro || reveal) fillDetail(info);
            else fillQuestion(info);
        }

        // 竖屏的新词首学卡：词语居中在屏幕约 1/3 处，插画缩小放在信息区里，例句和记忆点在下面
        if (port && cur.intro && !cur.kana()) {
            root.addView(info, Ui.lpw(Ui.MATCH, 0, 1));
            root.addView(actionBar(), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 10, 0, 0));
            return;
        }

        SquareBox box = new SquareBox(this, port);
        View content;
        if (cur.kana()) content = cur.intro ? kanaCard() : kanaGrid();
        else content = cur.intro ? tile(cur.w, false) : grid();
        box.addView(content, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        if (port) {
            root.addView(info, Ui.lpw(Ui.MATCH, 0, 1));
            root.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP));
            root.addView(actionBar(), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 10, 0, 0));
        } else {
            root.addView(info, Ui.lpw(0, Ui.MATCH, 1));
            // 右列宽度 = 正方形边长 = 屏幕高度减去上下留白和按钮条
            int barH = Ui.dp(this, 44), gap = Ui.dp(this, 8);
            int side = getResources().getDisplayMetrics().heightPixels - 2 * p - barH - gap;
            LinearLayout right = Ui.col(this);
            right.addView(box, Ui.lp(side, side));
            LinearLayout.LayoutParams bl = Ui.lp(side, barH);
            bl.topMargin = gap;
            right.addView(actionBar(), bl);
            root.addView(right, Ui.lp(Ui.WRAP, Ui.MATCH));
        }
    }

    /** 顶部：横竖屏切换（自动旋转开着时不显示）、进度条、计数、退出。 */
    private View header() {
        LinearLayout h = Ui.row(this);
        if (!autoRotate()) {
            boolean port = portrait();
            TextView rot = Ui.tv(this, port ? "⟳ 横屏" : "⟳ 竖屏", 13, Ui.SUB);
            rot.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            rot.setBackground(Ui.outline(this, Ui.CARD, Ui.LINE2, 1, 8));
            rot.setOnClickListener(v -> {
                App.db.set("quiz_orient", port ? "l" : "p");
                applyOrientation();
            });
            h.addView(rot, Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 0, 10, 0));
        }
        int total = total(), done = doneCount();
        float frac = total == 0 ? 0 : Math.min(1f, done / (float) total);
        h.addView(Ui.progress(this, frac, Ui.TEAL_MID), Ui.lpw(0, Ui.dp(this, 6), 1));
        TextView cnt = Ui.tv(this, done + "/" + total, 13, Ui.SUB);
        cnt.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        h.addView(cnt);
        TextView x = Ui.tv(this, "退出", 13, Ui.SUB);
        x.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        x.setBackground(Ui.outline(this, Ui.BG, Ui.LINE2, 1, 8));
        x.setOnClickListener(v -> finish());
        h.addView(x);
        return h;
    }

    private View banner() {
        LinearLayout b = Ui.row(this);
        b.setBackground(Ui.round(this, Ui.AMBER_BG, 8));
        int p = Ui.dp(this, 8);
        b.setPadding(p + p / 2, p / 2, p / 2, p / 2);
        b.addView(Ui.tv(this, "已斩「" + lastBanned.name() + "」", 14, Ui.AMBER), Ui.lpw(0, Ui.WRAP, 1));
        b.addView(Ui.btn(this, "恢复", Ui.AMBER_MID, Ui.AMBER_BG, v -> restore()));
        return b;
    }

    private View hintView(String text) {
        TextView t = Ui.tv(this, text, 13, Ui.RED);
        t.setBackground(Ui.round(this, Ui.RED_BG, 8));
        int p = Ui.dp(this, 8);
        t.setPadding(p, p, p, p);
        t.setMaxLines(4);
        return t;
    }

    /**
     * 按钮条（竖屏在最下方，横屏在右下方）：[上一个] [勘误]（仅单词） [斩] [记住了 / 下一个]。
     * 还没选对的题没有“下一个”，必须先选对。
     */
    private View actionBar() {
        LinearLayout bar = Ui.row(this);
        TextView prev = Ui.ghost(this, "上一个", v -> back());
        if (pos == 0) {
            prev.setTextColor(Ui.LINE2);
            prev.setOnClickListener(null);
        }
        bar.addView(prev);
        bar.addView(Ui.gap(this, 6, 1));
        if (!cur.kana()) {
            Word w = cur.w;
            bar.addView(Ui.ghost(this, Ui.errataLabel(w).replace("已勘误待确认", "待确认").replace("已标勘误", "已标"),
                    v -> Ui.errataDialog(this, w, this::render)));
            bar.addView(Ui.gap(this, 6, 1));
        }
        bar.addView(Ui.btn(this, "斩", Ui.RED_BG, Ui.RED, v -> ban()));
        bar.addView(Ui.gap(this, 6, 1));
        if (cur.intro || (cur.done && !advancing)) {
            String label = cur.intro ? "记住了" : pos == queue.size() - 1 ? "完成" : "下一个";
            bar.addView(Ui.btn(this, label, Ui.TEAL_BG, Ui.TEAL, v -> advance()), Ui.lpw(0, Ui.WRAP, 1));
        } else {
            bar.addView(new View(this), Ui.lpw(0, 1, 1));
        }
        return bar;
    }

    private TextView tag(String s) {
        TextView tag = Ui.tv(this, s, 12, Ui.PURPLE);
        tag.setBackground(Ui.round(this, 0xFF3C3489, 9));
        tag.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        return tag;
    }

    // ----- 单词 -----

    /** 出题状态：词、假名、读音按钮。 */
    private void fillQuestion(LinearLayout info) {
        Word w = cur.w;
        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        TextView word = center(Ui.tv(this, w.expr, Ui.wordSize(w.expr, 46), Ui.TXT));
        Ui.copyable(word, w.expr); // 长按复制
        mid.addView(word, Ui.lp(Ui.MATCH, Ui.WRAP));
        mid.addView(center(Ui.tv(this, w.readingLine(), 16, Ui.SUB)), Ui.lp(Ui.MATCH, Ui.WRAP));
        mid.addView(Ui.btn(this, "读音", Ui.BLUE_BG, Ui.BLUE, v -> App.sound.play(w.id)),
                Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 14, 0, 0));
        if (advancing) {
            mid.addView(okChip(), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        } else if (hint != null) {
            mid.addView(hintView(hint), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 12, 0, 0));
        } else {
            mid.addView(center(Ui.tv(this, cur.retry ? "刚才选错过，再测一次" : "选出这个词的插画", 12, 0xFF888780)),
                    Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        }
        info.addView(mid, Ui.lpw(Ui.MATCH, 0, 1));
    }

    /** 新词首学 / 选对后的揭示：词条信息 + 例句 + 记忆点。 */
    private void fillDetail(LinearLayout info) {
        Word w = cur.w;
        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        body.setPadding(0, Ui.dp(this, 8), 0, 0);
        if (cur.intro) {
            // 首学卡：词语居中，落在屏幕约 1/3 高度处
            int h = getResources().getDisplayMetrics().heightPixels;
            body.addView(new View(this), Ui.lp(1, Math.max(0, h / 3 - Ui.dp(this, 150))));
            LinearLayout.LayoutParams tl = Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 0, 0, 6);
            tl.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(tag("新词"), tl);
            body.addView(Ui.wordHead(this, w, 46));
            if (portrait()) {
                // 竖屏时插画缩小放在这里，给例句和记忆点留位置
                int side = Math.round(h * 0.23f);
                LinearLayout.LayoutParams il = Ui.lp(side, side);
                il.gravity = Gravity.CENTER_HORIZONTAL;
                il.topMargin = Ui.dp(this, 10);
                body.addView(tile(w, false), il);
            }
            body.addView(Ui.wordCards(this, w));
        } else {
            if (cur.wrongs > 0) {
                body.addView(center(Ui.tv(this, "选错了 " + cur.wrongs + " 次，这个词稍后再测", 12, Ui.RED)),
                        Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 0, 0, 6));
            }
            body.addView(Ui.wordInfo(this, w, 34));
        }
        sv.addView(body);
        info.addView(sv, Ui.lpw(Ui.MATCH, 0, 1));
    }

    private View grid() {
        LinearLayout g = Ui.col(this);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = Ui.row(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                View t = i < cur.options.size() ? tile(cur.options.get(i), true) : new View(this);
                line.addView(t, Ui.lpm(this, 0, Ui.MATCH, 1, c == 0 ? 0 : 3, 0, c == 0 ? 3 : 0, 0));
            }
            g.addView(line, Ui.lpm(this, Ui.MATCH, 0, 1, 0, r == 0 ? 0 : 3, 0, r == 0 ? 3 : 0));
        }
        return g;
    }

    /** 一张图片格。选错的标红并写出它是哪个词；揭示后答案标绿、所有格子都写出对应的词。 */
    private View tile(Word o, boolean clickable) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.round(this, Ui.CARD, 8));
        f.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = App.content.image(o);
        if (bm != null) iv.setImageBitmap(bm);
        f.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (!clickable) return f;

        boolean wrong = cur.wrongIds.contains(o.id);
        boolean right = cur.done && o == cur.w;
        if (wrong) iv.setColorFilter(0x99791F1F);
        if (wrong || cur.done) {
            TextView label = Ui.tv(this, (right ? "✓ " : wrong ? "✗ " : "") + o.expr + " · " + o.zh, 13,
                    right ? Ui.GREEN : wrong ? Ui.RED : Ui.TXT);
            label.setBackgroundColor(right ? 0xE627500A : wrong ? 0xE6791F1F : 0xB3000000);
            label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
            label.setMaxLines(1);
            f.addView(label, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM));
        }
        if (right || wrong) {
            f.setForeground(Ui.outline(this, Color.TRANSPARENT, right ? 0xFF97C459 : Ui.RED_MID, 3, 8));
        }
        f.setOnClickListener(v -> onTile(o));
        return f;
    }

    // ----- 假名 -----

    private static String formName(char form) {
        return form == 'h' ? "平假名" : form == 'k' ? "片假名" : "罗马音";
    }

    /** 假名首学：信息区是罗马音和读音按钮；平、片假名大字放在正方形区域里。 */
    private void fillKanaIntro(LinearLayout info) {
        Kana.Entry e = cur.k;
        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        mid.addView(tag("新假名"), Ui.lp(Ui.WRAP, Ui.WRAP));
        mid.addView(center(Ui.tv(this, e.roma, 44, Ui.TXT)), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 8, 0, 0));
        mid.addView(center(Ui.tv(this, "记住这个音的平假名和片假名", 13, Ui.SUB)));
        mid.addView(Ui.btn(this, "读音", Ui.BLUE_BG, Ui.BLUE, v -> App.sound.play("kana_" + e.key)),
                Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 14, 0, 0));
        info.addView(mid, Ui.lpw(Ui.MATCH, 0, 1));
    }

    /** 首学卡的大字：平假名和片假名并排。 */
    private View kanaCard() {
        Kana.Entry e = cur.k;
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.round(this, Ui.CARD, 12));
        float size = e.hira.length() > 1 ? 64 : 96;
        for (char form : new char[]{'h', 'k'}) {
            LinearLayout c = Ui.col(this);
            c.setGravity(Gravity.CENTER);
            c.addView(center(Ui.tv(this, e.text(form), size, Ui.TXT)));
            c.addView(center(Ui.tv(this, formName(form), 13, Ui.SUB)));
            card.addView(c, Ui.lpw(0, Ui.MATCH, 1));
        }
        return card;
    }

    private void fillKanaQuestion(LinearLayout info) {
        Kana.Entry e = cur.k;
        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        mid.addView(center(Ui.tv(this, e.text(cur.promptForm), cur.promptForm == 'r' ? 56 : 76, Ui.TXT)));
        mid.addView(center(Ui.tv(this, (cur.retry ? "刚才选错过 · " : "") + "选出对应的" + formName(cur.optionForm), 14, Ui.SUB)));
        if (cur.optionForm != 'r' || cur.done) { // 选罗马音的题答对前不给“读音”，免得直接听出答案
            mid.addView(Ui.btn(this, "读音", Ui.BLUE_BG, Ui.BLUE, v -> App.sound.play("kana_" + e.key)),
                    Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 14, 0, 0));
        }
        if (advancing) mid.addView(okChip(), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        else if (hint != null) mid.addView(hintView(hint), Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        info.addView(mid, Ui.lpw(Ui.MATCH, 0, 1));
    }

    /** 选对后那半秒显示的提示。 */
    private View okChip() {
        TextView t = Ui.tv(this, "✓ 答对", 14, Ui.GREEN);
        t.setBackground(Ui.round(this, Ui.GREEN_BG, 8));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        return t;
    }

    /** 选对后：三种写法一起显示。 */
    private void fillKanaReveal(LinearLayout info) {
        Kana.Entry e = cur.k;
        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        if (cur.wrongs > 0) mid.addView(center(Ui.tv(this, "选错了 " + cur.wrongs + " 次，这个音稍后再测", 12, Ui.RED)));
        LinearLayout three = Ui.row(this);
        three.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        for (char form : new char[]{'h', 'k', 'r'}) {
            LinearLayout c = Ui.col(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            c.addView(center(Ui.tv(this, e.text(form), form == 'r' ? 38 : 52, Ui.TXT)));
            c.addView(center(Ui.tv(this, formName(form), 12, Ui.SUB)));
            three.addView(c, Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 12, 0, 12, 0));
        }
        mid.addView(three);
        mid.addView(Ui.btn(this, "读音", Ui.BLUE_BG, Ui.BLUE, v -> App.sound.play("kana_" + e.key)),
                Ui.lpm(this, Ui.WRAP, Ui.WRAP, 0, 0, 12, 0, 0));
        info.addView(mid, Ui.lpw(Ui.MATCH, 0, 1));
    }

    private View kanaGrid() {
        LinearLayout g = Ui.col(this);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = Ui.row(this);
            for (int c = 0; c < 2; c++) {
                int i = r * 2 + c;
                View t = i < cur.kOptions.size() ? kanaTile(cur.kOptions.get(i)) : new View(this);
                line.addView(t, Ui.lpm(this, 0, Ui.MATCH, 1, c == 0 ? 0 : 3, 0, c == 0 ? 3 : 0, 0));
            }
            g.addView(line, Ui.lpm(this, Ui.MATCH, 0, 1, 0, r == 0 ? 0 : 3, 0, r == 0 ? 3 : 0));
        }
        return g;
    }

    /** 一个文字选项。选错标红并写出它是什么；揭示后答案标绿，所有选项都写出三种写法，点一下朗读。 */
    private View kanaTile(Kana.Entry o) {
        boolean wrong = cur.wrongIds.contains(o.key);
        boolean right = cur.done && o == cur.k;
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.round(this, right ? Ui.GREEN_BG : wrong ? Ui.RED_BG : Ui.CARD, 8));
        String text = o.text(cur.optionForm);
        float size = cur.optionForm == 'r' ? 40 : text.length() > 1 ? 44 : 60;
        box.addView(center(Ui.tv(this, text, size, Ui.TXT)));
        if (wrong || cur.done) {
            String all = (right ? "✓ " : wrong ? "✗ " : "") + o.hira + " · " + o.kata + " · " + o.roma;
            box.addView(center(Ui.tv(this, all, 13, right ? Ui.GREEN : wrong ? Ui.RED : Ui.SUB)));
        }
        box.setOnClickListener(v -> onKanaTile(o));
        return box;
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.sound.stop();
        if (!finished) saveSession();
        new Thread(() -> App.db.exportToSync()).start();
    }
}
