package com.example.jingjingai2.utils;

/**
 * createPlan 工具的「入参形态」抽象（标记接口）。
 *
 * 为什么需要它：createPlan 的入参形态由配置开关 {@code app.plan.steps.mode} 决定 ——
 * flat = 一个普通字符串；structured = 嵌套对象数组。两种形态对外必须是**同一个工具名**，
 * 但 Java 里无法用同一个方法签名表达，所以拆成两个 @Component
 * （{@link PlanToolFlat} / {@link PlanToolStructured}），用 @ConditionalOnProperty
 * 保证同一时刻只有一个 Bean 存在。
 *
 * ChatController 只按这个接口注入即可，完全不必知道当前生效的是哪种形态
 * （避免在 Controller 里写 if/else 判断配置）。
 */
public interface PlanCreateTool {
}
