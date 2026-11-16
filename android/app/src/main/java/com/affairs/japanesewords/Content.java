package com.affairs.japanesewords;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * 词库 = APK 内置词表（assets/words.json + img/ + audio/）
 *      + 同步文件夹里的补充包（content/words.json + img/ + audio/，电脑端生成，可覆盖内置词条、补全自添词）
 *      + 用户数据库里的自添词和自己换的图片。
 */
public class Content {
    private final Context ctx;
    private final LinkedHashMap<String, Word> map = new LinkedHashMap<>();
    private final Set<String> assetImg = new HashSet<>();
    private final Set<String> assetAudio = new HashSet<>();
    private Set<String> userImg = new HashSet<>();
    private final File packDir = new File(Db.SYNC_DIR, "content");
    private final LruCache<String, Bitmap> cache = new LruCache<>(48);
    private static final String[] IMG_EXT = {".webp", ".jpg", ".png"};

    public Content(Context c) {
        ctx = c.getApplicationContext();
    }

    public synchronized void reload() {
        map.clear();
        cache.evictAll();
        assetImg.clear();
        assetAudio.clear();
        try {
            String[] a = ctx.getAssets().list("img");
            if (a != null) assetImg.addAll(Arrays.asList(a));
            a = ctx.getAssets().list("audio");
            if (a != null) assetAudio.addAll(Arrays.asList(a));
        } catch (Exception ignored) {
        }
        try (InputStream in = ctx.getAssets().open("words.json")) {
            parse(readAll(in));
        } catch (Exception ignored) {
        }
        // 用户自添词（素材要等电脑端补全）
        for (Word w : App.db.customWords()) map.put(w.id, w);
        // 同步文件夹里的补充包：同 id 覆盖
        File pack = new File(packDir, "words.json");
        if (App.db.canSync() && pack.exists()) {
            try (InputStream in = new FileInputStream(pack)) {
                parse(readAll(in));
            } catch (Exception ignored) {
            }
        }
        // 电脑端已处理的勘误：[{"id":1,"ts":1794600303861}, …] → 标成“已勘误待确认”
        File done = new File(packDir, "errata_done.json");
        if (App.db.canSync() && done.exists()) {
            try (InputStream in = new FileInputStream(done)) {
                JSONArray arr = new JSONArray(readAll(in));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    App.db.markErrataFixed(o.getLong("id"), o.getLong("ts"));
                }
            } catch (Exception ignored) {
            }
        }
        userImg = App.db.userImageIds();
        for (Word w : map.values()) refreshAssets(w);
    }

    public void refreshAssets(Word w) {
        w.hasImg = userImg.contains(w.id) || packImage(w.id) != null || assetImage(w.id) != null;
        w.hasAudio = assetAudio.contains(w.id + ".mp3") || packAudio(w.id) != null;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private void parse(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            Word w = map.get(id);
            if (w == null) {
                w = new Word();
                w.id = id;
                w.custom = id.startsWith("c_");
                map.put(id, w);
            }
            w.level = o.optString("level", w.level);
            w.expr = o.optString("expr", w.expr);
            w.reading = o.optString("reading", w.reading);
            w.romaji = o.optString("romaji", w.romaji);
            w.pos = o.optString("pos", w.pos);
            w.zh = o.optString("zh", w.zh);
            w.en = o.optString("en", w.en);
            w.exJa = o.optString("ex_ja", w.exJa);
            w.exZh = o.optString("ex_zh", w.exZh);
            w.exSrc = o.optString("ex_src", w.exSrc);
            w.mnemonic = o.optString("mn", w.mnemonic);
            w.freq = o.optInt("freq", w.freq);
            w.grp = o.optString("grp", w.grp);
            if (w.custom) w.level = "自添";
        }
    }

    public Word get(String id) {
        return map.get(id);
    }

    public List<Word> all() {
        return new ArrayList<>(map.values());
    }

    /** 素材齐全、可以背的词。 */
    public List<Word> ready() {
        ArrayList<Word> out = new ArrayList<>();
        for (Word w : map.values()) if (w.ready()) out.add(w);
        return out;
    }

    public List<String> levels() {
        ArrayList<String> out = new ArrayList<>();
        for (Word w : map.values()) if (!w.custom && !out.contains(w.level)) out.add(w.level);
        return out;
    }

    // ---------- 素材 ----------

    private String assetImage(String id) {
        for (String e : IMG_EXT) if (assetImg.contains(id + e)) return "img/" + id + e;
        return null;
    }

    private File packImage(String id) {
        if (!packDir.exists()) return null;
        for (String e : IMG_EXT) {
            File f = new File(packDir, "img/" + id + e);
            if (f.exists()) return f;
        }
        return null;
    }

    public File packAudio(String name) {
        File f = new File(packDir, "audio/" + name + ".mp3");
        return f.exists() ? f : null;
    }

    public boolean hasAssetAudio(String name) {
        return assetAudio.contains(name + ".mp3");
    }

    public boolean hasSentenceAudio(Word w) {
        return hasAssetAudio(w.id + "_s") || packAudio(w.id + "_s") != null;
    }

    public boolean hasUserImage(String id) {
        return userImg.contains(id);
    }

    /** 优先级：用户自己换的图 > 同步文件夹补充包 > 内置插画。 */
    public Bitmap image(Word w) {
        Bitmap b = cache.get(w.id);
        if (b != null) return b;
        try {
            if (userImg.contains(w.id)) {
                byte[] d = App.db.userImage(w.id);
                if (d != null) b = BitmapFactory.decodeByteArray(d, 0, d.length);
            }
            if (b == null) {
                File f = packImage(w.id);
                if (f != null) b = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
            if (b == null) {
                String a = assetImage(w.id);
                if (a != null) {
                    try (InputStream in = ctx.getAssets().open(a)) {
                        b = BitmapFactory.decodeStream(in);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (b != null) cache.put(w.id, b);
        return b;
    }

    public void setUserImage(Word w, byte[] jpeg) {
        App.db.setUserImage(w.id, jpeg);
        if (jpeg == null) userImg.remove(w.id);
        else userImg.add(w.id);
        cache.remove(w.id);
        refreshAssets(w);
    }
}
