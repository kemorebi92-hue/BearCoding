package com.example.jingjingai2.mcp;

/**
 * 一次 {@code tools/call} 的结果。{@code error=true} 表示协议层或工具层失败
 * （JSON-RPC error / isError / 进程挂了），文案已经是给模型看的纯文本。
 *
 * <p>{@link Delivery} 只描述这次请求有没有真正发出去，给重试判断用。
 * 不靠错误文本里的措辞推断：文本是给模型看的，发送状态是给 Host 看的。
 */
public final class McpCallResult {

    /**
     * 请求的发送状态。
     * <ul>
     *   <li>{@link #NOT_SENT} 只有在能够证明请求一个字节都没出去时才能用；</li>
     *   <li>{@link #UNKNOWN} 表示可能已发出，或无法证明没发出；</li>
     *   <li>{@link #COMPLETED} 表示这次交互有了确定的结果，成功或远端明确返回的失败都算。</li>
     * </ul>
     */
    public enum Delivery { NOT_SENT, UNKNOWN, COMPLETED }

    private final boolean error;
    private final String text;
    private final Delivery delivery;

    public McpCallResult(boolean error, String text) {
        this(error, text, error ? Delivery.UNKNOWN : Delivery.COMPLETED);
    }

    public McpCallResult(boolean error, String text, Delivery delivery) {
        this.error = error;
        this.text = text == null ? "" : text;
        this.delivery = delivery == null ? Delivery.UNKNOWN : delivery;
    }

    public static McpCallResult ok(String text) {
        return new McpCallResult(false, text, Delivery.COMPLETED);
    }

    /** 失败但说不清请求发出去没有。不触发自动重发。 */
    public static McpCallResult fail(String reason) {
        return failed(reason, Delivery.UNKNOWN);
    }

    /** 调用开始前就能证明一个字节都没发出去。可以重连后再发，不能当成已经执行。 */
    public static McpCallResult notSent(String reason) {
        return failed(reason, Delivery.NOT_SENT);
    }

    /** 远端明确返回的失败：工具执行过了，结果是确定的，也不该重发。 */
    public static McpCallResult failedAtTool(String reason) {
        return failed(reason, Delivery.COMPLETED);
    }

    public static McpCallResult failed(String reason, Delivery delivery) {
        return new McpCallResult(true, reason == null ? "未知错误" : reason, delivery);
    }

    public boolean isError() { return error; }
    public String text() { return text; }
    public Delivery delivery() { return delivery; }
}
