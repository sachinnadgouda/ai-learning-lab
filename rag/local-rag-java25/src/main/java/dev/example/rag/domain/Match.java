package dev.example.rag.domain;

public record Match(String documentId, String documentName, String text, double score) {}
