package com.assignment.notificationservice.unit;

import com.assignment.notificationservice.exceptions.MissingVariableException;
import com.assignment.notificationservice.exceptions.SmsBodyTooLongException;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.services.TemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    // --- Variable extraction ---

    @Test
    void extractVariables_findsAllVariables() {
        assertThat(renderer.extractVariables("Hello {{name}}, order {{orderId}} shipped"))
                .containsExactly("name", "orderId");
    }

    @Test
    void extractVariables_handlesSpacesInsideBraces() {
        assertThat(renderer.extractVariables("Hello {{ name }}")).containsExactly("name");
    }

    @Test
    void extractVariables_emptyString() {
        assertThat(renderer.extractVariables("")).isEmpty();
    }

    @Test
    void extractVariables_nullString() {
        assertThat(renderer.extractVariables(null)).isEmpty();
    }

    @Test
    void extractVariables_noVariables() {
        assertThat(renderer.extractVariables("Plain text with no variables")).isEmpty();
    }

    @Test
    void extractVariables_duplicateVariables() {
        assertThat(renderer.extractVariables("{{name}} and {{name}}")).containsExactly("name");
    }

    // --- Rendering ---

    @Test
    void render_substitutesAllVariables() {
        String out = renderer.render("Hello {{name}}, order {{orderId}}",
                Map.of("name", "Alice", "orderId", "123"), Channel.SMS);
        assertThat(out).isEqualTo("Hello Alice, order 123");
    }

    @Test
    void render_throwsOnMissingVariable() {
        assertThatThrownBy(() -> renderer.render("Hello {{name}}", Map.of(), Channel.SMS))
                .isInstanceOf(MissingVariableException.class)
                .hasMessageContaining("name")
                .extracting(e -> ((MissingVariableException) e).getVariableName())
                .isEqualTo("name");
    }

    @Test
    void render_ignoresExtraVariables() {
        String out = renderer.render("Hello {{name}}",
                Map.of("name", "Alice", "extra", "ignored"), Channel.SMS);
        assertThat(out).isEqualTo("Hello Alice");
    }

    @Test
    void render_htmlEscapesForEmail() {
        String out = renderer.render("Hello {{name}}",
                Map.of("name", "<script>alert('xss')</script>"), Channel.EMAIL);
        assertThat(out).isEqualTo("Hello &lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");
    }

    @Test
    void render_htmlEscapesAmpersandAndQuotesForEmail() {
        String out = renderer.render("{{v}}", Map.of("v", "Tom & \"Jerry\""), Channel.EMAIL);
        assertThat(out).isEqualTo("Tom &amp; &quot;Jerry&quot;");
    }

    @Test
    void render_doesNotEscapeTheTemplateTextItself() {
        // Only substituted values are untrusted; the tenant's own markup is kept.
        String out = renderer.render("<b>Hi {{name}}</b>", Map.of("name", "Al"), Channel.EMAIL);
        assertThat(out).isEqualTo("<b>Hi Al</b>");
    }

    @Test
    void render_noEscapingForSms() {
        String out = renderer.render("Hello {{name}}", Map.of("name", "<b>Alice</b>"), Channel.SMS);
        assertThat(out).isEqualTo("Hello <b>Alice</b>");
    }

    @Test
    void render_noEscapingForPush() {
        String out = renderer.render("Hello {{name}}", Map.of("name", "<b>Alice</b>"), Channel.PUSH);
        assertThat(out).isEqualTo("Hello <b>Alice</b>");
    }

    @Test
    void render_nullTemplateReturnsNull() {
        assertThat(renderer.render(null, Map.of(), Channel.EMAIL)).isNull();
    }

    @Test
    void render_handlesSpecialRegexChars() {
        assertThat(renderer.render("Price: {{amount}}", Map.of("amount", "$100.00"), Channel.SMS))
                .isEqualTo("Price: $100.00");
        assertThat(renderer.render("Path: {{p}}", Map.of("p", "C:\\temp\\$1"), Channel.SMS))
                .isEqualTo("Path: C:\\temp\\$1");
    }

    @Test
    void render_handlesSpacesInsideBraces() {
        assertThat(renderer.render("Hi {{ name }}!", Map.of("name", "Bo"), Channel.SMS))
                .isEqualTo("Hi Bo!");
    }

    @Test
    void render_nullVariablesMapFailsOnlyIfAPlaceholderExists() {
        assertThat(renderer.render("No placeholders", null, Channel.SMS)).isEqualTo("No placeholders");
        assertThatThrownBy(() -> renderer.render("Hi {{name}}", null, Channel.SMS))
                .isInstanceOf(MissingVariableException.class);
    }

    // --- SMS length ---

    @Test
    void validateSmsLength_passesUnderLimit() {
        assertThatCode(() -> renderer.validateSmsLength("x".repeat(479))).doesNotThrowAnyException();
    }

    @Test
    void validateSmsLength_failsOverLimit() {
        assertThatThrownBy(() -> renderer.validateSmsLength("x".repeat(481)))
                .isInstanceOf(SmsBodyTooLongException.class)
                .hasMessage("SMS body too long: 481 chars (max 480)");
    }

    @Test
    void validateSmsLength_exactLimit() {
        assertThatCode(() -> renderer.validateSmsLength("x".repeat(480))).doesNotThrowAnyException();
    }
}
