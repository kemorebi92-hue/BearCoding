package com.example.jingjingai2.permission;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code app.permission.*}。rules 为空时用代码里的内置表，避免空配置把一切打成 ASK。
 */
@Component
@ConfigurationProperties(prefix = "app.permission")
public class PermissionProperties {

    private int approvalTimeoutSeconds = 120;
    private List<Rule> rules = new ArrayList<>();

    public int getApprovalTimeoutSeconds() { return approvalTimeoutSeconds; }
    public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) {
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    public static class Rule {
        private String pattern;
        private String effect = "ASK";
        private boolean sessionGrant;

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public String getEffect() { return effect; }
        public void setEffect(String effect) { this.effect = effect; }
        public boolean isSessionGrant() { return sessionGrant; }
        public void setSessionGrant(boolean sessionGrant) { this.sessionGrant = sessionGrant; }
    }
}
