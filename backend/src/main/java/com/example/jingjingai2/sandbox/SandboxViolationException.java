package com.example.jingjingai2.sandbox;

/**
 * 沙箱违规异常：工具尝试访问工作区之外的路径时抛出。
 * 捕获方应把它转成对模型可见的错误文案（让模型知道"越界了"并自我纠正），而不是让对话崩溃。
 */
public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) {
        super(message);
    }
}
