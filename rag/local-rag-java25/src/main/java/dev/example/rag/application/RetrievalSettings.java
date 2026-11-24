package dev.example.rag.application;

public record RetrievalSettings(int topK, double minRelevanceScore, double minQuestionCoverage) {}
