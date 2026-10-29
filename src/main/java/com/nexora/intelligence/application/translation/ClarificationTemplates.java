package com.nexora.intelligence.application.translation;

import com.nexora.intelligence.domain.question.Language;
import com.nexora.intelligence.domain.request.DraftIssue;

import java.util.List;

/** Nexora's own clarification questions for ambiguities found by deterministic rules. Never echo user values. */
final class ClarificationTemplates {

    private ClarificationTemplates() {
    }

    static String forIssues(List<DraftIssue> issues, Language language) {
        boolean numbers = issues.stream().allMatch(i -> i.code() == DraftIssue.Code.NUMBER_AMBIGUOUS);
        if (numbers) {
            return language == Language.EN
                    ? "A number in your question can be read in two ways. Could you write it without thousands "
                    + "separators, for example 1000 instead of 1,000?"
                    : "Un número de tu pregunta puede leerse de dos formas. ¿Podrías escribirlo sin separador de "
                    + "miles, por ejemplo 1000 en lugar de 1.000?";
        }
        return language == Language.EN
                ? "Part of your question can be read in more than one way. Could you rephrase it more precisely?"
                : "Parte de tu pregunta admite más de una lectura. ¿Podrías formularla con más precisión?";
    }
}
