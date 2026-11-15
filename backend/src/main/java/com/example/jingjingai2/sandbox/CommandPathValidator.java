package com.example.jingjingai2.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 终端命令路径守卫（**白名单式**，替代原来的黑名单式防御）。
 *
 * 为什么改：
 *  原来的做法是"列出敏感目录，命令里出现就拦"。E2E 实测证明这种 deny-list 不可靠——
 *  一句 {@code cd /d D:\java\practice\jingjingAI2 && dir /s /b src} 就绕过了它，
 *  因为 cd 本身没提任何敏感词，但它把后续命令的工作目录整个换走了。
 *  黑名单永远列不全（pushd、../ 拼接、符号链接、驱动器切换……），所以这里换成
 *  **白名单**：只允许"工作区内"的路径出现。
 *
 * 两条规则：
 *  1. **禁止改目录**：命令里出现 cd / chdir / pushd / popd（作为独立词）→ 直接拒绝。
 *     不做"判断它切到哪是否合法"的尝试，工作目录已经固定在 {@link #validate} 传入的 workDir。
 *  2. **所有像路径的参数都要在界内**：把命令按空白/引号切词，挑出看起来像路径的 token
 *     （含分隔符、`..`、盘符，或是 `.`/`..`），逐个规范化（resolve → normalize → 解析符号链接）
 *     后校验必须仍在工作区根内；**任何一个越界，整条命令拒绝执行**。不是只看命令开头。
 *
 * 局限（诚实说明）：这仍是**应用层**防线，靠的是"解析 + 前缀判断"，不是 OS 级隔离。
 * 面对刻意构造的混淆（命令拼接字符串再 eval、调用外部程序间接访问等）无法保证 100% 拦死；
 * 真正要跑不受信任的代码，最终仍应下沉到容器/虚拟机（对标 OpenHands 的 Docker 沙箱）。
 */
public final class CommandPathValidator {

    /** 改目录指令（含 cmd 的 cd/chdir 与 PowerShell 的 pushd/popd）：作为独立词出现即拒绝 */
    private static final Pattern DIR_CHANGE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(cd|chdir|pushd|popd)(?![A-Za-z0-9_-])");

    /** cmd 的短开关（/s /b /a /c 之类），它们不是路径 */
    private static final Pattern WINDOWS_SWITCH = Pattern.compile("^/[A-Za-z?]{1,3}$");

    /** 盘符开头（C: 或 C:\x） */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    /**
     * 远程 URL：这类 token 不是文件系统路径，不该按路径规则校验。
     * E2E 里发现的误伤：`curl https://api.github.com/...` 被当成"非法路径"拒了
     * （Windows 路径中 `:` 出现在非盘符位置属于非法字符）。URL 自身无法造成路径逃逸；
     * 但 `file://` **不在**白名单内——它确实指向本地文件，仍必须走路径校验。
     */
    private static final Pattern REMOTE_URL = Pattern.compile(
            "(?i)^(https?|ftps?|wss?|sftp|ssh|git|npm|maven)://.*");

    /** 校验结论 */
    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    private CommandPathValidator() {}

    /**
     * 校验一条即将执行的命令。默认要求所有路径参数落在工作区内。
     *
     * @param command 命令原文
     * @param workDir 该命令实际的工作目录（应为工作区根）
     * @return allowed=false 时 reason 是给模型看的中文提示
     */
    public static Decision validate(String command, Path workDir) {
        return validate(command, workDir, false);
    }

    /**
     * 同上，{@code allowOutside=true}（会话 FULL_ACCESS）时只放开「路径必须落在工作区内」这一条范围限制。
     * cd / chdir / pushd / popd 禁令、UNC、非法冒号、非法路径、驱动器相对路径都照旧拒绝。
     *
     * @param allowOutside 是否允许参数引用工作区外的普通路径
     */
    public static Decision validate(String command, Path workDir, boolean allowOutside) {
        if (command == null || command.isBlank()) {
            return Decision.allow();
        }
        if (workDir == null) {
            return Decision.deny("沙箱未就绪：无法确定工作目录，已拒绝执行。");
        }

        // 规则 1：一律禁止改目录
        if (DIR_CHANGE.matcher(command).find()) {
            return Decision.deny("命令已被沙箱拦截：不允许在命令中切换目录，请使用相对路径操作。"
                    + "（检测到 cd / chdir / pushd / popd；命令的工作目录已固定在工作区：" + workDir + "）");
        }

        Path root = workDir.toAbsolutePath().normalize();
        Path realRoot = canonicalize(root);

        // 规则 2：所有像路径的参数都必须落在工作区内（FULL_ACCESS 下放开范围，其余检查照旧）
        for (String token : tokenize(command)) {
            if (!looksLikePath(token)) continue;

            if (DRIVE_PREFIX.matcher(token).matches()) {
                Path raw;
                try {
                    raw = Paths.get(token);
                } catch (RuntimeException e) {
                    return Decision.deny("命令中包含非法路径：" + token + "，已拒绝执行。");
                }
                // 形如 `D:foo` 的"驱动器相对路径"含义依赖该盘当前目录，无法保证在工作区内 → 拒绝
                if (!raw.isAbsolute()) {
                    return Decision.deny("命令包含含义不确定的驱动器相对路径：" + token
                            + "，请改用工作区内的相对路径。");
                }
            }

            Path abs;
            try {
                Path p = Paths.get(token);
                abs = p.isAbsolute() ? p : root.resolve(p);
            } catch (RuntimeException e) {
                return Decision.deny("命令中包含非法路径：" + token + "，已拒绝执行。");
            }

            Path real = canonicalize(abs.toAbsolutePath().normalize());
            if (!allowOutside && !real.startsWith(realRoot)) {
                return Decision.deny("命令已被沙箱拦截：参数引用了工作区之外的路径 -> " + token
                        + "（只允许操作工作区 " + workDir + "）。");
            }
        }
        return Decision.allow();
    }

    /**
     * 把命令切成词：按空白与 shell 分隔符切分，并剥掉成对的引号
     * （剥引号是为了让 `"C:\secret file.txt"` 这种也能被识别成路径并校验）。
     */
    static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else cur.append(ch);
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
                    || ch == ';' || ch == '|' || ch == '&' || ch == '>' || ch == '<') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 判断一个 token 是否"看起来像路径"（保守判断：拿不准就不当路径，避免误伤普通参数） */
    static boolean looksLikePath(String token) {
        if (token == null || token.isEmpty()) return false;
        if (WINDOWS_SWITCH.matcher(token).matches()) return false;   // /s /b 这类开关
        if (REMOTE_URL.matcher(token).matches()) return false;       // 远程 URL 不是文件路径
        if (token.equals(".") || token.equals("..")) return true;
        if (token.contains("/") || token.contains("\\")) return true;
        if (token.contains("..")) return true;
        return DRIVE_PREFIX.matcher(token).matches();
    }

    /**
     * 规范化：解析 {@code ..}，并**解析符号链接**。
     * 路径不存在时（例如带通配符），向上找到最近的已存在祖先做 realpath，再把剩余层级拼回去，
     * 这样"工作区里放一个指向外部的符号链接"也会被识破。
     */
    static Path canonicalize(Path path) {
        Path cur = path;
        Deque<Path> tail = new ArrayDeque<>();
        while (cur != null) {
            if (Files.exists(cur)) {
                try {
                    Path real = cur.toRealPath();
                    Iterator<Path> it = tail.descendingIterator();
                    while (it.hasNext()) {
                        real = real.resolve(it.next());
                    }
                    return real.normalize();
                } catch (IOException e) {
                    return path.normalize();
                }
            }
            Path name = cur.getFileName();
            if (name == null) break;
            tail.addLast(name);
            cur = cur.getParent();
        }
        return path.normalize();
    }
}
