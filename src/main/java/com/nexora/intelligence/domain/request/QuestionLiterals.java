package com.nexora.intelligence.domain.request;

import com.nexora.intelligence.domain.question.Language;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative literal policy of v1: a filter value is accepted only if the question itself contains it.
 * <ul>
 *   <li>Text: found ignoring case and accents, as a whole word sequence; the value used is the span <em>as written
 *       in the question</em>, never the model's spelling.</li>
 *   <li>Numbers: found as a numeral. Thousands and decimal separators follow the question's language (Spanish
 *       {@code 1.000,5}, English {@code 1,000.5}); when the language is unknown and a numeral reads differently under
 *       each convention (e.g. {@code 1.000}), the match is ambiguous. Numbers written in words are not matched.</li>
 * </ul>
 * Nothing here is logged; the question never leaves this object.
 */
final class QuestionLiterals {

    enum NumberMatch { FOUND, AMBIGUOUS, NOT_FOUND }

    private static final Pattern NUMERAL = Pattern.compile("(?<![\\p{L}\\p{N}.,])(-?)(\\d+(?:[.,]\\d+)*)(?![\\p{L}\\p{N}])");
    private static final Pattern DOT_THOUSANDS = Pattern.compile("\\d{1,3}(\\.\\d{3})*(,\\d+)?|\\d+(,\\d+)?");
    private static final Pattern COMMA_THOUSANDS = Pattern.compile("\\d{1,3}(,\\d{3})*(\\.\\d+)?|\\d+(\\.\\d+)?");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final String question;
    private final String normalized;
    private final int[] origin;
    private final List<List<BigDecimal>> numerals;

    QuestionLiterals(String question, Optional<Language> language) {
        this.question = Objects.requireNonNull(question, "question");
        StringBuilder text = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        for (int i = 0; i < question.length(); ) {
            int codePoint = question.codePointAt(i);
            String folded = fold(new String(Character.toChars(codePoint)));
            for (int k = 0; k < folded.length(); k++) {
                map.add(i);
            }
            text.append(folded);
            i += Character.charCount(codePoint);
        }
        this.normalized = text.toString();
        this.origin = map.stream().mapToInt(Integer::intValue).toArray();
        this.numerals = numerals(question, language == null ? Optional.empty() : language);
    }

    /** The span of the question that matches {@code value}, as the user wrote it; empty if absent. */
    Optional<String> findText(String value) {
        String needle = fold(value.strip());
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        for (int from = normalized.indexOf(needle); from >= 0; from = normalized.indexOf(needle, from + 1)) {
            int to = from + needle.length();
            if (isBoundary(from - 1) && isBoundary(to)) {
                int start = origin[from];
                int last = origin[to - 1];
                int end = last + Character.charCount(question.codePointAt(last));
                return Optional.of(question.substring(start, end));
            }
        }
        return Optional.empty();
    }

    NumberMatch findNumber(BigDecimal value) {
        boolean ambiguous = false;
        for (List<BigDecimal> readings : numerals) {
            boolean matches = readings.stream().anyMatch(r -> r.compareTo(value) == 0);
            if (matches && readings.size() == 1) {
                return NumberMatch.FOUND;
            }
            ambiguous |= matches;
        }
        return ambiguous ? NumberMatch.AMBIGUOUS : NumberMatch.NOT_FOUND;
    }

    private boolean isBoundary(int index) {
        if (index < 0 || index >= normalized.length()) {
            return true;
        }
        return !Character.isLetterOrDigit(normalized.charAt(index));
    }

    private static String fold(String text) {
        return COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /** Each numeral of the question with its distinct readings (one when unambiguous, two when ambiguous). */
    private static List<List<BigDecimal>> numerals(String question, Optional<Language> language) {
        List<List<BigDecimal>> result = new ArrayList<>();
        Matcher matcher = NUMERAL.matcher(question);
        while (matcher.find()) {
            String sign = matcher.group(1);
            String digits = matcher.group(2);
            Optional<BigDecimal> dot = DOT_THOUSANDS.matcher(digits).matches()
                    ? Optional.of(new BigDecimal(sign + digits.replace(".", "").replace(',', '.'))) : Optional.empty();
            Optional<BigDecimal> comma = COMMA_THOUSANDS.matcher(digits).matches()
                    ? Optional.of(new BigDecimal(sign + digits.replace(",", ""))) : Optional.empty();
            Optional<BigDecimal> preferred = language.map(l -> l == Language.ES ? dot : comma).orElse(Optional.empty());
            if (preferred.isPresent()) {
                result.add(List.of(preferred.get()));
            } else if (dot.isPresent() && comma.isPresent()) {
                result.add(dot.get().compareTo(comma.get()) == 0 ? List.of(dot.get())
                        : language.isPresent() ? List.of() : List.of(dot.get(), comma.get()));
            } else {
                dot.or(() -> comma).ifPresent(v -> result.add(List.of(v)));
            }
        }
        return result;
    }
}
