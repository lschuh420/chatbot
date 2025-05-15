package com.hhn.studyChat.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class RAGDocument {
    private String id;
    private String jobId;           // Referenz zum CrawlJob
    private String url;             // URL der Quelle
    private String title;           // Titel des Dokuments
    private String content;         // Textinhalt
    private String category;        // Kategorie (z.B. "studium", "forschung")
    private String[] embeddings;    // Vector-Embeddings für das Dokument
    private LocalDateTime createdAt;
    private String filePath;        // Pfad zur Originaldatei

    public static RAGDocument create(String jobId, String url, String title, String content, String category, String filePath) {
        return RAGDocument.builder()
                .id(UUID.randomUUID().toString())
                .jobId(jobId)
                .url(url)
                .title(title)
                .content(content)
                .category(category)
                .createdAt(LocalDateTime.now())
                .filePath(filePath)
                .build();
    }
}