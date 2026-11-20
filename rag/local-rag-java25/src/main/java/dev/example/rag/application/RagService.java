package dev.example.rag.application;
import dev.example.rag.application.port.*;
import dev.example.rag.domain.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class RagService {
  private static final Logger log=LoggerFactory.getLogger(RagService.class);
  private final DocumentReader reader; private final EmbeddingPort embeddings; private final VectorStorePort vectors; private final AnswerPort answers; private final GeneralAnswerPort generalAnswers; private final TextChunker chunker; private final int topK; private final double minRelevanceScore; private final double minQuestionCoverage;
  public RagService(DocumentReader reader,EmbeddingPort embeddings,VectorStorePort vectors,AnswerPort answers,GeneralAnswerPort generalAnswers,TextChunker chunker,int topK,double minRelevanceScore,double minQuestionCoverage) {
    this.reader=reader;this.embeddings=embeddings;this.vectors=vectors;this.answers=answers;this.generalAnswers=generalAnswers;this.chunker=chunker;this.topK=topK;this.minRelevanceScore=minRelevanceScore;this.minQuestionCoverage=minQuestionCoverage;
  }
  public IngestResult ingestUploadedPdf(String filename,byte[] pdfBytes) {
    String name=filename==null ? "Policy PDF" : filename.replace('\\','/');
    name=name.substring(name.lastIndexOf('/')+1).strip();
    if(name.isBlank()) name="Policy PDF";
    return ingestDocument(UUID.nameUUIDFromBytes(pdfBytes).toString(),name,reader.readPdfBytes(pdfBytes));
  }
  public QueryResult askWithoutRag(String question) {
    log.info("General query started");
    String answer=generalAnswers.answerWithoutRag(question);
    log.info("General query completed");
    return new QueryResult(answer,List.of());
  }
  public QueryResult ask(String question) {
    log.info("RAG query started");
    List<Match> relevant=findPolicyMatches(question);
    if(relevant.isEmpty()) {
      log.info("RAG query completed without a policy match");
      return new QueryResult(null,List.of());
    }
    StringBuilder context=new StringBuilder();
    for(int i=0;i<relevant.size();i++) context.append("[Source ").append(i+1).append("] ").append(relevant.get(i).text()).append("\n\n");
    String answer=answers.answer(question,context.toString());
    log.info("RAG query completed matches={} grounded=true",relevant.size());
    return new QueryResult(answer,relevant);
  }
  public List<Match> findPolicyMatches(String question) {
    List<Match> relevant=vectors.search(embeddings.embed(question),topK).stream()
        .filter(match->match.score()>=minRelevanceScore).toList();
    return isQuestionCovered(question,relevant) ? relevant : List.of();
  }
  private IngestResult ingestDocument(String documentId,String documentName,String text) {
    log.info("Ingestion started documentId={}",documentId);
    List<Chunk> chunks=chunker.split(documentId,documentName,text);
    List<List<Double>> embedded=chunks.stream().map(c->embeddings.embed(c.text())).toList();
    vectors.upsert(chunks,embedded);
    log.info("Ingestion completed documentId={} chunks={}",documentId,chunks.size());
    return new IngestResult(documentId,chunks.size());
  }
  private boolean isQuestionCovered(String question,List<Match> matches) {
    String sourceText=matches.stream().map(Match::text).reduce("",(a,b)->a+" "+b).toLowerCase(Locale.ROOT);
    List<String> tokens=extractTokens(question);
    if(tokens.isEmpty()) return true;
    long covered=tokens.stream().filter(sourceText::contains).count();
    return ((double)covered/(double)tokens.size())>=minQuestionCoverage;
  }
  private List<String> extractTokens(String question) {
    List<String> tokens=new ArrayList<>();
    for(String part:question.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if(part.length()<4) continue;
      String normalized=part.endsWith("s") && part.length()>4 ? part.substring(0,part.length()-1) : part;
      tokens.add(normalized);
    }
    return tokens;
  }
  public record IngestResult(String documentId,int chunks) {}
  public record QueryResult(String answer,List<Match> sources) {}
}
