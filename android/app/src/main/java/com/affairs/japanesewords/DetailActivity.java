package com.affairs.japanesewords;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 词条详情（竖屏）：词表里点开一个词。可以朗读、勘误、斩/恢复，以及从相册换掉不合适的插画。 */
public class DetailActivity extends Activity {
    public static final String EXTRA_ID = "id";
    private static final int REQ_PICK = 11;
    private Word w;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        w = App.content.get(getIntent().getStringExtra(EXTRA_ID));
        if (w == null) {
            finish();
            return;
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFitsSystemWindows(true);
        root = Ui.col(this);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
        render();
        if (w.hasAudio && "1".equals(App.db.get("auto_read", "1"))) App.sound.play(w.id);
    }

    private void render() {
        root.removeAllViews();
        // 插画 + 右上角“换图”
        FrameLayout pic = new FrameLayout(this);
        pic.setBackground(Ui.round(this, Ui.CARD, 10));
        pic.setClipToOutline(true);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = App.content.image(w);
        if (bm != null) iv.setImageBitmap(bm);
        pic.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (bm == null) {
            TextView none = Ui.tv(this, "还没有插画", 14, Ui.SUB);
            none.setGravity(Gravity.CENTER);
            pic.addView(none, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        LinearLayout tools = Ui.row(this);
        tools.addView(Ui.btn(this, "换图", Ui.BLUE_BG, Ui.BLUE, v -> pick()));
        if (App.content.hasUserImage(w.id)) {
            tools.addView(Ui.gap(this, 6, 1));
            tools.addView(Ui.ghost(this, "恢复原图", v -> {
                App.content.setUserImage(w, null);
                render();
            }));
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.END);
        tp.setMargins(0, Ui.dp(this, 8), Ui.dp(this, 8), 0);
        pic.addView(tools, tp);
        int side = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
        root.addView(pic, Ui.lp(Ui.MATCH, Math.round(side * 0.8f)));

        // 状态行
        Db.P p = App.db.p(w.id);
        String st;
        if (!w.ready()) st = "待补素材，暂不参与背诵（电脑端补全后自动加入）";
        else if (p != null && p.banned) st = "已斩，不再出现";
        else if (p != null && p.learned()) {
            long d = p.due - Db.today();
            st = "已学 " + p.reps + " 次，错 " + p.lapses + " 次 · " + (d <= 0 ? "今天该复习" : d + " 天后复习");
        } else if (p != null && p.seen()) st = "已学（看过首学卡，还没测过）";
        else st = "还没学";
        root.addView(Ui.tv(this, w.level + " · " + st, 12, Ui.SUB), Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 10, 0, 6));

        root.addView(Ui.wordInfo(this, w, 34));

        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.ghost(this, Ui.errataLabel(w), v -> Ui.errataDialog(this, w, this::render)));
        bar.addView(Ui.gap(this, 8, 1));
        if (p != null && p.banned) {
            bar.addView(Ui.btn(this, "恢复", Ui.AMBER_BG, Ui.AMBER, v -> {
                App.db.ban(w.id, false);
                render();
            }));
        } else {
            bar.addView(Ui.btn(this, "斩", Ui.RED_BG, Ui.RED, v -> {
                App.db.ban(w.id, true);
                App.sound.fxBan();
                render();
            }));
        }
        bar.addView(Ui.gap(this, 8, 1));
        if (w.custom && !w.ready()) {
            bar.addView(Ui.ghost(this, "删除", v -> new AlertDialog.Builder(this)
                    .setMessage("删除自己添加的「" + w.expr + "」？")
                    .setPositiveButton("删除", (d, i) -> {
                        App.db.deleteCustom(w.id);
                        App.content.reload();
                        finish();
                    }).setNegativeButton("取消", null).show()));
            bar.addView(Ui.gap(this, 8, 1));
        }
        bar.addView(Ui.btn(this, "返回", Ui.TEAL_BG, Ui.TEAL, v -> finish()), Ui.lpw(0, Ui.WRAP, 1));
        root.addView(bar, Ui.lpm(this, Ui.MATCH, Ui.WRAP, 0, 0, 16, 0, 0));
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "选一张图片"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            // 先只读尺寸，按比例缩小解码，再居中裁成 512 的正方形存进数据库
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= 512) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                src = BitmapFactory.decodeStream(in, null, o2);
            }
            if (src == null) throw new Exception("decode");
            int m = Math.min(src.getWidth(), src.getHeight());
            Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - m) / 2, (src.getHeight() - m) / 2, m, m);
            Bitmap out = m > 512 ? Bitmap.createScaledBitmap(sq, 512, 512, true) : sq;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            App.content.setUserImage(w, bo.toByteArray());
            Ui.toast(this, "已换图");
            render();
        } catch (Exception e) {
            Ui.toast(this, "这张图片读不了，换一张试试");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.sound.stop();
        new Thread(() -> App.db.exportToSync()).start();
    }
}
