package dev.demo.traffic.adapter.out.ollama;

import dev.demo.traffic.domain.evolution.EvolutionDecision;
import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses the model's JSON answer (the content of the chat message) into an {@link EvolutionDecision}. */
public final class OllamaResponseParser {
    public record Parsed(EvolutionDecision decision, String error) {}

    private OllamaResponseParser() {}

    public static Parsed parse(JsonMapper mapper, String content) {
        if (content == null || content.isBlank()) return new Parsed(null, "Empty model response");
        String json = stripFences(content.trim());
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (RuntimeException e) {
            return new Parsed(null, "Model response is not valid JSON");
        }
        if (node == null || !node.isObject()) return new Parsed(null, "Model response is not a JSON object");
        String raw = node.path("decision").asString("").trim().toUpperCase(Locale.ROOT);
        EvolutionDecisionType type;
        try {
            type = EvolutionDecisionType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, "Unsupported AI decision: " + truncate(raw));
        }
        String rationale = node.path("rationale").asString("No rationale");
        if (type != EvolutionDecisionType.PROPOSE) return new Parsed(new EvolutionDecision(type, null, null, rationale), null);
        JsonNode admission = node.path("admissionPerSecond"), queue = node.path("maxQueue");
        if (!admission.isIntegralNumber() || !queue.isIntegralNumber() || !admission.canConvertToInt() || !queue.canConvertToInt())
            return new Parsed(null, "PROPOSE requires integer admissionPerSecond and maxQueue");
        return new Parsed(new EvolutionDecision(type, admission.intValue(), queue.intValue(), rationale), null);
    }

    private static String stripFences(String s) {
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) return s.substring(nl + 1, end).trim();
        }
        return s;
    }

    private static String truncate(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
