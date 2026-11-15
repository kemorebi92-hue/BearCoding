package com.example.jingjingai2.service;

import org.springframework.stereotype.Service;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在本机桌面弹出系统文件夹选择器，返回用户选中的绝对路径。
 *
 * <p>为什么放在后端：浏览器的 File System Access API 只给目录句柄，拿不到真实绝对路径；
 * 而“绑定工作区”必须把真实路径交给后端。后端与用户在同一台机器上时，由服务端弹窗是唯一能拿到
 * 绝对路径的做法。
 *
 * <p>三条边界：
 * <ul>
 *   <li>只在交互式桌面可用；headless（服务器 / 无显示会话）直接给出可读错误，不假装成功。</li>
 *   <li>同一时刻只允许一个选择窗口；第二个请求返回“已有窗口在等待”，避免弹出一串对话框。</li>
 *   <li>是否只允许本机请求由 Controller 判断（远程机器上弹窗没有意义，也容易被滥用）。</li>
 * </ul>
 */
@Service
public class DirectoryPickerService {

    /** 可替换的弹窗实现：单测用假实现，不真的打开 Swing 窗口。 */
    @FunctionalInterface
    public interface Chooser {
        /** @return 选中的绝对路径；{@code null} 表示用户取消。 */
        String choose(Path initialDir);
    }

    private final Chooser chooser;
    private final AtomicBoolean picking = new AtomicBoolean(false);

    public DirectoryPickerService() {
        this(new SwingChooser());
    }

    DirectoryPickerService(Chooser chooser) {
        this.chooser = chooser;
    }

    /** 当前是否已经有一个选择窗口在等待（诊断 / 测试用）。 */
    public boolean isPicking() {
        return picking.get();
    }

    /**
     * 弹出选择器并阻塞到用户选完或取消。
     *
     * @param initialDir 初始目录，可为 null（回落到用户主目录）
     * @return 选中的绝对路径；null 表示取消
     * @throws UnsupportedOperationException 当前环境没有图形界面
     * @throws IllegalStateException         已经有一个选择窗口在等待
     */
    public String pick(Path initialDir) {
        if (!picking.compareAndSet(false, true)) {
            throw new IllegalStateException("已经有一个文件夹选择窗口在等待，请先完成或取消它");
        }
        try {
            return chooser.choose(initialDir);
        } finally {
            picking.set(false);
        }
    }

    /** 真正的 Swing 实现：必须在 EDT 上创建和关闭对话框。 */
    static final class SwingChooser implements Chooser {

        @Override
        public String choose(Path initialDir) {
            if (GraphicsEnvironment.isHeadless()) {
                throw new UnsupportedOperationException(
                        "当前后端运行在无图形界面的环境，无法打开文件夹选择器；请手动填写路径");
            }
            AtomicReference<String> selected = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    // 独立的置顶父窗口：否则对话框会落到浏览器后面，用户以为没弹出来
                    JDialog parent = new JDialog();
                    parent.setAlwaysOnTop(true);
                    try {
                        JFileChooser chooser = new JFileChooser();
                        chooser.setDialogTitle("选择要绑定的本地目录");
                        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        chooser.setAcceptAllFileFilterUsed(false);
                        Path start = usableStart(initialDir);
                        if (start != null) {
                            chooser.setCurrentDirectory(start.toFile());
                        }
                        int ret = chooser.showDialog(parent, "选择此文件夹");
                        if (ret == JFileChooser.APPROVE_OPTION) {
                            selected.set(resolveSelected(
                                    chooser.getSelectedFile(), chooser.getCurrentDirectory()));
                        }
                    } finally {
                        parent.dispose();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待文件夹选择时被中断，请重试", e);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof HeadlessException) {
                    throw new UnsupportedOperationException(
                            "当前后端运行在无图形界面的环境，无法打开文件夹选择器；请手动填写路径", cause);
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("打开文件夹选择器失败: " + cause.getMessage(), cause);
            }
            return selected.get();
        }

        /**
         * DIRECTORY_ONLY 下用户可能只是导航到目标目录、没有点中某个子目录；
         * 这时 getSelectedFile() 为 null，用当前目录兜底（就是“文件夹名”里显示的目录）。
         */
        static String resolveSelected(File selected, File current) {
            File file = selected != null ? selected : current;
            return file == null ? null : file.toPath().toAbsolutePath().normalize().toString();
        }

        private static Path usableStart(Path initialDir) {
            if (initialDir != null && Files.isDirectory(initialDir)) return initialDir;
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                Path p = Path.of(home);
                if (Files.isDirectory(p)) return p;
            }
            return null;
        }
    }
}
