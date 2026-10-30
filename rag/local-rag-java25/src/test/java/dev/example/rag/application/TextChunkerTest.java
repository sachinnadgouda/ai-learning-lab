package dev.example.rag.application;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TextChunkerTest {
  @Test void splitsWithOverlapAndStableIdentifiers() {
    var chunker=new TextChunker(5,2);
    var result=chunker.split("doc","abcdefghij");
    assertThat(result).extracting(c->c.text()).containsExactly("abcde","defgh","ghij");
    assertThat(result.getFirst().id()).isEqualTo(chunker.split("doc","abcdefghij").getFirst().id());
  }
  @Test void rejectsBlankInputAndInvalidConfiguration() {
    assertThatThrownBy(()->new TextChunker(4,4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new TextChunker(5,1).split("doc","  ")).isInstanceOf(IllegalArgumentException.class);
  }
}
