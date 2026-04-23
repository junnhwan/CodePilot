package com.codepilot.core.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codepilot.storage")
public class StorageProperties {

    private final JdbcStore mysql = new JdbcStore();

    private final JdbcStore pgvector = new JdbcStore();

    private final RedisStore redis = new RedisStore();

    public JdbcStore getMysql() {
        return mysql;
    }

    public JdbcStore getPgvector() {
        return pgvector;
    }

    public RedisStore getRedis() {
        return redis;
    }

    public static class JdbcStore {

        private boolean enabled = true;

        private String url = "";

        private String username = "";

        private String password = "";

        private String driverClassName = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }

    public static class RedisStore {

        private boolean enabled = true;

        private String url = "";

        private String username = "";

        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
