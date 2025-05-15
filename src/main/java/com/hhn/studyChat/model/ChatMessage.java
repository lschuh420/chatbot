package com.hhn.studyChat.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessage {
    private String id;
    private String jobId;        // Referenz zum CrawlJob
    private String userMessage;  // Nachricht vom Benutzer
    private String aiResponse;   // Antwort des AI-Systems
    private LocalDateTime timestamp;
    private boolean processed;
}