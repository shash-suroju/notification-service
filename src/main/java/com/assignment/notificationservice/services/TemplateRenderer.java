package com.assignment.notificationservice.services;

import com.assignment.notificationservice.constants.TemplateConstants;
import com.assignment.notificationservice.exceptions.MissingVariableException;
import com.assignment.notificationservice.exceptions.SmsBodyTooLongException;
import com.assignment.notificationservice.models.enums.Channel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{variable}}} substitution. Pure logic — the {@code @Component} only makes it
 * injectable; nothing here touches Spring, so unit tests just call {@code new}.
 *
 * <p>EMAIL values are HTML-escaped because email bodies are rendered as HTML by mail
 * clients; a customer name like {@code <script>} must arrive as text, not markup.
 * SMS and PUSH are plain text, so values pass through untouched.
 */
@Component
public class TemplateRenderer {

    private static final int SMS_MAX_LENGTH = TemplateConstants.SMS_MAX_LENGTH;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile(TemplateConstants.VARIABLE_REGEX);

    /**
     * All distinct variable names in {@code templateText}, in order of first appearance.
     * {@code "Hello {{name}}, order {{orderId}}"} → {@code [name, orderId]}.
     */
    public Set<String> extractVariables(String templateText) {
        if (templateText == null) {
            return Set.of();
        }
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VARIABLE_PATTERN.matcher(templateText);
        while (m.find()) {
            vars.add(m.group(1));
        }
        return vars;
    }

    /**
     * Substitutes every placeholder. Extra variables are ignored.
     *
     * @throws MissingVariableException if a placeholder has no value
     */
    public String render(String templateText, Map<String, String> variables, Channel channel) {
        if (templateText == null) {
            return null;
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;

        Matcher m = VARIABLE_PATTERN.matcher(templateText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String value = vars.get(varName);
            if (value == null) {
                throw new MissingVariableException(varName);
            }
            String safe = channel == Channel.EMAIL ? escapeHtml(value) : value;
            // quoteReplacement: values containing $ or \ must not be read as group references
            m.appendReplacement(sb, Matcher.quoteReplacement(safe));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** @throws SmsBodyTooLongException if the rendered body exceeds {@value #SMS_MAX_LENGTH} chars */
    public void validateSmsLength(String renderedBody) {
        if (renderedBody != null && renderedBody.length() > SMS_MAX_LENGTH) {
            throw new SmsBodyTooLongException(renderedBody.length(), SMS_MAX_LENGTH);
        }
    }

    private static String escapeHtml(String input) {
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
