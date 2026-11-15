package com.example.jingjingai2.context;

/**
 * 轻量 token 估算器（零依赖）。
 *
 * 为什么不用精确 tokenizer：
 *  - 精确计数需要引入 JTokkit 之类的分词器（本机 Maven 损坏，无法新增依赖）；
 *  - 这里只用于"是否超预算、要不要压缩"的粗判断，不要求精确到个位。
 *
 * 估算规则（启发式，够用）：
 *  - 中文（CJK）按 1 字 ≈ 1 token（多数主流 tokenizer 对中文大致如此）；
 *  - 其他字符（英文/数字/标点）按 4 字符 ≈ 1 token。
 * 注释里诚实说明：若要精确计数，替换为真实 tokenizer 即可，本类的调用方无需改动。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    private static boolean isCJK(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}
