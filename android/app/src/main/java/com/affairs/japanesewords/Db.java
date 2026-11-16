package com.affairs.japanesewords;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全部用户数据都在这一个 SQLite 文件里：学习进度、答题记录、打卡、设置、勘误、自添词、自己换的图片。
 * 工作副本在应用内部；每次学习后整文件复制到同步文件夹（见 {@link #exportToSync()}）。
 */
public class Db extends SQLiteOpenHelper {
    public static final String NAME = "user.db";
    public static final String SYNC_DIR =
            Environment.getExternalStorageDirectory() + "/1/SyncProjects/JapaneseWords";

    /** 一个词的学习进度。 */
    public static class P {
        public String id;
        public double s, d;
        public long due, last, firstDay;
        public int reps, lapses;
        public boolean banned;

        /** 已经答过题（有记忆数据）。 */
        public boolean learned() {
            return reps > 0;
        }

        /** 已学：看过首学卡点了“记住了”，或已经答过题。词表、五十音图、进度统计都按这个算，点完立刻生效。 */
        public boolean seen() {
            return reps > 0 || firstDay > 0;
        }
    }

    private final Context ctx;
    private final HashMap<String, P> prog = new HashMap<>();
    private boolean loaded;

    public Db(Context c) {
        super(c.getApplicationContext(), NAME, null, 3);
        ctx = c.getApplicationContext();
        // 不用 WAL，保证数据都在 user.db 这一个文件里，复制出去就是完整的
        setWriteAheadLoggingEnabled(false);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE progress(word_id TEXT PRIMARY KEY, stability REAL, difficulty REAL,"
                + " due INTEGER, last INTEGER, first_day INTEGER, reps INTEGER DEFAULT 0,"
                + " lapses INTEGER DEFAULT 0, banned INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, word_id TEXT, ts INTEGER,"
                + " day INTEGER, first_try INTEGER, wrongs INTEGER, is_new INTEGER)");
        db.execSQL("CREATE TABLE checkin(day INTEGER PRIMARY KEY, new_cnt INTEGER DEFAULT 0,"
                + " review_cnt INTEGER DEFAULT 0, done INTEGER DEFAULT 0, kana_cnt INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE errata(id INTEGER PRIMARY KEY AUTOINCREMENT, word_id TEXT, expr TEXT,"
                + " ts INTEGER, type TEXT, note TEXT, status TEXT DEFAULT 'open')");
        db.execSQL("CREATE TABLE custom_words(id TEXT PRIMARY KEY, expr TEXT, reading TEXT, zh TEXT,"
                + " ex_ja TEXT, ex_zh TEXT, mnemonic TEXT, ts INTEGER)");
        db.execSQL("CREATE TABLE user_images(word_id TEXT PRIMARY KEY, data BLOB, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v2：五十音加入日常学习，打卡表多记一列当天的假名答题数
        if (oldV < 2) db.execSQL("ALTER TABLE checkin ADD COLUMN kana_cnt INTEGER DEFAULT 0");
        // v3：清音 / 浊音换成真人录音，五十音从头学：清空全部假名进度（含已斩）和答题记录，
        // 今天的假名计数归零，学到一半的一轮也丢掉（里面可能有假名卡片）
        if (oldV < 3) {
            db.execSQL("DELETE FROM progress WHERE word_id LIKE 'kh!_%' ESCAPE '!' OR word_id LIKE 'kk!_%' ESCAPE '!'");
            db.execSQL("DELETE FROM log WHERE word_id LIKE 'kh!_%' ESCAPE '!' OR word_id LIKE 'kk!_%' ESCAPE '!'");
            db.execSQL("UPDATE checkin SET kana_cnt=0 WHERE day=" + today());
            db.execSQL("DELETE FROM settings WHERE k='session'");
        }
    }

    /** 假名的进度 id：kh_<key>（平假名）/ kk_<key>（片假名）。其余都是单词。 */
    public static boolean isKana(String id) {
        return id.startsWith("kh_") || id.startsWith("kk_");
    }

    /** “今天”以凌晨 4 点为界：半夜学到零点以后仍算前一天，不会打断当晚没学完的那一轮。 */
    public static long today() {
        return java.time.LocalDateTime.now().minusHours(4).toLocalDate().toEpochDay();
    }

    /** 今天有没有学到一半的一轮（学习页退出时存下的）。 */
    public boolean hasSession() {
        String s = get("session", "");
        return !s.isEmpty() && s.contains("\"day\":" + today() + ",");
    }

    // ---------- 设置 ----------

    public String get(String k, String def) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM settings WHERE k=?", new String[]{k})) {
            return c.moveToFirst() ? c.getString(0) : def;
        }
    }

    public int getInt(String k, int def) {
        try {
            return Integer.parseInt(get(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void set(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------- 进度 ----------

    private synchronized void load() {
        if (loaded) return;
        prog.clear();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT word_id, stability, difficulty, due, last, first_day, reps, lapses, banned FROM progress",
                null)) {
            while (c.moveToNext()) {
                P p = new P();
                p.id = c.getString(0);
                p.s = c.getDouble(1);
                p.d = c.getDouble(2);
                p.due = c.getLong(3);
                p.last = c.getLong(4);
                p.firstDay = c.getLong(5);
                p.reps = c.getInt(6);
                p.lapses = c.getInt(7);
                p.banned = c.getInt(8) != 0;
                prog.put(p.id, p);
            }
        }
        loaded = true;
    }

    /** 没学过也没斩过的词返回 null。 */
    public P p(String id) {
        load();
        return prog.get(id);
    }

    public boolean banned(String id) {
        P p = p(id);
        return p != null && p.banned;
    }

    public boolean learned(String id) {
        P p = p(id);
        return p != null && p.learned();
    }

    public boolean seen(String id) {
        P p = p(id);
        return p != null && p.seen();
    }

    /**
     * 首学卡点了“记住了”：立刻记为已学（还没有记忆数据，等第一次答题时再建）。
     * 这样词表和五十音图马上更新；就算这时退出，它也会作为“待复习”再出现，不会丢。
     */
    public void introduce(String id) {
        load();
        P p = prog.get(id);
        if (p == null) {
            p = new P();
            p.id = id;
            prog.put(id, p);
        }
        if (p.seen()) return;
        p.firstDay = today();
        save(p);
    }

    private void save(P p) {
        ContentValues cv = new ContentValues();
        cv.put("word_id", p.id);
        cv.put("stability", p.s);
        cv.put("difficulty", p.d);
        cv.put("due", p.due);
        cv.put("last", p.last);
        cv.put("first_day", p.firstDay);
        cv.put("reps", p.reps);
        cv.put("lapses", p.lapses);
        cv.put("banned", p.banned ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 记录一次答题并排下次复习。firstTry = 一次就选对。 */
    public void review(Word w, boolean firstTry, int wrongs) {
        review(w.id, w.freq, firstTry, wrongs);
    }

    /** 同上，按进度 id 记录（单词或假名）。freq 是常用度 1..5，假名按最常用算。 */
    public void review(String id, int freq, boolean firstTry, int wrongs) {
        load();
        long t = today();
        boolean kana = isKana(id);
        P p = prog.get(id);
        boolean isNew = p == null || !p.learned();
        int g = firstTry ? Fsrs.GOOD : Fsrs.AGAIN;
        if (p == null) {
            p = new P();
            p.id = id;
            prog.put(p.id, p);
        }
        if (isNew) {
            p.s = Fsrs.initS(g);
            p.d = Fsrs.initD(g);
            if (p.firstDay == 0) p.firstDay = t;
        } else {
            double r = Fsrs.retrievability(Math.max(0, t - p.last), p.s);
            double ns = Fsrs.nextS(p.d, p.s, r, g);
            p.d = Fsrs.nextD(p.d, g);
            p.s = ns;
        }
        if (!firstTry) p.lapses++;
        p.reps++;
        p.last = t;
        p.due = t + Fsrs.interval(p.s, Fsrs.targetRetention(freq, p.lapses));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            save(p);
            ContentValues cv = new ContentValues();
            cv.put("word_id", id);
            cv.put("ts", System.currentTimeMillis());
            cv.put("day", t);
            cv.put("first_try", firstTry ? 1 : 0);
            cv.put("wrongs", wrongs);
            cv.put("is_new", isNew ? 1 : 0);
            db.insert("log", null, cv);
            db.execSQL("INSERT OR IGNORE INTO checkin(day) VALUES(?)", new Object[]{t});
            db.execSQL("UPDATE checkin SET "
                    + (kana ? "kana_cnt=kana_cnt+1" : isNew ? "new_cnt=new_cnt+1" : "review_cnt=review_cnt+1")
                    + " WHERE day=?", new Object[]{t});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 斩 / 恢复。 */
    public void ban(String id, boolean banned) {
        load();
        P p = prog.get(id);
        if (p == null) {
            if (!banned) return;
            p = new P();
            p.id = id;
            prog.put(id, p);
        }
        p.banned = banned;
        save(p);
    }

    /** 今天新学了多少词。 */
    public int newToday() {
        load();
        long t = today();
        int n = 0;
        for (P p : prog.values()) if (p.seen() && p.firstDay == t && !isKana(p.id)) n++;
        return n;
    }

    /** 今天新学了多少个音（以平假名那条进度为准）。 */
    public int kanaNewToday() {
        load();
        long t = today();
        int n = 0;
        for (P p : prog.values()) if (p.seen() && p.firstDay == t && p.id.startsWith("kh_")) n++;
        return n;
    }

    public void markDone(long day) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO checkin(day) VALUES(?)", new Object[]{day});
        db.execSQL("UPDATE checkin SET done=1 WHERE day=?", new Object[]{day});
    }

    /** 打卡记录：day → 1 学过 / 2 完成当日计划。 */
    public Map<Long, Integer> checkins() {
        HashMap<Long, Integer> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day, new_cnt + kana_cnt, review_cnt, done FROM checkin", null)) {
            while (c.moveToNext()) {
                if (c.getInt(3) != 0) m.put(c.getLong(0), 2);
                else if (c.getInt(1) + c.getInt(2) > 0) m.put(c.getLong(0), 1);
            }
        }
        return m;
    }

    /** 某天的 [新词数, 单词复习数, 假名答题数]。 */
    public int[] dayCounts(long day) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT new_cnt, review_cnt, kana_cnt FROM checkin WHERE day=?", new String[]{String.valueOf(day)})) {
            if (c.moveToFirst()) return new int[]{c.getInt(0), c.getInt(1), c.getInt(2)};
        }
        return new int[]{0, 0, 0};
    }

    /** 最近 days 天复习的一次选对率；没有复习记录时用全部记录；仍没有则返回 -1。 */
    public double accuracy(int days) {
        long from = today() - days + 1;
        SQLiteDatabase db = getReadableDatabase();
        // 只算单词，不算假名
        String words = " AND substr(word_id,1,3) NOT IN ('kh_','kk_')";
        try (Cursor c = db.rawQuery("SELECT COUNT(*), SUM(first_try) FROM log WHERE day>=? AND is_new=0" + words,
                new String[]{String.valueOf(from)})) {
            if (c.moveToFirst() && c.getInt(0) >= 5) return c.getDouble(1) / c.getInt(0);
        }
        try (Cursor c = db.rawQuery("SELECT COUNT(*), SUM(first_try) FROM log WHERE day>=?" + words,
                new String[]{String.valueOf(from)})) {
            if (c.moveToFirst() && c.getInt(0) > 0) return c.getDouble(1) / c.getInt(0);
        }
        return -1;
    }

    // ---------- 勘误 ----------

    public void addErrata(Word w, String type, String note) {
        ContentValues cv = new ContentValues();
        cv.put("word_id", w.id);
        cv.put("expr", w.expr);
        cv.put("ts", System.currentTimeMillis());
        cv.put("type", type);
        cv.put("note", note);
        getWritableDatabase().insert("errata", null, cv);
    }

    public Set<String> openErrata() {
        HashSet<String> s = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT word_id FROM errata WHERE status='open'", null)) {
            while (c.moveToNext()) s.add(c.getString(0));
        }
        return s;
    }

    /** 电脑端已更正、等用户确认的勘误所涉及的词。 */
    public Set<String> fixedErrata() {
        HashSet<String> s = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT word_id FROM errata WHERE status='fixed'", null)) {
            while (c.moveToNext()) s.add(c.getString(0));
        }
        return s;
    }

    /** 某个词待确认的勘误说明（类型：备注），每条一行。 */
    public String fixedErrataText(String wordId) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT type, note FROM errata WHERE word_id=? AND status='fixed' ORDER BY id", new String[]{wordId})) {
            while (c.moveToNext()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.getString(0));
                String note = c.getString(1);
                if (note != null && !note.isEmpty()) sb.append("：").append(note);
            }
        }
        return sb.toString();
    }

    /**
     * 电脑端处理完一条勘误后，通过同步文件夹告诉手机（content/errata_done.json）：
     * 把它从“已标勘误”改成“已勘误待确认”。ts 一起核对，防止重装后 id 对不上。
     */
    public void markErrataFixed(long id, long ts) {
        getWritableDatabase().execSQL("UPDATE errata SET status='fixed' WHERE id=? AND ts=? AND status='open'",
                new Object[]{id, ts});
    }

    /** 用户对“已勘误待确认”的答复：确认改好了（closed），或还是不对（rejected，随后可重新标记）。 */
    public void resolveFixedErrata(String wordId, boolean ok) {
        getWritableDatabase().execSQL("UPDATE errata SET status=? WHERE word_id=? AND status='fixed'",
                new Object[]{ok ? "closed" : "rejected", wordId});
    }

    public void clearErrata(String wordId) {
        getWritableDatabase().execSQL("UPDATE errata SET status='withdrawn' WHERE word_id=? AND status='open'",
                new Object[]{wordId});
    }

    // ---------- 自添词 ----------

    public String addCustom(String expr, String reading, String zh, String exJa, String exZh, String mn) {
        String id = "c_" + System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("expr", expr);
        cv.put("reading", reading);
        cv.put("zh", zh);
        cv.put("ex_ja", exJa);
        cv.put("ex_zh", exZh);
        cv.put("mnemonic", mn);
        cv.put("ts", System.currentTimeMillis());
        getWritableDatabase().insert("custom_words", null, cv);
        return id;
    }

    public void deleteCustom(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("custom_words", "id=?", new String[]{id});
        db.delete("progress", "word_id=?", new String[]{id});
        db.delete("user_images", "word_id=?", new String[]{id});
        loaded = false;
    }

    public List<Word> customWords() {
        ArrayList<Word> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, expr, reading, zh, ex_ja, ex_zh, mnemonic FROM custom_words ORDER BY ts", null)) {
            while (c.moveToNext()) {
                Word w = new Word();
                w.id = c.getString(0);
                w.expr = c.getString(1);
                w.reading = nz(c.getString(2));
                w.zh = nz(c.getString(3));
                w.exJa = nz(c.getString(4));
                w.exZh = nz(c.getString(5));
                w.mnemonic = nz(c.getString(6));
                w.level = "自添";
                w.custom = true;
                w.freq = 4;
                out.add(w);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------- 自己换的图片 ----------

    public byte[] userImage(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT data FROM user_images WHERE word_id=?",
                new String[]{id})) {
            return c.moveToFirst() ? c.getBlob(0) : null;
        }
    }

    public void setUserImage(String id, byte[] data) {
        SQLiteDatabase db = getWritableDatabase();
        if (data == null) {
            db.delete("user_images", "word_id=?", new String[]{id});
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put("word_id", id);
        cv.put("data", data);
        cv.put("ts", System.currentTimeMillis());
        db.insertWithOnConflict("user_images", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Set<String> userImageIds() {
        HashSet<String> s = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT word_id FROM user_images", null)) {
            while (c.moveToNext()) s.add(c.getString(0));
        }
        return s;
    }

    // ---------- 同步文件夹 ----------

    public boolean canSync() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static File syncFile() {
        return new File(SYNC_DIR, NAME);
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 把数据库整文件复制到同步文件夹。先写临时文件再改名，避免 Syncthing 同步到半截文件。 */
    public synchronized boolean exportToSync() {
        if (!canSync()) return false;
        try {
            File dir = new File(SYNC_DIR);
            if (!dir.exists() && !dir.mkdirs()) return false;
            getWritableDatabase(); // 确保文件已建好
            File tmp = new File(dir, NAME + ".tmp");
            copy(ctx.getDatabasePath(NAME), tmp);
            File dst = syncFile();
            if (dst.exists() && !dst.delete()) return false;
            if (!tmp.renameTo(dst)) return false;
            set("last_sync", String.valueOf(System.currentTimeMillis()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用同步文件夹里的数据库覆盖本机数据（换手机 / 重装后恢复）。 */
    public synchronized boolean importFromSync() {
        File src = syncFile();
        if (!canSync() || !src.exists()) return false;
        try {
            close();
            copy(src, ctx.getDatabasePath(NAME));
            loaded = false;
            getWritableDatabase();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
