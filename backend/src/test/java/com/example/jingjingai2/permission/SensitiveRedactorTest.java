package com.example.jingjingai2.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 脱敏规则本身。纯函数，不连库、不碰网络。 */
class SensitiveRedactorTest {

    @Test
    @DisplayName("常见敏感键打星，普通键保留（含嵌套）")
    void redactsCommonKeys() {
        String json = "{\"path\":\"a.txt\",\"token\":\"tok-value\",\"password\":\"pw-value\","
                + "\"apiKey\":\"ak-value\",\"Cookie\":\"sid-value\","
                + "\"nested\":{\"refresh_token\":\"rt-value\"}}";
        String out = SensitiveRedactor.redact(json);
        for (String secret : new String[]{"tok-value", "pw-value", "ak-value", "sid-value", "rt-value"}) {
            assertFalse(out.contains(secret), secret + " 应被打星：" + out);
        }
        assertTrue(out.contains("a.txt"), out);
        assertTrue(out.contains("***"), out);
    }

    @Test
    @DisplayName("键名判定：后缀匹配覆盖带前缀写法，但不误伤 tokenizer")
    void nameMatching() {
        assertTrue(SensitiveRedactor.sensitive("access_token"));
        assertTrue(SensitiveRedactor.sensitive("refresh_token"));
        assertTrue(SensitiveRedactor.sensitive("client_secret"));
        assertTrue(SensitiveRedactor.sensitive("x-api-key"));
        assertTrue(SensitiveRedactor.sensitive("set-cookie"));
        assertTrue(SensitiveRedactor.sensitive("password"));
        assertTrue(SensitiveRedactor.sensitive("Authorization"));
        assertFalse(SensitiveRedactor.sensitive("tokenizer"));
        assertFalse(SensitiveRedactor.sensitive("path"));
        assertFalse(SensitiveRedactor.sensitive(null));
    }

    @Test
    @DisplayName("日志脱敏绝不回退原文：非 JSON / 非法 JSON 只留长度")
    void logNeverFallsBackToRaw() {
        String json = SensitiveRedactor.redactForLog("{\"token\":\"tok-value\",\"path\":\"a.txt\"}");
        assertFalse(json.contains("tok-value"), json);
        assertTrue(json.contains("a.txt"), json);

        String plain = SensitiveRedactor.redactForLog("sk-live-abcdef");
        assertFalse(plain.contains("sk-live"), plain);
        assertTrue(plain.contains("长度"), plain);

        String broken = SensitiveRedactor.redactForLog("{token: \"sk-live-abcdef\"");
        assertFalse(broken.contains("sk-live"), broken);

        assertEquals("", SensitiveRedactor.redactForLog(null));
        assertEquals("", SensitiveRedactor.redactForLog("   "));
    }

    @Test
    @DisplayName("redact() 保持旧语义：非 JSON 原样返回（卡片/审计依赖它）")
    void redactKeepsRawForNonJson() {
        assertEquals("plain text", SensitiveRedactor.redact("plain text"));
        assertNull(SensitiveRedactor.redact(null));
    }

    // ==================== redactText：任意文本（命令 / 模型上报正文 / 日志行） ====================

    @Test
    @DisplayName("redactText：命令行里的 token / 密码 / Key 全部打星，正常内容保留")
    void redactTextMasksCommandLineSecrets() {
        String cmd = "run-deploy --token=FAKE-CLI-TOKEN-9 --password Hunter2-FAKE-pw"
                + " --api-key: FAKE-API-KEY-123456 && echo ok";
        String out = SensitiveRedactor.redactText(cmd);
        for (String secret : new String[]{"FAKE-CLI-TOKEN-9", "Hunter2-FAKE-pw", "FAKE-API-KEY-123456"}) {
            assertFalse(out.contains(secret), secret + " 应被打星：" + out);
        }
        assertTrue(out.contains("run-deploy"), "命令本身要保留，否则看不懂这一步做了什么：" + out);
        assertTrue(out.contains("&& echo ok"), out);
    }

    @Test
    @DisplayName("redactText：Bearer / 环境变量 / 厂商 Key 形状 / 中文键名都覆盖")
    void redactTextMasksCommonShapes() {
        String text = "Authorization: Bearer FAKE-BEARER-1234567890\n"
                + "export OPENAI_API_KEY=sk-test-0000FAKE0000notreal\n"
                + "gh token ghp_FAKE0000000000000000000000000000\n"
                + "密码 Hunter2-FAKE-pw 已更新";
        String out = SensitiveRedactor.redactText(text);
        for (String secret : new String[]{"FAKE-BEARER-1234567890", "sk-test-0000FAKE0000notreal",
                "ghp_FAKE0000000000000000000000000000", "Hunter2-FAKE-pw"}) {
            assertFalse(out.contains(secret), secret + " 应被打星：" + out);
        }
        assertTrue(out.contains("Authorization"), "键名保留，值打星");
        assertTrue(out.contains("已更新"), "中文正文不该被吃掉：" + out);
    }

    @Test
    @DisplayName("redactText：私钥块整段省略；非凭证文本原样返回（不误伤）")
    void redactTextKeepsInnocentTextAndDropsPrivateKey() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEfakefakefake\n-----END RSA PRIVATE KEY-----";
        String out = SensitiveRedactor.redactText("key=\n" + pem + "\ndone");
        assertFalse(out.contains("MIIEfakefakefake"), out);
        assertTrue(out.contains("私钥内容已省略"), out);

        for (String innocent : new String[]{
                "已写入 a.md", "javac -d out src/Main.java", "total=270.0", "exit=0",
                "tokenizer 分词", "max_tokens 4096", "编译通过，测试通过"}) {
            assertEquals(innocent, SensitiveRedactor.redactText(innocent), "不该误伤：" + innocent);
        }
        assertNull(SensitiveRedactor.redactText(null));
    }

    @Test
    @DisplayName("redactText 幂等：二次脱敏结果不变")
    void redactTextIsIdempotent() {
        String once = SensitiveRedactor.redactText("curl -H 'Authorization: Bearer FAKE-BEARER-1234567890' "
                + "--data password=Hunter2-FAKE-pw");
        assertEquals(once, SensitiveRedactor.redactText(once), once);
    }

    // ==================== fingerprint：不可逆、稳定、不撞键 ====================

    @Test
    @DisplayName("fingerprint：同输入同结果、不同输入不同结果、不可逆、null 安全")
    void fingerprintIsStableIrreversibleAndDistinct() {
        String a = "javac -d out src/Main.java";
        String b = "javac -d out src/Other.java";
        assertEquals(SensitiveRedactor.fingerprint(a), SensitiveRedactor.fingerprint(a));
        assertNotEquals(SensitiveRedactor.fingerprint(a), SensitiveRedactor.fingerprint(b));
        assertFalse(SensitiveRedactor.fingerprint(a).contains("javac"), "指纹必须不可逆");
        assertEquals(32, SensitiveRedactor.fingerprint(a).length());
        assertNull(SensitiveRedactor.fingerprint(null));
    }
}
