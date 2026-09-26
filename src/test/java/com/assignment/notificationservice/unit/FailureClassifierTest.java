package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.dtos.SendResult.PermanentFailure;
import com.assignment.notificationservice.dtos.SendResult.Success;
import com.assignment.notificationservice.dtos.SendResult.TransientFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.assignment.notificationservice.utils.FailureClassifier.isPermanent;
import static com.assignment.notificationservice.utils.FailureClassifier.isTransient;
import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void success_isNeitherTransientNorPermanent() {
        assertThat(isTransient(new Success("id"))).isFalse();
        assertThat(isPermanent(new Success("id"))).isFalse();
    }

    @Test
    void transient_isTransient() {
        assertThat(isTransient(new TransientFailure("TIMEOUT", ""))).isTrue();
        assertThat(isPermanent(new TransientFailure("TIMEOUT", ""))).isFalse();
    }

    @Test
    void transientResult_isTransientEvenWithAPermanentLookingCode() {
        // The result type wins for transient: the provider said "try again".
        assertThat(isTransient(new TransientFailure("INVALID_RECIPIENT", ""))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_RECIPIENT", "INVALID_TOKEN", "UNSUBSCRIBED", "BAD_PAYLOAD",
            "BLOCKED", "CARRIER_REJECTED", "SPAM_DETECTED"})
    void permanent_knownCodes_arePermanent(String code) {
        assertThat(isPermanent(new PermanentFailure(code, ""))).isTrue();
        assertThat(isTransient(new PermanentFailure(code, ""))).isFalse();
    }

    @Test
    void permanent_unknownCode_isTreatedAsTransient() {
        assertThat(isTransient(new PermanentFailure("UNKNOWN_CODE", ""))).isTrue();
        assertThat(isPermanent(new PermanentFailure("UNKNOWN_CODE", ""))).isFalse();
    }
}
