package com.affairs.japanesewords;

/**
 * FSRS v4 记忆模型（默认参数）。只用两档评分：一次选对 = Good(3)，选错过 = Again(1)。
 * S = 稳定度（天），D = 难度（1..10），R = 可提取概率。
 */
public final class Fsrs {
    private static final double[] W = {0.4, 0.6, 2.4, 5.8, 4.93, 0.94, 0.86, 0.01, 1.49, 0.14, 0.94,
            2.18, 0.05, 0.34, 1.26, 0.29, 2.61};
    public static final int AGAIN = 1, GOOD = 3;

    private Fsrs() {}

    private static double clampD(double d) {
        return Math.max(1, Math.min(10, d));
    }

    public static double initS(int g) {
        return W[g - 1];
    }

    public static double initD(int g) {
        return clampD(W[4] - (g - 3) * W[5]);
    }

    public static double retrievability(double elapsedDays, double s) {
        return 1.0 / (1.0 + elapsedDays / (9.0 * Math.max(0.01, s)));
    }

    public static double nextD(double d, int g) {
        double nd = d - W[6] * (g - 3);
        return clampD(W[7] * initD(3) + (1 - W[7]) * nd);
    }

    public static double nextS(double d, double s, double r, int g) {
        if (g == AGAIN) {
            double f = W[11] * Math.pow(d, -W[12]) * (Math.pow(s + 1, W[13]) - 1) * Math.exp(W[14] * (1 - r));
            return Math.max(0.1, Math.min(s, f));
        }
        return s * (1 + Math.exp(W[8]) * (11 - d) * Math.pow(s, -W[9]) * (Math.exp(W[10] * (1 - r)) - 1));
    }

    /** 下次复习间隔（天）。target 越高，间隔越短，这个词出现得越频繁。 */
    public static int interval(double s, double target) {
        return Math.max(1, (int) Math.round(9.0 * s * (1.0 / target - 1.0)));
    }

    /**
     * 每个词的目标记忆率不同：越基础常用（freq 高）、错得越多（lapses 多）的词目标越高，
     * 于是复习间隔更短、出现概率更大。
     */
    public static double targetRetention(int freq, int lapses) {
        double t = 0.86 + 0.015 * Math.max(1, Math.min(5, freq)) + Math.min(0.04, 0.01 * lapses);
        return Math.min(0.96, t);
    }
}
