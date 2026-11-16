package com.affairs.japanesewords;

import android.app.Application;

/** 全局单例：词库、用户数据库、声音。 */
public class App extends Application {
    public static Content content;
    public static Db db;
    public static Sound sound;

    @Override
    public void onCreate() {
        super.onCreate();
        db = new Db(this);
        content = new Content(this);
        sound = new Sound(this);
        content.reload();
    }
}
