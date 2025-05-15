package com.hhn.studyChat.config;

import com.hhn.studyChat.service.ChatService;
import com.hhn.studyChat.service.CrawlerService;
import com.hhn.studyChat.service.RAGService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    /**
     * Konfiguriert die Abhängigkeiten zwischen den Services
     * und vermeidet zirkuläre Abhängigkeiten
     */
    @Bean
    public RAGService ragService(CrawlerService crawlerService) {
        return new RAGService(crawlerService);
    }

    @Bean
    public ChatService chatService(RAGService ragService) {
        return new ChatService(ragService);
    }

    /**
     * Initialisierung nach Service-Erstellung
     */
    @Bean
    public boolean initializeServices(CrawlerService crawlerService, RAGService ragService) {
        // Zirkuläre Abhängigkeit manuell auflösen
        crawlerService.setRagService(ragService);
        return true;
    }
}