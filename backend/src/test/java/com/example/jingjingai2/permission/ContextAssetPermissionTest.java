package com.example.jingjingai2.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** searchContext 是只读内置工具，默认放行，且不改变其他工具的默认权限。 */
class ContextAssetPermissionTest {

    private final PermissionPolicy policy = new PermissionPolicy(new PermissionProperties());

    @Test
    @DisplayName("searchContext 默认 ALLOW，不提供会话级授权")
    void searchContextIsReadOnlyAllow() {
        PermissionPolicy.Match match = policy.match("searchContext", "{\"operation\":\"search\"}");
        assertEquals(PermissionPolicy.Effect.ALLOW, match.effect);
        assertEquals(false, match.sessionGrant);
    }

    @Test
    @DisplayName("未登记工具仍是 ASK，写类工具仍是 ASK")
    void otherDefaultsUntouched() {
        assertEquals(PermissionPolicy.Effect.ASK, policy.match("someNewTool", "{}").effect);
        assertEquals(PermissionPolicy.Effect.ASK, policy.match("writeFile", "{}").effect);
        assertEquals(PermissionPolicy.Effect.ALLOW, policy.match("readFile", "{}").effect);
    }
}
