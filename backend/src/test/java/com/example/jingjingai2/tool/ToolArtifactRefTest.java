package com.example.jingjingai2.tool;

import com.example.jingjingai2.security.DesktopMcpKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 机器引用的编码/签名/解析：伪造、篡改、缺失、重启后的行为。 */
class ToolArtifactRefTest {

    private static final byte[] KEY_A = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_B = "fedcba9876543210fedcba9876543210"
            .getBytes(StandardCharsets.UTF_8);
    private static final String DESKTOP_MATERIAL = "0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() {
        ToolArtifactRef.configure(KEY_A);
    }

    @AfterEach
    void tearDown() {
        // 其他测试类共用同一个静态密钥；恢复成固定测试密钥，避免顺序耦合。
        ToolArtifactRef.configure(KEY_A);
    }

    @Test
    @DisplayName("Windows 路径 + 空格 + 中文：marker/parse 往返一致")
    void roundTripsWindowsPathWithSpacesAndChinese() {
        String path = "C:\\Users\\测试 用户\\mcp-output\\conv-1\\read_item-1-abc.txt";
        String marker = ToolArtifactRef.marker(path, 12345, "read_item");
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse("prefix\n" + marker + "\nsuffix");
        assertNotNull(ref);
        assertEquals(path, ref.path());
        assertEquals(12345, ref.chars());
        assertEquals("read_item", ref.tool());
    }

    @Test
    @DisplayName("多个 marker 时取最后一个（裁剪后尾部标记仍可见）")
    void parseTakesLastMarker() {
        String a = ToolArtifactRef.marker("C:\\a.txt", 1, "a");
        String b = ToolArtifactRef.marker("C:\\b.txt", 2, "b");
        ToolArtifactRef.Ref ref = ToolArtifactRef.parse(a + "\n" + b);
        assertNotNull(ref);
        assertEquals("C:\\b.txt", ref.path());
        assertEquals(2, ref.chars());
    }

    @Test
    @DisplayName("没有 marker / null 文本返回 null")
    void noMarkerReturnsNull() {
        assertNull(ToolArtifactRef.parse("just text"));
        assertNull(ToolArtifactRef.parse(null));
        assertNull(ToolArtifactRef.parse(""));
    }

    @Test
    @DisplayName("无签名的旧格式一律拒绝，不保留兼容逻辑")
    void unsignedLegacyMarkerIsRejected() {
        String path = URLEncoder.encode("C:\\a.txt", StandardCharsets.UTF_8);
        String legacy = "[artifact-ref]path=" + path + "&chars=1&tool=readFile[/artifact-ref]";
        assertNull(ToolArtifactRef.parse(legacy));
    }

    @Test
    @DisplayName("缺 sig 字段：拒绝")
    void missingSignatureIsRejected() {
        String marker = ToolArtifactRef.marker("C:\\a.txt", 123, "readFile");
        int sigAt = marker.indexOf("&sig=");
        assertTrue(sigAt > 0);
        String unsigned = marker.substring(0, sigAt) + ToolArtifactRef.CLOSE;
        assertNull(ToolArtifactRef.parse(unsigned));
    }

    @Test
    @DisplayName("sig 被改：拒绝")
    void tamperedSignatureIsRejected() {
        String marker = ToolArtifactRef.marker("C:\\a.txt", 123, "readFile");
        int sigAt = marker.indexOf("sig=") + 4;
        char c = marker.charAt(sigAt);
        char flipped = c == 'a' ? 'b' : 'a';
        String tampered = marker.substring(0, sigAt) + flipped + marker.substring(sigAt + 1);
        assertNull(ToolArtifactRef.parse(tampered));
    }

    @Test
    @DisplayName("path 被改：签名验证失败")
    void tamperedPathIsRejected() {
        String original = URLEncoder.encode("C:\\a.txt", StandardCharsets.UTF_8);
        String replacement = URLEncoder.encode("C:\\b.txt", StandardCharsets.UTF_8);
        String marker = ToolArtifactRef.marker("C:\\a.txt", 123, "readFile");
        assertNull(ToolArtifactRef.parse(marker.replace(original, replacement)));
    }

    @Test
    @DisplayName("chars 被改：签名验证失败")
    void tamperedCharsIsRejected() {
        String marker = ToolArtifactRef.marker("C:\\a.txt", 123, "readFile");
        assertNull(ToolArtifactRef.parse(marker.replace("chars=123", "chars=124")));
    }

    @Test
    @DisplayName("tool 被改：签名验证失败")
    void tamperedToolIsRejected() {
        String marker = ToolArtifactRef.marker("C:\\a.txt", 123, "readFile");
        assertNull(ToolArtifactRef.parse(marker.replace("tool=readFile", "tool=evil")));
    }

    @Test
    @DisplayName("稳定密钥：重新配置同一密钥后旧引用仍可验证；换密钥后失败")
    void stableKeySurvivesReconfigure() {
        String marker = ToolArtifactRef.marker("C:\\a.txt", 10, "readFile");
        ToolArtifactRef.configure(KEY_A); // 模拟重启后同一稳定密钥
        assertNotNull(ToolArtifactRef.parse(marker));
        ToolArtifactRef.configure(KEY_B);
        assertNull(ToolArtifactRef.parse(marker));
    }

    @Test
    @DisplayName("进程内随机密钥：重启后旧引用明确降级为不可验证")
    void processRandomKeyRestartInvalidates() {
        ToolArtifactRef.useProcessRandomKey("test-a");
        String marker = ToolArtifactRef.marker("C:\\a.txt", 10, "readFile");
        ToolArtifactRef.useProcessRandomKey("test-b");
        assertNull(ToolArtifactRef.parse(marker));
    }

    @Test
    @DisplayName("桌面稳定材料：派生同一子密钥，重启后历史引用仍可验证")
    void providerDerivesStableKeyFromDesktopMaterial() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of(ArtifactRefKeyProvider.PROPERTY, DESKTOP_MATERIAL)));

        new ArtifactRefKeyProvider(env);
        String marker = ToolArtifactRef.marker("C:\\a.txt", 10, "readFile");

        ToolArtifactRef.resetForTest(); // 模拟进程重启
        new ArtifactRefKeyProvider(env);
        assertNotNull(ToolArtifactRef.parse(marker));
    }

    @Test
    @DisplayName("没有桌面材料：进程内随机密钥，重启后旧引用不可验证")
    void providerWithoutMaterialUsesProcessRandomAndDegrades() {
        new ArtifactRefKeyProvider(new StandardEnvironment());
        String marker = ToolArtifactRef.marker("C:\\a.txt", 10, "readFile");

        ToolArtifactRef.resetForTest(); // 模拟进程重启
        new ArtifactRefKeyProvider(new StandardEnvironment());
        assertNull(ToolArtifactRef.parse(marker));
    }

    @Test
    @DisplayName("三种省略/清除标记都能被识别，避免被后续压缩器二次改写")
    void omissionMarkerRecognizesAllThree() {
        assertTrue(ToolArtifactRef.isOmissionMarker("[旧工具结果已省略：x"));
        assertTrue(ToolArtifactRef.isOmissionMarker("[旧页面内容已省略：x"));
        assertTrue(ToolArtifactRef.isOmissionMarker("[已清除的工具结果：x"));
        assertFalse(ToolArtifactRef.isOmissionMarker("normal"));
        assertFalse(ToolArtifactRef.isOmissionMarker(null));
    }

    @Test
    @DisplayName("恢复说明带签名引用，后续压缩器能继续解析出同一路径")
    void recoverySuffixCarriesMarkerForLaterCompactors() {
        ToolArtifactRef.Ref ref = new ToolArtifactRef.Ref("C:\\a.txt", 10, "read_item");
        String suffix = ToolArtifactRef.recoverySuffix(ref, 99);
        assertTrue(suffix.contains("C:\\a.txt"));
        assertTrue(suffix.contains("总 10 字符"));
        ToolArtifactRef.Ref parsed = ToolArtifactRef.parse(suffix);
        assertNotNull(parsed);
        assertEquals("C:\\a.txt", parsed.path());
        assertEquals(10, parsed.chars());
    }

    @Test
    @DisplayName("未落盘时明确说无法恢复，不暗示存在文件")
    void nullRefSaysCannotRecover() {
        String suffix = ToolArtifactRef.recoverySuffix(null, 99);
        assertTrue(suffix.contains("未落盘"));
        assertTrue(suffix.contains("无法从该标记恢复"));
        assertFalse(suffix.contains(ToolArtifactRef.OPEN));
    }
}
