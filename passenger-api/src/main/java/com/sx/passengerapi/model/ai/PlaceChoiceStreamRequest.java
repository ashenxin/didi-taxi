package com.sx.passengerapi.model.ai;

public record PlaceChoiceStreamRequest(long expectedRequestVersion, String placeChoiceId,
                                       String choiceName, String choiceCity) {
}
