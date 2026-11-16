package com.affairs.japanesewords;

/** 一个词条（来自内置词表、同步文件夹里的补充包，或用户自己添加）。 */
public class Word {
    public String id = "";
    public String level = "";      // N5 … N1；自添词为 "自添"
    public String expr = "";       // 日文写法
    public String reading = "";    // 假名
    public String romaji = "";
    public String pos = "";        // 词性
    public String zh = "";         // 中文释义
    public String en = "";         // Tanos 原英文释义
    public String exJa = "";       // 例句
    public String exZh = "";
    public String exSrc = "";      // 例句来源：tatoeba / ai
    public String mnemonic = "";   // 记忆点
    public String grp = "";        // 互斥组：同组的词（如 青 / 青い）插画相近，不互相当干扰项
    public int freq = 3;           // 1..5，越大越基础、越常用
    public boolean custom;         // 用户自己添加的
    public boolean hasImg;
    public boolean hasAudio;

    /** 素材齐全（释义、插画、读音）才参与背诵。 */
    public boolean ready() {
        return hasImg && hasAudio && zh != null && !zh.isEmpty();
    }

    public String readingLine() {
        if (romaji == null || romaji.isEmpty()) return reading;
        return reading + " · " + romaji;
    }
}
