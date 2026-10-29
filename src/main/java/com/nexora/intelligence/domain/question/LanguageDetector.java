package com.nexora.intelligence.domain.question;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Best-effort, deterministic guess of a question's language from frequent words and Spanish-only characters. Used
 * only to word Nexora's own messages; it never changes how a question is interpreted. Empty when undecided.
 */
public final class LanguageDetector {

    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");
    private static final Set<String> SPANISH = Set.of("el", "la", "los", "las", "de", "del", "por", "para", "que",
            "en", "y", "con", "cual", "cuales", "cuanto", "cuantos", "cuantas", "mes", "ano", "ventas", "total",
            "mayor", "menor", "entre", "como", "dame", "muestra", "mostrar", "quiero", "ultimo", "ultimos", "hoy",
            "ayer", "semana", "trimestre", "pasado", "este", "esta", "segun", "cada", "una", "uno", "sin", "mas");
    private static final Set<String> ENGLISH = Set.of("the", "of", "by", "for", "what", "which", "how", "many",
            "much", "and", "with", "per", "month", "year", "sales", "show", "me", "give", "last", "this", "today",
            "yesterday", "week", "quarter", "between", "more", "than", "less", "each", "top", "from", "in", "is", "are",
            "were", "was", "total", "orders", "without");

    private LanguageDetector() {
    }

    public static Optional<Language> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.indexOf('¿') >= 0 || text.indexOf('¡') >= 0 || text.toLowerCase(Locale.ROOT).indexOf('ñ') >= 0) {
            return Optional.of(Language.ES);
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        int spanish = 0;
        int english = 0;
        var words = WORD.matcher(folded);
        while (words.find()) {
            String word = words.group();
            spanish += SPANISH.contains(word) ? 1 : 0;
            english += ENGLISH.contains(word) ? 1 : 0;
        }
        if (spanish == english) {
            return Optional.empty();
        }
        return Optional.of(spanish > english ? Language.ES : Language.EN);
    }
}
