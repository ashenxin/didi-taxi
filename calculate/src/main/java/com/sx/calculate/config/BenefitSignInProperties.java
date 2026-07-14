package com.sx.calculate.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "benefit.sign-in")
public class BenefitSignInProperties {
    private Boolean enabled;
    private String timezone;
    private String cycle;
    private Integer displayDays;
    private SignDays signDays = new SignDays();
    private Rewards rewards = new Rewards();

    @Getter
    @Setter
    public static class SignDays {
        private Integer startDayOfMonth;
        private Integer endDayOfMonth;
    }

    @Getter
    @Setter
    public static class Rewards {
        private DefaultReward defaultReward = new DefaultReward();
        private List<ContinuousReward> continuous = new ArrayList<>();

        public DefaultReward getDefault() {
            return defaultReward;
        }

        public void setDefault(DefaultReward defaultReward) {
            this.defaultReward = defaultReward;
        }
    }

    @Getter
    @Setter
    public static class DefaultReward {
        private Integer points;
    }

    @Getter
    @Setter
    public static class ContinuousReward {
        private Integer everyDays;
        private Integer points;
        private Boolean includeDefault;
    }
}
