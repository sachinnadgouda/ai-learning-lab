package dev.example.rag.application.port;
import java.util.List;
public interface EmbeddingPort { List<Double> embed(String text); }
