package dev.example.rag.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TextChunkerTest {
    @Test
    void splitsWithOverlapAndStableIdentifiers() {
        var chunker = new TextChunker(5, 2);

        var result = chunker.split("doc", "policy.pdf", "abcdefghij");

        assertThat(result).extracting(c -> c.text()).containsExactly("abcde", "defgh", "ghij");
        assertThat(result.getFirst().id())
                .isEqualTo(chunker.split("doc", "policy.pdf", "abcdefghij").getFirst().id());
    }

    @Test
    void normalizesWhitespace() {
        var result = new TextChunker(50, 2).split("doc", "policy.pdf", "a \n\t b   c");

        assertThat(result).extracting(c -> c.text()).containsExactly("a b c");
    }

    @Test
    void rejectsBlankInputAndInvalidConfiguration() {
        assertThatThrownBy(() -> new TextChunker(4, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(5, 1).split("doc", "policy.pdf", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
