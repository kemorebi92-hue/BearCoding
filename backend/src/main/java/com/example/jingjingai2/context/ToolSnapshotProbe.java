package com.example.jingjingai2.context;

import com.example.jingjingai2.tool.ToolArtifactRef;
import com.example.jingjingai2.tool.ToolSnapshotIntegrity;

/**
 * 退休前的快照探测：这条引用**现在**能不能按引用读回，读回的字节是否与哈希一致。
 *
 * <p>为什么必须由调用方注入：能不能读，取决于当前请求的沙箱授权（工作区根、当前会话的
 * 只读落盘目录、会话权限模式）。策略类自己看不到这些，凭空判断路径合法就等于假设
 * 「文件在操作系统上存在 = 模型一定读得到」，而这个假设正是 M3 要拆掉的。
 *
 * <p>生产实现是 {@code FileTool#snapshotStatus}：和真正恢复时走同一段沙箱与哈希校验，
 * 所以「退休前说可恢复」和「恢复时真的读得到」结论一致。不传时策略退回纯结构 + 哈希
 * 校验（见 {@link #SANDBOX_FREE}），仅用于单元测试与无沙箱场景。
 */
@FunctionalInterface
public interface ToolSnapshotProbe {

    /**
     * @return {@link ToolSnapshotIntegrity.Status#OK} 表示可退休；其它状态都禁止退休
     */
    ToolSnapshotIntegrity.Status check(ToolArtifactRef.Ref ref);

    /** 没有沙箱时：只验路径结构、存在性和内容哈希，不主张授权可读。 */
    ToolSnapshotProbe SANDBOX_FREE = ref -> ToolSnapshotIntegrity.verify(ref, null).status();
}
