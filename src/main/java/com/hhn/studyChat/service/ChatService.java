package com.hhn.studyChat.service;

import com.hhn.studyChat.model.ChatMessage;
import com.hhn.studyChat.model.RAGDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final Map<String, List<ChatMessage>> chatHistory = new ConcurrentHashMap<>();
    private final RAGService ragService;

    @Autowired
    public ChatService(RAGService ragService) {
        this.ragService = ragService;
    }

    /**
     * Verarbeitet eine Nachricht und erhält eine Antwort vom LLM
     */
    public ChatMessage processMessage(ChatMessage message) {
        // Relevante Dokumente für die Anfrage finden
        List<RAGDocument> relevantDocuments = ragService.findRelevantDocuments(
                message.getJobId(),
                message.getUserMessage(),
                5  // Top 5 relevante Dokumente
        );

        // Kontext für das LLM vorbereiten
        String context = prepareContextFromDocuments(relevantDocuments);

        // LLM-Antwort generieren
        String aiResponse = ragService.generateResponse(message.getUserMessage(), context);

        // Nachricht aktualisieren
        message.setAiResponse(aiResponse);
        message.setProcessed(true);

        // Zum Chat-Verlauf hinzufügen
        chatHistory.computeIfAbsent(message.getJobId(), k -> new ArrayList<>()).add(message);

        return message;
    }

    /**
     * Bereitet den Kontext für das LLM aus den relevanten Dokumenten vor
     */
    private String prepareContextFromDocuments(List<RAGDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "Keine relevanten Informationen gefunden.";
        }

        StringBuilder context = new StringBuilder();
        context.append("Relevante Informationen aus den gecrawlten Daten:\n\n");

        for (int i = 0; i < documents.size(); i++) {
            RAGDocument doc = documents.get(i);
            context.append("Dokument ").append(i + 1).append(": ");
            context.append(doc.getTitle()).append("\n");
            context.append("URL: ").append(doc.getUrl()).append("\n");
            context.append("Inhalt: ").append(doc.getContent().substring(0, Math.min(doc.getContent().length(), 500))).append("...\n\n");
        }

        return context.toString();
    }

    /**
     * Gibt den Chat-Verlauf für einen bestimmten Job zurück
     */
    public List<ChatMessage> getChatHistoryForJob(String jobId) {
        return chatHistory.getOrDefault(jobId, new ArrayList<>())
                .stream()
                .sorted(Comparator.comparing(ChatMessage::getTimestamp))
                .collect(Collectors.toList());
    }
}