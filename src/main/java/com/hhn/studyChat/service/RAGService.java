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
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import dev.langchain4j.data.document.Metadata;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import okhttp3.OkHttpClient;
import okhttp3.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;

@Service
public class RAGService {
    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);

    private final CrawlerService crawlerService;

    // === LOKALE OPEN WEBUI KONFIGURATION ===
    @Value("${openwebui.host:localhost}")
    private String openWebUIHost;

    @Value("${openwebui.port:3000}")
    private int openWebUIPort;

    @Value("${openwebui.api.key:}")
    private String openWebUIApiKey;

    @Value("${openwebui.model:mistral:latest}")
    private String openWebUIModel;

    // === QDRANT KONFIGURATION ===
    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6334}")
    private int qdrantPort;

    @Value("${use.inmemory.store:false}")
    private boolean useInMemoryStore;

    // In-Memory-Cache für RAG-Dokumente nach jobId
    private final Map<String, List<RAGDocument>> documentCache = new ConcurrentHashMap<>();

    // Modelle und Stores für Langchain4j
    private EmbeddingModel embeddingModel;
    private ChatLanguageModel chatModel;
    private final Map<String, EmbeddingStore<TextSegment>> embeddingStores = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Konstanten
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int EMBEDDING_SIZE = 384; // Für AllMiniLmL6V2EmbeddingModel
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private QdrantClient qdrantClient;

    @Autowired
    public RAGService(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @PostConstruct
    public void init() {
        logger.info("=== INITIALISIERE RAG-SERVICE MIT LOKALER OPEN WEBUI ===");

        // === LOKALES LLM ÜBER OPEN WEBUI KONFIGURIEREN ===
        String openWebUIBaseUrl = String.format("http://%s:%d/api", openWebUIHost, openWebUIPort);

        logger.info("Open WebUI Base URL: {}", openWebUIBaseUrl);
        logger.info("Verwendetes Modell: {}", openWebUIModel);

        try {
            // OpenAI-kompatible API über Open WebUI
            var chatModelBuilder = OpenAiChatModel.builder()
                    .baseUrl(openWebUIBaseUrl)
                    .modelName(openWebUIModel)
                    .temperature(0.7);

            // API Key nur setzen wenn vorhanden
            if (openWebUIApiKey != null && !openWebUIApiKey.trim().isEmpty()) {
                chatModelBuilder.apiKey(openWebUIApiKey);
                logger.info("API Key für Open WebUI konfiguriert");
            } else {
                // Fallback API Key für Open WebUI (oft nicht erforderlich bei lokaler Installation)
                chatModelBuilder.apiKey("sk-not-needed-for-local");
                logger.info("Verwende Fallback API Key für lokale Open WebUI");
            }

            chatModel = chatModelBuilder.build();
            logger.info("✓ Open WebUI Chat Model erfolgreich konfiguriert");

            // Test der Verbindung
            testOpenWebUIConnection();

        } catch (Exception e) {
            logger.error("❌ Fehler bei der Open WebUI Konfiguration: {}", e.getMessage());
            throw new RuntimeException("Kann Open WebUI nicht erreichen", e);
        }

        // === EMBEDDING MODEL INITIALISIEREN ===
        logger.info("Initialisiere lokales Embedding-Modell...");
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        logger.info("✓ Embedding-Modell erfolgreich geladen");

        // === QDRANT CLIENT INITIALISIEREN ===
        if (!useInMemoryStore) {
            try {
                logger.info("Initialisiere Qdrant Client...");
                qdrantClient = new QdrantClient(
                        QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build()
                );
                logger.info("✓ Qdrant Client erfolgreich initialisiert");
            } catch (Exception e) {
                logger.error("❌ Qdrant nicht verfügbar, verwende In-Memory Store: {}", e.getMessage());
                useInMemoryStore = true;
            }
        }

        // === RAG-SYSTEM FÜR EXISTIERENDE JOBS INITIALISIEREN ===
        logger.info("Initialisiere RAG-System für alle abgeschlossenen Jobs...");
        List<CrawlJob> completedJobs = crawlerService.getCompletedJobs();
        for (CrawlJob job : completedJobs) {
            try {
                initializeEmbeddingStoreForJob(job.getId());
            } catch (Exception e) {
                logger.error("Fehler beim Initialisieren des RAG-Systems für Job {}: {}", job.getId(), e.getMessage());
            }
        }

        logger.info("=== RAG-SERVICE ERFOLGREICH INITIALISIERT ===");
    }

    /**
     * Testet die Verbindung zur Open WebUI
     */
    private void testOpenWebUIConnection() {
        try {
            logger.info("Teste Verbindung zur Open WebUI...");
            String testResponse = chatModel.generate("Antworte nur mit 'OK' wenn du erreichbar bist.");
            logger.info("✓ Open WebUI Test erfolgreich. Antwort: {}", testResponse);
        } catch (Exception e) {
            logger.error("❌ Open WebUI Test fehlgeschlagen: {}", e.getMessage());
            throw new RuntimeException("Open WebUI nicht erreichbar", e);
        }
    }

    /**
     * Generiert eine Antwort vom lokalen LLM basierend auf der Anfrage und dem Kontext
     */
    public String generateResponse(String query, String context) {
        try {
            // Optimierter Prompt für deutsche Hochschul-Inhalte
            String prompt = String.format(
                    "Du bist ein hilfsreicher Assistent für Studierende der Hochschule Heilbronn. " +
                            "Beantworte die folgende Frage basierend auf den bereitgestellten Informationen aus den Webseiten der Hochschule.\n\n" +
                            "WICHTIGE REGELN:\n" +
                            "- Antworte auf Deutsch\n" +
                            "- Sei präzise und hilfreich\n" +
                            "- Beziehe dich nur auf die gegebenen Informationen\n" +
                            "- Wenn die Antwort nicht in den Informationen steht, sage das ehrlich\n" +
                            "- Gib konkrete Hinweise und Links wenn möglich\n\n" +
                            "KONTEXT (Informationen von der HHN-Website):\n%s\n\n" +
                            "FRAGE:\n%s\n\n" +
                            "ANTWORT:",
                    context, query
            );

            logger.info("Generiere Antwort für Anfrage: '{}'", query);
            logger.debug("Verwendeter Prompt: {}", prompt);

            String response = chatModel.generate(prompt);
            logger.info("✓ Antwort vom lokalen LLM erhalten");
            return response;

        } catch (Exception e) {
            logger.error("❌ Fehler bei der Generierung der Antwort: {}", e.getMessage());
            return "Entschuldigung, es gab einen Fehler beim Verarbeiten deiner Anfrage. " +
                    "Bitte überprüfe, ob die Open WebUI erreichbar ist und versuche es später erneut.";
        }
    }

    // === REST DER KLASSE BLEIBT UNVERÄNDERT ===

    @PreDestroy
    public void cleanup() {
        if (qdrantClient != null) {
            try {
                qdrantClient.close();
                logger.info("Qdrant gRPC client closed successfully");
            } catch (Exception e) {
                logger.error("Error closing Qdrant client: {}", e.getMessage());
            }
        }
        if (httpClient != null) {
            httpClient.connectionPool().evictAll();
            httpClient.dispatcher().executorService().shutdown();
        }
    }

    // [Alle anderen Methoden bleiben unverändert...]
    // ... hier würden alle anderen Methoden aus der ursprünglichen RAGService.java folgen

    /**
     * Prüft, ob eine Qdrant-Collection existiert (via gRPC)
     */
    private boolean collectionExists(String collectionName) {
        if (useInMemoryStore) return false;

        try {
            Boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
            logger.debug("Collection '{}' exists: {}", collectionName, exists);
            return exists != null && exists;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Thread interrupted while checking collection existence: {}", e.getMessage());
            return false;
        } catch (ExecutionException e) {
            logger.error("gRPC-Fehler (collectionExists): {}", e.getMessage());
            return false;
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
            logger.info("Embedding-Store für Job {} bereits initialisiert", jobId);
            return;
        }

        String collectionName = "job_" + jobId.replace("-", "_");
        EmbeddingStore<TextSegment> embeddingStore;

        // Embedding Store konfigurieren (In-Memory oder Qdrant)
        if (useInMemoryStore) {
            logger.info("Verwende In-Memory-Store für Job {}", jobId);
            embeddingStore = new InMemoryEmbeddingStore<>();
        } else {
            try {
                if (!collectionExists(collectionName)) {
                    logger.info("Collection {} wird erstellt...", collectionName);
                    createCollection(collectionName);
                }

                embeddingStore = QdrantEmbeddingStore.builder()
                        .host(qdrantHost)
                        .port(qdrantPort)
                        .collectionName(collectionName)
                        .build();

                logger.info("✓ Qdrant Embedding Store für Job {} konfiguriert", jobId);
            } catch (Exception e) {
                logger.error("Qdrant Fehler, verwende In-Memory Store: {}", e.getMessage());
                embeddingStore = new InMemoryEmbeddingStore<>();
            }
        }

        embeddingStores.put(jobId, embeddingStore);

        // Dokumente laden und indexieren
        logger.info("Lade Dokumente aus Crawl-Job {}...", jobId);
        List<RAGDocument> documents = loadDocumentsFromCrawlJob(job);
        documentCache.put(jobId, documents);
        logger.info("{} Dokumente geladen", documents.size());

        // Dokumente chunken und embedden
        logger.info("Erstelle Embeddings für {} Dokumente...", documents.size());
        int processedCount = 0;
        for (RAGDocument doc : documents) {
            try {
                if (doc.getContent() == null || doc.getContent().trim().isEmpty()) {
                    logger.warn("Überspringe Dokument {} mit leerem Inhalt", doc.getId());
                    continue;
                }

                // Metadata und Document erstellen
                Metadata metadata = new Metadata();
                metadata.add("url", doc.getUrl());
                metadata.add("title", doc.getTitle());
                metadata.add("category", doc.getCategory());

                Document langchainDoc = Document.from(doc.getContent(), metadata);

                // Dokument chunken
                DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
                List<TextSegment> segments = splitter.split(langchainDoc).stream()
                        .map(doc1 -> (TextSegment) doc1)
                        .collect(Collectors.toList());

                // Embeddings erstellen und speichern
                for (TextSegment segment : segments) {
                    Embedding embedding = embeddingModel.embed(segment).content();
                    embeddingStore.add(embedding, segment);
                }

                processedCount++;
                if (processedCount % 10 == 0) {
                    logger.info("Verarbeitet: {} von {} Dokumenten", processedCount, documents.size());
                }
            } catch (Exception e) {
                logger.error("Fehler beim Verarbeiten von Dokument {}: {}", doc.getId(), e.getMessage());
            }
        }

        logger.info("✓ RAG-System für Job {} initialisiert mit {} Dokumenten", jobId, documents.size());
    }

    /**
     * Findet relevante Dokumente für eine Anfrage
     */
    public List<RAGDocument> findRelevantDocuments(String jobId, String query, int maxResults) {
        // Prüfen, ob das Embedding-Store initialisiert ist
        if (!embeddingStores.containsKey(jobId)) {
            try {
                logger.info("Initialisiere Embedding-Store für Job {}...", jobId);
                initializeEmbeddingStoreForJob(jobId);
            } catch (Exception e) {
                logger.error("Fehler beim Initialisieren des RAG-Systems: {}", e.getMessage());
                return new ArrayList<>();
            }
        }

        EmbeddingStore<TextSegment> embeddingStore = embeddingStores.get(jobId);

        try {
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
                        if (!relevantDocs.contains(doc)) {
                            relevantDocs.add(doc);
                        }
                        break;
                    }
                }
            }

            return relevantDocs;
        } catch (Exception e) {
            logger.error("Fehler beim Suchen relevanter Dokumente: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Aktualisiert das RAG-System nach einem neuen Job
     */
    public void updateForNewCompletedJob(String jobId) {
        try {
            logger.info("Aktualisiere RAG-System für neuen Job: {}", jobId);
            initializeEmbeddingStoreForJob(jobId);
        } catch (Exception e) {
            logger.error("Fehler beim Aktualisieren des RAG-Systems für neuen Job: {}", e.getMessage());
        }
    }

    // Weitere Hilfsmethoden...
    public boolean createCollection(String collectionName) {
        if (useInMemoryStore) return true;

        try {
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setSize(EMBEDDING_SIZE)
                    .setDistance(Collections.Distance.Cosine)
                    .build();

            Collections.VectorsConfig vectorsConfig = Collections.VectorsConfig.newBuilder()
                    .setParams(vectorParams)
                    .build();

            Collections.CreateCollection createRequest = Collections.CreateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setVectorsConfig(vectorsConfig)
                    .build();

            Collections.CollectionOperationResponse response = qdrantClient.createCollectionAsync(createRequest).get();
            boolean success = response.getResult();
            logger.info("Collection '{}' created successfully: {}", collectionName, success);
            return success;
        } catch (Exception e) {
            logger.error("gRPC-Fehler (createCollection): {}", e.getMessage());
            return false;
        }
    }

    private List<RAGDocument> loadDocumentsFromCrawlJob(CrawlJob job) throws IOException {
        List<RAGDocument> documents = new ArrayList<>();
        String outputDir = job.getOutputDirectory();
        Path indexFilePath = Paths.get(outputDir, "crawl_index.json");

        // Index-Datei lesen
        if (!Files.exists(indexFilePath)) {
            logger.error("Index-Datei nicht gefunden: {}", indexFilePath);
            return documents;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(indexFilePath.toFile());
        JsonNode urlsArray = rootNode.get("crawled_urls");

        if (urlsArray == null || !urlsArray.isArray()) {
            logger.error("Keine URLs in der Index-Datei gefunden");
            return documents;
        }

        // Alle gecrawlten URLs durchgehen
        for (JsonNode urlNode : urlsArray) {
            String filePath = urlNode.get("file_path").asText();
            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                logger.warn("Datei nicht gefunden: {}", filePath);
                continue;
            }

            try {
                // JSON-Datei lesen
                JsonNode docNode = mapper.readTree(path.toFile());

                String url = docNode.get("url").asText();
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
                logger.error("Fehler beim Lesen der Datei {}: {}", filePath, e.getMessage());
            }
        }

        return documents;
    }
}