package com.affairs.japanesewords;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.SoundPool;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 所有声音都走“媒体”通道。
 * 静音规则：媒体音量为 0 → 不出声；连着耳机（有线 / 蓝牙 / USB）时只从耳机播放（系统自动只走耳机），不管外放开关；
 * 没连耳机时，设置里打开“允许外放”（speaker_on）才外放，不管手机的静音 / 振动模式。
 */
public class Sound {
    private final Context ctx;
    private final AudioManager am;
    private final SoundPool pool;
    private final int fxOk, fxBad, fxBan;
    private MediaPlayer mp;
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    public Sound(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(aa).build();
        fxOk = pool.load(ctx, R.raw.fx_ok, 1);
        fxBad = pool.load(ctx, R.raw.fx_bad, 1);
        fxBan = pool.load(ctx, R.raw.fx_ban, 1);
    }

    private boolean headset() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    return true;
                default:
            }
        }
        return false;
    }

    public boolean allowed() {
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return false;
        if (headset()) return true;
        // 没连耳机：打开“允许外放”才出声（与手机静音 / 振动模式无关）
        return "1".equals(App.db.get("speaker_on", "0"));
    }

    /** 选对 / 选错的短提示音。 */
    public void fx(boolean ok) {
        if (!allowed() || !"1".equals(App.db.get("fx_sound", "1"))) return;
        pool.play(ok ? fxOk : fxBad, 0.6f, 0.6f, 1, 0, 1f);
    }

    /** 斩的刀光音效（跟随“选对 / 选错提示音”开关）。 */
    public void fxBan() {
        if (!allowed() || !"1".equals(App.db.get("fx_sound", "1"))) return;
        pool.play(fxBan, 0.8f, 0.8f, 1, 0, 1f);
    }

    /** 依次播放若干段音频（文件名不带扩展名，如 n5_0001、n5_0001_s）。 */
    public void play(String... names) {
        stop();
        if (!allowed()) return;
        queue.addAll(Arrays.asList(names));
        next();
    }

    private void next() {
        while (!queue.isEmpty()) {
            String name = queue.poll();
            try {
                MediaPlayer p = new MediaPlayer();
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                File f = App.content.packAudio(name);
                if (f != null) {
                    p.setDataSource(f.getAbsolutePath());
                } else if (App.content.hasAssetAudio(name)) {
                    AssetFileDescriptor fd = ctx.getAssets().openFd("audio/" + name + ".mp3");
                    p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                    fd.close();
                } else {
                    p.release();
                    continue;
                }
                p.setOnCompletionListener(m -> {
                    m.release();
                    if (mp == m) mp = null;
                    next();
                });
                p.prepare();
                p.start();
                mp = p;
                return;
            } catch (Exception ignored) {
                // 这一段播不了就跳到下一段
            }
        }
    }

    public void stop() {
        queue.clear();
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }
}
