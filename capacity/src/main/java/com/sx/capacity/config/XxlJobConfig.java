package com.sx.capacity.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(XxlJobConfig.XxlJobProperties.class)
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties props) {
        if (props.getAdmin() == null
                || !StringUtils.hasText(props.getAdmin().getAddresses())
                || props.getExecutor() == null
                || !StringUtils.hasText(props.getExecutor().getAppname())) {
            throw new IllegalStateException("xxl.job 已开启，但 admin.addresses 或 executor.appname 未配置");
        }

        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(props.getAdmin().getAddresses().trim());
        if (StringUtils.hasText(props.getAccessToken())) {
            executor.setAccessToken(props.getAccessToken().trim());
        }

        XxlJobProperties.Executor ex = props.getExecutor();
        executor.setAppname(ex.getAppname().trim());
        if (StringUtils.hasText(ex.getAddress())) {
            executor.setAddress(ex.getAddress().trim());
        }
        if (StringUtils.hasText(ex.getIp())) {
            executor.setIp(ex.getIp().trim());
        }
        executor.setPort(ex.getPort() > 0 ? ex.getPort() : 9999);
        if (StringUtils.hasText(ex.getLogPath())) {
            executor.setLogPath(ex.getLogPath().trim());
        }
        if (ex.getLogRetentionDays() > 0) {
            executor.setLogRetentionDays(ex.getLogRetentionDays());
        }
        return executor;
    }

    @ConfigurationProperties(prefix = "xxl.job")
    public static class XxlJobProperties {
        private boolean enabled;
        private Admin admin;
        private Executor executor;
        /** 与 xxl-job-admin 通讯 token，需与 admin 配置一致；建议生产环境用环境变量覆盖 */
        private String accessToken = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Admin getAdmin() {
            return admin;
        }

        public void setAdmin(Admin admin) {
            this.admin = admin;
        }

        public Executor getExecutor() {
            return executor;
        }

        public void setExecutor(Executor executor) {
            this.executor = executor;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public static class Admin {
            /** 例如：http://127.0.0.1:8080/xxl-job-admin */
            private String addresses;

            public String getAddresses() {
                return addresses;
            }

            public void setAddresses(String addresses) {
                this.addresses = addresses;
            }
        }

        public static class Executor {
            private String appname = "capacity-executor";
            private String address;
            private String ip;
            private int port = 9999;
            private String logPath = "./logs/xxl-job";
            private int logRetentionDays = 7;

            public String getAppname() {
                return appname;
            }

            public void setAppname(String appname) {
                this.appname = appname;
            }

            public String getAddress() {
                return address;
            }

            public void setAddress(String address) {
                this.address = address;
            }

            public String getIp() {
                return ip;
            }

            public void setIp(String ip) {
                this.ip = ip;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getLogPath() {
                return logPath;
            }

            public void setLogPath(String logPath) {
                this.logPath = logPath;
            }

            public int getLogRetentionDays() {
                return logRetentionDays;
            }

            public void setLogRetentionDays(int logRetentionDays) {
                this.logRetentionDays = logRetentionDays;
            }
        }
    }
}

