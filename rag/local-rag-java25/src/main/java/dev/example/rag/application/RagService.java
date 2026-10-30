package dev.example.rag.application;
import dev.example.rag.application.port.*;
import dev.example.rag.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class RagService {
  private static final Logger log=LoggerFactory.getLogger(RagService.class);
  private static final String FALLBACK_ALERT="This question is not clearly covered by the indexed policy document. The answer may come from general model knowledge; please verify with your manager or HR before acting.";
  private final DocumentReader reader; private final EmbeddingPort embeddings; private final VectorStorePort vectors; private final AnswerPort answers; private final GeneralAnswerPort generalAnswers; private final TextChunker chunker; private final int topK; private final double minRelevanceScore; private final double minQuestionCoverage;
  public RagService(DocumentReader reader,EmbeddingPort embeddings,VectorStorePort vectors,AnswerPort answers,GeneralAnswerPort generalAnswers,TextChunker chunker,int topK,double minRelevanceScore,double minQuestionCoverage) {
    this.reader=reader;this.embeddings=embeddings;this.vectors=vectors;this.answers=answers;this.generalAnswers=generalAnswers;this.chunker=chunker;this.topK=topK;this.minRelevanceScore=minRelevanceScore;this.minQuestionCoverage=minQuestionCoverage;
  }
  public IngestResult ingest(String url) {
    return ingestDocument(url,reader.readPdf(url));
  }
  public IngestResult ingestUploadedPdf(String filename,byte[] pdfBytes) {
    String source=filename==null || filename.isBlank() ? UUID.randomUUID().toString() : filename;
    return ingestDocument("upload:"+source,reader.readPdfBytes(pdfBytes));
  }
  public QueryResult askWithoutRag(String question) {
    log.info("General query started");
    String answer=generalAnswers.answerWithoutRag(question);
    log.info("General query completed");
    return new QueryResult(answer,List.of(),false,"internet","Generated without RAG context; this is a general answer.");
  }
  public QueryResult ask(String question) {
    log.info("RAG query started");
    List<Match> matches=vectors.search(embeddings.embed(question),topK);
    List<Match> relevant=matches.stream().filter(match->match.score()>=minRelevanceScore).toList();
    if(relevant.isEmpty() || !isQuestionCovered(question,relevant)) {
      String answer=generalAnswers.answerWithoutRag(question);
      log.info("RAG query completed matches={} grounded=false",relevant.size());
      return new QueryResult(answer,relevant,false,"internet",FALLBACK_ALERT);
    }
    StringBuilder context=new StringBuilder();
    for(int i=0;i<relevant.size();i++) context.append("[Source ").append(i+1).append("] ").append(relevant.get(i).text()).append("\n\n");
    String answer=answers.answer(question,context.toString());
    log.info("RAG query completed matches={} grounded=true",relevant.size());
    return new QueryResult(answer,relevant,true,"rag",null);
  }
  private IngestResult ingestDocument(String sourceId,String text) {
    String documentId=UUID.nameUUIDFromBytes(sourceId.getBytes(StandardCharsets.UTF_8)).toString();
    log.info("Ingestion started documentId={}",documentId);
    List<Chunk> chunks=chunker.split(documentId,text);
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
  public record QueryResult(String answer,List<Match> sources,boolean grounded,String source,String advisory) {}
}
