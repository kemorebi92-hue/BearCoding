package com.example.jingjingai2.tool;

import com.example.jingjingai2.sandbox.PathSandbox;
import com.example.jingjingai2.sandbox.SandboxViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * 签名快照的**一次读取、一次校验**。
 *
 * <p>为什么单独拿出来：退休前的安全检查（{@code ContextProjectionPolicy}）、实际恢复
 * （{@code FileTool.readFile}）和历史压缩都要回答同一个问题——「这条引用现在还能读回、
 * 而且读回的字节就是引用里那个哈希吗」。以前三处各写一遍，结论容易不一致：退休端只看
 * 文件存在，读取端才看哈希，于是存在「存在但已损坏」的快照被标成可恢复。
 *
 * <p>校验顺序（每一步失败都返回具体状态，不抛异常）：
 * <ol>
 *   <li>引用必须带 64 位 sha256；旧引用无法证明内容完整，直接拒绝；</li>
 *   <li>路径必须合法：非空、绝对、不是 UNC、冒号只能在盘符位置（NTFS 备用数据流）；</li>
 *   <li>给了沙箱就必须过 {@link PathSandbox#resolveForRead}——签名不能扩大可读范围；</li>
 *   <li>文件必须存在、是普通文件、可读；</li>
 *   <li>字节的 SHA-256 必须与引用里的哈希一致。</li>
 * </ol>
 *
 * <p>校验通过时把**这一次读到的字节**一起返回。调用方必须用它做正文，不要再读一次：
 * 校验和读取是同一次读取，检查后被替换/删除的窗口就只剩「同一份字节」这一层。
 * 这不等于操作系统级的原子协议，但比「先检查文件、再读文件」小得多。
 */
public final class ToolSnapshotIntegrity {

    /** 快照不可恢复的原因。OK 之外的状态都对应一条明确的安全原因。 */
    public enum Status {
        /** 引用有效、路径合法、文件存在且内容哈希一致。 */
        OK,
        /** 引用没有内容哈希（旧格式），无法证明内容完整。 */
        NO_HASH,
        /** 路径不合法：空、相对、UNC 或带非法冒号。 */
        PATH_UNSAFE,
        /** 路径不在当前会话允许读取的范围内。 */
        NOT_AUTHORIZED,
        /** 文件不存在或不是普通文件。 */
        MISSING,
        /** 文件存在但读不出来。 */
        UNREADABLE,
        /** 文件内容与引用里的哈希不一致（被替换或篡改）。 */
        HASH_MISMATCH
    }

    /**
     * @param status  校验结论
     * @param path    解析后的绝对路径；路径非法时为 null
     * @param content 只有 {@link Status#OK} 时才是校验过的那份字节
     * @param detail  给调用方拼错误信息用的原因（例如沙箱的越界说明）
     */
    public record Check(Status status, Path path, byte[] content, String detail) {
        public boolean ok() {
            return status == Status.OK;
        }

        /** 校验过的正文。非 OK 时是空串，绝不返回未校验的内容。 */
        public String text() {
            return content == null ? "" : new String(content, StandardCharsets.UTF_8);
        }
    }

    private ToolSnapshotIntegrity() {}

    /**
     * 校验一条引用。
     *
     * @param sandbox 为 null 时只做结构 + 哈希校验（单元测试或无沙箱场景）；
     *                生产必须传入当前请求的真实沙箱，否则无法证明「模型真的读得到」。
     */
    public static Check verify(ToolArtifactRef.Ref ref, PathSandbox sandbox) {
        if (ref == null) return fail(Status.NO_HASH, null, "没有可信引用");
        if (!ref.hasContentHash()) return fail(Status.NO_HASH, null, "引用缺少内容哈希");
        String raw = ref.path();
        if (raw == null || raw.isBlank()) return fail(Status.PATH_UNSAFE, null, "引用里的路径为空");
        if (PathSandbox.isUnc(raw) || PathSandbox.hasIllegalColon(raw)) {
            return fail(Status.PATH_UNSAFE, null, "引用里的路径不合法");
        }
        Path path;
        try {
            path = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return fail(Status.PATH_UNSAFE, null, "引用里的路径无法解析");
        }
        if (sandbox != null) {
            try {
                path = sandbox.resolveForRead(raw);
            } catch (SandboxViolationException e) {
                return fail(Status.NOT_AUTHORIZED, path, e.getMessage());
            } catch (RuntimeException e) {
                return fail(Status.NOT_AUTHORIZED, path, "沙箱无法确认该路径可读");
            }
        }
        try {
            if (!Files.isRegularFile(path)) {
                return fail(Status.MISSING, path, "快照文件不存在或不是普通文件");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equalsIgnoreCase(ref.sha256())) {
                return fail(Status.HASH_MISMATCH, path, "快照内容与引用哈希不一致");
            }
            return new Check(Status.OK, path, bytes, null);
        } catch (IOException | RuntimeException e) {
            return fail(Status.UNREADABLE, path, e.getMessage() == null ? "快照读取失败" : e.getMessage());
        }
    }

    /** 字节的 SHA-256 小写十六进制。和落盘、引用用的是同一套算法。 */
    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static Check fail(Status status, Path path, String detail) {
        return new Check(status, path, null, detail);
    }
}
