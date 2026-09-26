package com.assignment.notificationservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateGlobalLimitRequest(
        @NotNull @Min(1) Integer ratePerSec,
        @NotNull @Min(1) Integer burst
) {

    @JsonIgnore
    @AssertTrue(message = "burst must be >= ratePerSec")
    public boolean isBurstValid() {
        return ratePerSec == null || burst == null || burst >= ratePerSec;
    }
}
