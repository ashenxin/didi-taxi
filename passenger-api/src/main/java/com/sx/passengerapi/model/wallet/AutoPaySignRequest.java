package com.sx.passengerapi.model.wallet;

import jakarta.validation.constraints.NotBlank;

public class AutoPaySignRequest {
    @NotBlank
    private String channel;
    private String signScene;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSignScene() {
        return signScene;
    }

    public void setSignScene(String signScene) {
        this.signScene = signScene;
    }
}
