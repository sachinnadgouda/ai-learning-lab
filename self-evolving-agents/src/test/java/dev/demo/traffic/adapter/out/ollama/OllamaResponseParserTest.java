package dev.demo.traffic.adapter.out.ollama;

import static org.junit.jupiter.api.Assertions.*;

import dev.demo.traffic.domain.evolution.EvolutionDecisionType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OllamaResponseParserTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void parsesIncreaseProposal() {
        var p = OllamaResponseParser.parse(mapper,
                "{\"decision\":\"propose\",\"admissionPerSecond\":60,\"maxQueue\":800,\"rationale\":\"scale up\"}");
        assertNull(p.error());
        assertEquals(EvolutionDecisionType.PROPOSE, p.decision().type());
        assertEquals(60, p.decision().admissionPerSecond());
        assertEquals("scale up", p.decision().rationale());
    }

    @Test void parsesNoChangeAndInsufficientData() {
        assertEquals(EvolutionDecisionType.NO_CHANGE,
                OllamaResponseParser.parse(mapper, "{\"decision\":\"NO_CHANGE\",\"rationale\":\"fine\"}").decision().type());
        assertEquals(EvolutionDecisionType.INSUFFICIENT_DATA,
                OllamaResponseParser.parse(mapper, "{\"decision\":\"INSUFFICIENT_DATA\"}").decision().type());
    }

    @Test void stripsMarkdownFences() {
        var p = OllamaResponseParser.parse(mapper, "```json\n{\"decision\":\"NO_CHANGE\",\"rationale\":\"x\"}\n```");
        assertNotNull(p.decision());
    }

    @Test void rejectsMalformedOrIncompleteAnswers() {
        assertNotNull(OllamaResponseParser.parse(mapper, "not json").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "[1,2]").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "{\"decision\":\"DROP_TABLES\"}").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "{\"decision\":\"PROPOSE\",\"admissionPerSecond\":\"fast\",\"maxQueue\":5}").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "{\"decision\":\"PROPOSE\",\"admissionPerSecond\":5}").error());
        assertNotNull(OllamaResponseParser.parse(mapper, "{\"decision\":\"PROPOSE\",\"admissionPerSecond\":1.5,\"maxQueue\":5}").error());
    }
}
