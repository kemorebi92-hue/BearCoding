package com.example.jingjingai2.service;

import com.example.jingjingai2.entity.McpServerConfig;
import com.example.jingjingai2.entity.McpServerCredential;
import com.example.jingjingai2.mapper.McpServerConfigMapper;
import com.example.jingjingai2.mapper.McpServerCredentialMapper;
import com.example.jingjingai2.mcp.McpCredentialCrypto;
import com.example.jingjingai2.mcp.McpCredentialException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 凭证存储与加密。假令牌，内存表，不连库，不访问外网。 */
class McpCredentialServiceTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";
    private static final String TOKEN = "fake-token-not-real";

    @Test
    @DisplayName("密文不等于明文，正确密钥能解回原值")
    void ciphertextIsNotPlaintextAndRoundTrips() {
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService svc = service(stored, KEY, config(11L, 7L, "echo"));
        svc.put(7L, 11L, TOKEN);
        assertEquals(1, stored.rows.size());
        assertNotEquals(TOKEN, stored.rows.get(0).getCiphertext());
        assertFalse(stored.rows.get(0).getCiphertext().contains(TOKEN));
        assertTrue(stored.rows.get(0).getCiphertext().startsWith("v1."));
        assertEquals(TOKEN, svc.load(7L, 11L));
    }

    @Test
    @DisplayName("密钥缺失、空白或过短时保存失败，且没有凭证行")
    void missingKeyDoesNotStore() {
        for (String material : new String[]{null, "", "   ", "too-short"}) {
            MemoryCredentials stored = new MemoryCredentials();
            McpCredentialService svc = service(stored, material, config(11L, 7L, "echo"));
            assertThrows(McpCredentialException.class, () -> svc.put(7L, 11L, TOKEN));
            assertTrue(stored.rows.isEmpty(), material);
        }
    }

    @Test
    @DisplayName("错误密钥或被改过的密文都不能解密")
    void wrongKeyAndTamperFail() {
        McpCredentialCrypto crypto = new McpCredentialCrypto(KEY);
        String stored = crypto.encrypt(TOKEN);
        McpCredentialCrypto wrong = new McpCredentialCrypto(OTHER_KEY);
        assertThrows(McpCredentialException.class, () -> wrong.decrypt(stored));
        // 不在 Base64 字符串末尾做替换：密文带 "=" 填充时，末尾字符的低位可能只是填充位，
        // 解码后的字节可以完全不变，断言就会偶发失败。这里在字节层翻转认证标签的最后一个字节，
        // 再按原格式重新编码，字节数与字符串长度都不变。
        String prefix = stored.substring(0, stored.indexOf('.') + 1);
        byte[] packed = Base64.getDecoder().decode(stored.substring(prefix.length()));
        packed[packed.length - 1] ^= 0x01;
        String flipped = prefix + Base64.getEncoder().encodeToString(packed);
        assertNotEquals(stored, flipped);
        assertEquals(stored.length(), flipped.length());
        assertThrows(McpCredentialException.class, () -> crypto.decrypt(flipped));
        assertThrows(McpCredentialException.class, () -> crypto.decrypt(stored + "x"));
    }

    @Test
    @DisplayName("用户 A 不能读、覆盖或删除用户 B 的凭证")
    void userCannotTouchAnotherUsersCredential() {
        MemoryCredentials stored = new MemoryCredentials();
        McpServerConfig b = config(22L, 9L, "echo");
        McpCredentialService svc = service(stored, KEY, b);
        svc.put(9L, 22L, TOKEN);
        String cipher = stored.rows.get(0).getCiphertext();
        assertThrows(McpCredentialException.class, () -> svc.load(7L, 22L));
        assertThrows(McpCredentialException.class, () -> svc.put(7L, 22L, "other-fake"));
        assertThrows(McpCredentialException.class, () -> svc.delete(7L, 22L));
        assertEquals(1, stored.rows.size());
        assertEquals(cipher, stored.rows.get(0).getCiphertext());
        assertEquals(TOKEN, svc.load(9L, 22L));
    }

    @Test
    @DisplayName("同名服务各用各的配置 id，凭证不串")
    void sameNameDoesNotShareCredential() {
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService svc = service(stored, KEY,
                config(11L, 7L, "echo"), config(22L, 9L, "echo"));
        svc.put(7L, 11L, "token-for-a");
        svc.put(9L, 22L, "token-for-b");
        assertEquals("token-for-a", svc.load(7L, 11L));
        assertEquals("token-for-b", svc.load(9L, 22L));
        assertThrows(McpCredentialException.class, () -> svc.load(7L, 22L));
    }

    @Test
    @DisplayName("再次保存覆盖原密文，不增加第二行")
    void secondPutReplaces() {
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService svc = service(stored, KEY, config(11L, 7L, "echo"));
        svc.put(7L, 11L, TOKEN);
        svc.put(7L, 11L, "fake-token-rotated");
        assertEquals(1, stored.rows.size());
        assertEquals("fake-token-rotated", svc.load(7L, 11L));
    }

    @Test
    @DisplayName("不存在或不属于当前用户的配置不能绑定凭证")
    void missingOrForeignConfigRejected() {
        MemoryCredentials stored = new MemoryCredentials();
        McpCredentialService svc = service(stored, KEY, config(22L, 9L, "echo"));
        assertThrows(McpCredentialException.class, () -> svc.put(7L, 99L, TOKEN));
        assertThrows(McpCredentialException.class, () -> svc.put(7L, 22L, TOKEN));
        assertThrows(McpCredentialException.class, () -> svc.put(0L, 22L, TOKEN));
        assertTrue(stored.rows.isEmpty());
    }

    private static McpCredentialService service(MemoryCredentials stored, String key, McpServerConfig... configs) {
        return new McpCredentialService(new MemoryConfigs(configs), stored, new McpCredentialCrypto(key));
    }

    private static McpServerConfig config(long id, long owner, String name) {
        McpServerConfig c = new McpServerConfig();
        c.setId(id);
        c.setCreatedBy(owner);
        c.setName(name);
        c.setAuthType("none");
        return c;
    }

    private static final class MemoryConfigs implements McpServerConfigMapper {
        private final List<McpServerConfig> rows;
        MemoryConfigs(McpServerConfig... configs) { this.rows = List.of(configs); }
        @Override public McpServerConfig findById(long id) {
            for (McpServerConfig c : rows) {
                if (c.getId() != null && c.getId() == id) return c;
            }
            return null;
        }
        @Override public int insert(McpServerConfig config) { throw new UnsupportedOperationException(); }
        @Override public int update(McpServerConfig config) { throw new UnsupportedOperationException(); }
        @Override public int updateEnabled(long ownerId, String name, boolean enabled) { throw new UnsupportedOperationException(); }
        @Override public int delete(long ownerId, String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByName(String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findByOwnerAndName(long ownerId, String name) { throw new UnsupportedOperationException(); }
        @Override public McpServerConfig findSharedByName(String name) { throw new UnsupportedOperationException(); }
        @Override public List<McpServerConfig> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<McpServerConfig> findAllByOwner(long ownerId) { throw new UnsupportedOperationException(); }
    }

    private static final class MemoryCredentials implements McpServerCredentialMapper {
        final List<McpServerCredential> rows = new ArrayList<>();
        @Override public int insert(McpServerCredential row) { rows.add(row); return 1; }
        @Override public int updateCiphertext(long userId, long serverConfigId, String ciphertext) {
            McpServerCredential row = find(userId, serverConfigId);
            if (row == null) return 0;
            row.setCiphertext(ciphertext);
            return 1;
        }
        @Override public McpServerCredential find(long userId, long serverConfigId) {
            for (McpServerCredential row : rows) {
                if (row.getUserId() == userId && row.getServerConfigId() == serverConfigId) return row;
            }
            return null;
        }
        @Override public int count(long userId, long serverConfigId) {
            return find(userId, serverConfigId) == null ? 0 : 1;
        }
        @Override public int delete(long userId, long serverConfigId) {
            return rows.removeIf(row -> row.getUserId() == userId && row.getServerConfigId() == serverConfigId) ? 1 : 0;
        }
    }
}
