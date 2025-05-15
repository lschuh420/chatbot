package com.hhn.studyChat.service;

import com.hhn.studyChat.model.CrawlJob;
import com.hhn.studyChat.model.RAGDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.data.document.Metadata; // Importiere die Metadata-Klasse direkt

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;

@Service
public class RAGService {

    private final CrawlerService crawlerService;

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantPort;

    @Value("${openai.api.key:your-api-key}")
    private String openaiApiKey;

    // In-Memory-Cache für RAG-Dokumente nach jobId
    private final Map<String, List<RAGDocument>> documentCache = new ConcurrentHashMap<>();

    // Modelle und Stores für Langchain4j
    private EmbeddingModel embeddingModel;
    private ChatLanguageModel chatModel;
    private final Map<String, EmbeddingStore<TextSegment>> embeddingStores = new ConcurrentHashMap<>();

    // Konstanten
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    @Autowired
    public RAGService(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @PostConstruct
    public void init() {
        // Embedding-Modell initialisieren (lokales Modell)
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // Chat-Modell initialisieren (hier OpenAI, könnte auch durch ein lokales Modell ersetzt werden)
        chatModel = OpenAiChatModel.builder()
                .apiKey(openaiApiKey)
                .modelName("gpt-3.5-turbo")
                .temperature(0.7)
                .build();

        // Initialisieren des RAG-Systems für alle abgeschlossenen Jobs
        List<CrawlJob> completedJobs = crawlerService.getCompletedJobs();
        for (CrawlJob job : completedJobs) {
            try {
                initializeEmbeddingStoreForJob(job.getId());
            } catch (Exception e) {
                // Log-Fehler
                System.err.println("Fehler beim Initialisieren des RAG-Systems für Job " + job.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Initialisiert das Embedding-Store für einen bestimmten Job
     */
    public void initializeEmbeddingStoreForJob(String jobId) throws IOException {
        CrawlJob job = crawlerService.getJob(jobId);
        if (job == null || !"COMPLETED".equals(job.getStatus())) {
            throw new IllegalArgumentException("Job nicht gefunden oder nicht abgeschlossen: " + jobId);
        }

        // Prüfen, ob bereits initialisiert
        if (embeddingStores.containsKey(jobId)) {
            return;
        }

        // Qdrant Collection für diesen Job erstellen
        EmbeddingStore<TextSegment> embeddingStore = QdrantEmbeddingStore.builder()
                .host(qdrantHost)
                .port(qdrantPort)
                .collectionName("job_" + jobId.replace("-", "_"))
                .build();

        embeddingStores.put(jobId, embeddingStore);

        // Gecrawlte JSON-Dateien laden und indexieren
        List<RAGDocument> documents = loadDocumentsFromCrawlJob(job);
        documentCache.put(jobId, documents);

        // Dokumente chunken und embedden
        for (RAGDocument doc : documents) {
            // Erstelle Metadata-Objekt und befülle es
            Metadata metadata = new Metadata();
            metadata.add("url", doc.getUrl());
            metadata.add("title", doc.getTitle());
            metadata.add("category", doc.getCategory());

            // Erstelle Document mit dem Text und den Metadaten
            Document langchainDoc = Document.from(doc.getContent(), metadata);

            // Dokument in Chunks aufteilen
            DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
            List<TextSegment> segments = splitter.split(langchainDoc).stream()
                    .map(doc1 -> (TextSegment) doc1)
                    .collect(Collectors.toList());

            // Embeddings erzeugen und speichern
            for (TextSegment segment : segments) {
                Embedding embedding = embeddingModel.embed(segment).content();
                embeddingStore.add(embedding, segment);
            }
        }

        System.out.println("RAG-System für Job " + jobId + " initialisiert mit " + documents.size() + " Dokumenten");
    }

    /**
     * Lädt die gecrawlten Dokumente für einen Job
     */
    private List<RAGDocument> loadDocumentsFromCrawlJob(CrawlJob job) throws IOException {
        List<RAGDocument> documents = new ArrayList<>();
        String outputDir = job.getOutputDirectory();
        Path indexFilePath = Paths.get(outputDir, "crawl_index.json");

        // Index-Datei lesen
        if (!Files.exists(indexFilePath)) {
            System.err.println("Index-Datei nicht gefunden: " + indexFilePath);
            return documents;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(indexFilePath.toFile());
        JsonNode urlsArray = rootNode.get("crawled_urls");

        if (urlsArray == null || !urlsArray.isArray()) {
            System.err.println("Keine URLs in der Index-Datei gefunden");
            return documents;
        }

        // Alle gecrawlten URLs durchgehen
        for (JsonNode urlNode : urlsArray) {
            String filePath = urlNode.get("file_path").asText();
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                System.err.println("Datei nicht gefunden: " + filePath);
                continue;
            }

            try {
                // JSON-Datei lesen
                JsonNode docNode = mapper.readTree(path.toFile());

                String url = docNode.get("url").asText();
                String domain = docNode.get("domain").asText();
                String category = docNode.has("category") ? docNode.get("category").asText() : "allgemein";

                // Titel und Inhalt extrahieren
                String title = "";
                String content = "";

                if (docNode.has("content")) {
                    JsonNode contentNode = docNode.get("content");
                    if (contentNode.has("title")) {
                        title = contentNode.get("title").asText();
                    }
                    if (contentNode.has("full_text")) {
                        content = contentNode.get("full_text").asText();
                    }
                }

                // RAG-Dokument erstellen
                RAGDocument ragDoc = RAGDocument.create(
                        job.getId(),
                        url,
                        title,
                        content,
                        category,
                        filePath
                );

                documents.add(ragDoc);

            } catch (Exception e) {
                System.err.println("Fehler beim Lesen der Datei " + filePath + ": " + e.getMessage());
            }
        }

        return documents;
    }

    /**
     * Findet relevante Dokumente für eine Anfrage
     */
    public List<RAGDocument> findRelevantDocuments(String jobId, String query, int maxResults) {
        // Prüfen, ob das Embedding-Store initialisiert ist
        if (!embeddingStores.containsKey(jobId)) {
            try {
                initializeEmbeddingStoreForJob(jobId);
            } catch (Exception e) {
                System.err.println("Fehler beim Initialisieren des RAG-Systems: " + e.getMessage());
                return new ArrayList<>();
            }
        }

        EmbeddingStore<TextSegment> embeddingStore = embeddingStores.get(jobId);

        // Query embedden
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // Ähnliche Dokumente finden
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, maxResults);

        // RAG-Dokumente aus dem Cache abrufen
        List<RAGDocument> documents = documentCache.getOrDefault(jobId, new ArrayList<>());

        // Relevante Dokumente anhand der URLs finden
        List<RAGDocument> relevantDocs = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            String url = segment.metadata().get("url");

            // Passendes Dokument im Cache finden
            for (RAGDocument doc : documents) {
                if (doc.getUrl().equals(url)) {
                    // Wenn nicht bereits in der Liste, hinzufügen
                    if (!relevantDocs.contains(doc)) {
                        relevantDocs.add(doc);
                    }
                    break;
                }
            }
        }

        return relevantDocs;
    }

    /**
     * Generiert eine Antwort vom LLM basierend auf der Anfrage und dem Kontext
     */
    public String generateResponse(String query, String context) {
        try {
            // Prompt erstellen
            String prompt = String.format(
                    "Du bist ein Assistent, der Fragen über gecrawlte Webinhalte beantwortet.\n" +
                            "Beantworte die folgende Frage basierend auf dem bereitgestellten Kontext.\n" +
                            "Wenn du die Antwort nicht im Kontext findest, sage, dass du die Information nicht hast.\n\n" +
                            "KONTEXT:\n%s\n\n" +
                            "FRAGE:\n%s\n\n" +
                            "ANTWORT:\n",
                    context, query
            );

            // Antwort vom LLM generieren
            return chatModel.generate(prompt);

        } catch (Exception e) {
            System.err.println("Fehler bei der Generierung der Antwort: " + e.getMessage());
            return "Entschuldigung, es gab einen Fehler bei der Verarbeitung deiner Anfrage. Bitte versuche es später erneut.";
        }
    }

    /**
     * Aktualisiert das RAG-System nach einem neuen Job
     */
    public void updateForNewCompletedJob(String jobId) {
        try {
            initializeEmbeddingStoreForJob(jobId);
        } catch (Exception e) {
            System.err.println("Fehler beim Aktualisieren des RAG-Systems für neuen Job: " + e.getMessage());
        }
    }
}