package com.example.jingjingai2.agent.reconcile;

/** 一次工具调用的**业务**结果。与执行级 {@code ToolExecutionStatus.Kind} 分开。 */
public enum ToolBusinessOutcome {
    /** 能确认业务成功（例如 exit=0、明确的成功返回、子代理 COMPLETE）。 */
    OK,
    /** 工具正常执行，但业务失败（例如 exit!=0、匹配 0 处、写入失败）。 */
    BUSINESS_ERROR,
    /** 工具执行失败 / 不存在 / 抛异常等。 */
    FAILED,
    /** 权限或白名单拒绝，工具没有执行。 */
    DENIED,
    /** 子代理只拿到部分结论。 */
    PARTIAL,
    /** 无法从现有结构化结果或严格文本规则判断。 */
    UNKNOWN
}
