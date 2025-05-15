package com.hhn.studyChat.controller;

import com.hhn.studyChat.model.ChatMessage;
import com.hhn.studyChat.model.CrawlJob;
import com.hhn.studyChat.service.ChatService;
import com.hhn.studyChat.service.CrawlerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
public class ChatController {

    private final ChatService chatService;
    private final CrawlerService crawlerService;

    @Autowired
    public ChatController(ChatService chatService, CrawlerService crawlerService) {
        this.chatService = chatService;
        this.crawlerService = crawlerService;
    }

    // Chat-Seite anzeigen
    @GetMapping("/chat")
    public String chatPage(Model model) {
        // Liste aller abgeschlossenen Crawl-Jobs für die Auswahl
        model.addAttribute("crawlJobs", crawlerService.getCompletedJobs());
        return "chat";
    }

    // API-Endpunkt zum Senden von Nachrichten und Erhalten von Antworten
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        String jobId = request.get("jobId");
        String message = request.get("message");

        // Validierung
        if (jobId == null || message == null || message.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Jobid und Nachricht sind erforderlich");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        // Prüfen, ob der Job existiert
        CrawlJob job = crawlerService.getJob(jobId);
        if (job == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Job nicht gefunden");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        // Nachricht erstellen
        ChatMessage chatMessage = ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .jobId(jobId)
                .userMessage(message)
                .timestamp(LocalDateTime.now())
                .processed(false)
                .build();

        // Antwort vom LLM erhalten
        ChatMessage processedMessage = chatService.processMessage(chatMessage);

        // Antwort zurückgeben
        Map<String, Object> response = new HashMap<>();
        response.put("messageId", processedMessage.getId());
        response.put("response", processedMessage.getAiResponse());
        response.put("timestamp", processedMessage.getTimestamp().toString());

        return ResponseEntity.ok(response);
    }

    // Optional: Endpunkt zum Abrufen des Chat-Verlaufs
    @GetMapping("/api/chat/history/{jobId}")
    @ResponseBody
    public ResponseEntity<Object> getChatHistory(@PathVariable String jobId) {
        return ResponseEntity.ok(chatService.getChatHistoryForJob(jobId));
    }
}