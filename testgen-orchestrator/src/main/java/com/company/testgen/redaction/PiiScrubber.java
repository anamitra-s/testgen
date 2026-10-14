package com.company.testgen.redaction;

import com.company.testgen.model.RedactionReport;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces sensitive PII (emails, phones, JWTs, card numbers, national IDs)
 * with typed tokens (§6.9).
 * <p>
 * Typed tokens preserve the semantic shape of the content (e.g. {@code [REDACTED:EMAIL]})
 * so downstream LLM prompts still understand the element's purpose without transmitting
 * actual customer values.
 */
public class PiiScrubber {

    public record Rule(String name, Pattern pattern, Predicate<String> validator) {
        public String apply(String input, RedactionReport.Builder report) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            Matcher matcher = pattern.matcher(input);
            if (!matcher.find()) {
                return input;
            }

            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            matcher.reset();
            while (matcher.find()) {
                String match = matcher.group();
                if (validator == null || validator.test(match)) {
                    sb.append(input, lastEnd, matcher.start());
                    sb.append("[REDACTED:").append(name).append("]");
                    if (report != null) {
                        report.ruleApplied(name);
                    }
                    lastEnd = matcher.end();
                }
            }
            sb.append(input, lastEnd, input.length());
            return sb.toString();
        }
    }

    private static Rule rule(String name, String regex) {
        return new Rule(name, Pattern.compile(regex), null);
    }

    private static Rule rule(String name, String regex, Predicate<String> validator) {
        return new Rule(name, Pattern.compile(regex), validator);
    }

    private static final List<Rule> RULES = List.of(
            rule("JWT",     "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            rule("BEARER",  "(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}"),
            rule("EMAIL",   "[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"),
            rule("ID",      "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"),
            rule("PAN",     "[A-Z]{5}\\d{4}[A-Z]"),          // India PAN
            rule("CARD",    "\\b(?:\\d[ -]*?){13,19}\\b", PiiScrubber::luhnValid),
            rule("AADHAAR", "\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b(?!\\s?\\d)"),
            rule("PHONE",   "(?<!\\w)\\+?\\d[\\d\\s().-]{7,}\\d(?!\\w)", PiiScrubber::phoneValid)
    );

    /**
     * Scrubs PII from the provided string and replaces it with typed tokens.
     * Truncates text exceeding {@link RedactionRules#MAX_TEXT_LEN}.
     */
    public String scrub(String in, RedactionReport.Builder r) {
        if (in == null) {
            return null;
        }
        String out = in;
        for (Rule rule : RULES) {
            out = rule.apply(out, r);
        }

        if (out.length() > RedactionRules.MAX_TEXT_LEN) {
            if (r != null) {
                r.textTruncated();
            }
            return out.substring(0, RedactionRules.MAX_TEXT_LEN) + "…";
        }
        return out;
    }

    /**
     * Validates card numbers using the standard Luhn algorithm (§6.9).
     */
    public static boolean luhnValid(String numberStr) {
        if (numberStr == null) {
            return false;
        }
        String digits = numberStr.replaceAll("\\D", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    /**
     * Distinguishes valid phone numbers from ISO timestamps and short numbers.
     */
    public static boolean phoneValid(String text) {
        if (text == null) {
            return false;
        }
        // Avoid matching ISO dates YYYY-MM-DD
        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return false;
        }
        int digitCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                digitCount++;
            }
        }
        return digitCount >= 7 && digitCount <= 15;
    }
}
