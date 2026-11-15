package com.example.jingjingai2.service;

/**
 * 数据库里没有用户模型，且系统模型也没有配置可用的 API Key。
 *
 * <p>单独成类型是为了让聊天入口把“请先配置模型”原样交给用户，
 * 而不是被通用异常处理成“执行异常，未确认交付”。
 */
public class ModelNotConfiguredException extends RuntimeException {

    public static final String MESSAGE =
            "还没有配置模型。请先在「模型配置」页添加并保存一个模型，然后再发送消息。";

    public ModelNotConfiguredException() {
        super(MESSAGE);
    }
}
