package com.example.jingjingai2.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区之外的「只读例外」根目录。
 *
 * <p>目前只有一个实现：MCP 超限结果落盘的当前会话目录。
 * 接口放在 sandbox 包里是为了让 {@link PathSandbox} 不反向依赖 mcp 包。
 */
public interface ReadOnlyRootProvider {

    /**
     * 当前线程/请求上下文里允许只读访问的根目录。没有上下文时返回空表。
     * 调用方必须对返回的路径做真实路径（toRealPath）前缀校验后才会放行。
     */
    List<Path> readOnlyRoots();
}
