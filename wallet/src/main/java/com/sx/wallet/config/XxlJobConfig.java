package com.sx.wallet.config;

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
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        if (properties.getAdmin() == null
                || !StringUtils.hasText(properties.getAdmin().getAddresses())
                || properties.getExecutor() == null
                || !StringUtils.hasText(properties.getExecutor().getAppname())) {
            throw new IllegalStateException("xxl.job已开启，但wallet执行器配置不完整");
        }
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(properties.getAdmin().getAddresses().trim());
        if (StringUtils.hasText(properties.getAccessToken())) {
            executor.setAccessToken(properties.getAccessToken().trim());
        }
        Executor executorProperties = properties.getExecutor();
        executor.setAppname(executorProperties.getAppname().trim());
        if (StringUtils.hasText(executorProperties.getAddress())) {
            executor.setAddress(executorProperties.getAddress().trim());
        }
        executor.setPort(executorProperties.getPort() > 0 ? executorProperties.getPort() : 9997);
        executor.setLogPath(executorProperties.getLogPath());
        executor.setLogRetentionDays(executorProperties.getLogRetentionDays());
        return executor;
    }

    @ConfigurationProperties(prefix = "xxl.job")
    public static class XxlJobProperties {
        private boolean enabled;
        private String accessToken;
        private Admin admin;
        private Executor executor;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public Admin getAdmin() { return admin; }
        public void setAdmin(Admin admin) { this.admin = admin; }
        public Executor getExecutor() { return executor; }
        public void setExecutor(Executor executor) { this.executor = executor; }
    }

    public static class Admin {
        private String addresses;
        public String getAddresses() { return addresses; }
        public void setAddresses(String addresses) { this.addresses = addresses; }
    }

    public static class Executor {
        private String appname = "wallet-executor";
        private String address;
        private int port = 9997;
        private String logPath = "./logs/xxl-job";
        private int logRetentionDays = 7;
        public String getAppname() { return appname; }
        public void setAppname(String appname) { this.appname = appname; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getLogPath() { return logPath; }
        public void setLogPath(String logPath) { this.logPath = logPath; }
        public int getLogRetentionDays() { return logRetentionDays; }
        public void setLogRetentionDays(int logRetentionDays) { this.logRetentionDays = logRetentionDays; }
    }
}
