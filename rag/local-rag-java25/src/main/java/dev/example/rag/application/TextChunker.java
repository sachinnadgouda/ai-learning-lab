package dev.example.rag.application;

import dev.example.rag.domain.Chunk;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TextChunker {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final int size;
    private final int overlap;

    public TextChunker(int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Invalid chunk settings");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<Chunk> split(String documentId, String documentName, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Document contains no text");
        }
        String normalized = WHITESPACE.matcher(text).replaceAll(" ").trim();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        for (int ordinal = 0; start < normalized.length(); ordinal++) {
            int end = Math.min(start + size, normalized.length());
            result.add(new Chunk(chunkId(documentId, ordinal), documentId, documentName,
                    normalized.substring(start, end), ordinal));
            if (end == normalized.length()) {
                break;
            }
            start = end - overlap;
        }
        return List.copyOf(result);
    }

    private static String chunkId(String documentId, int ordinal) {
        return UUID.nameUUIDFromBytes((documentId + ":" + ordinal).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
