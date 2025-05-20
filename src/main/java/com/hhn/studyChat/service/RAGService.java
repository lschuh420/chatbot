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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;

@Service
public class RAGService {
    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);

    private final CrawlerService crawlerService;

    @Value("${qdrant.host:localhost}")
    private String qdrantHost;

    @Value("${qdrant.port:6333}")
    private int qdrantPort;

    @Value("${openai.api.key:your-api-key}")
    private String openaiApiKey;

    @Value("${use.inmemory.store:true}")
    private boolean useInMemoryStore;

    // In-Memory-Cache für RAG-Dokumente nach jobId
    private final Map<String, List<RAGDocument>> documentCache = new ConcurrentHashMap<>();

    // Modelle und Stores für Langchain4j
    private EmbeddingModel embeddingModel;
    private ChatLanguageModel chatModel;
    private OpenAiChatModel openAiChatModel;
    private final Map<String, EmbeddingStore<TextSegment>> embeddingStores = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Konstanten
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int EMBEDDING_SIZE = 384; // Für AllMiniLmL6V2EmbeddingModel
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Autowired
    public RAGService(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    @PostConstruct
    public void init() {
        // Embedding-Modell initialisieren (lokales Modell)
        logger.info("Initialisiere Embedding-Modell...");
        embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // Chat-Modell initialisieren
        logger.info("Initialisiere Chat-Modell...");
        openAiChatModel = OpenAiChatModel.builder()
                .baseUrl("http://langchain4j.dev/demo/openai/v1")
                .apiKey("demo")
                .modelName("gpt-4o-mini")
                .build();

        // Initialisieren des RAG-Systems für alle abgeschlossenen Jobs
        logger.info("Initialisiere RAG-System für alle abgeschlossenen Jobs...");
        List<CrawlJob> completedJobs = crawlerService.getCompletedJobs();
        for (CrawlJob job : completedJobs) {
            try {
                initializeEmbeddingStoreForJob(job.getId());
            } catch (Exception e) {
                logger.error("Fehler beim Initialisieren des RAG-Systems für Job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    /**
     * Prüft, ob eine Qdrant-Collection existiert
     */
    private boolean collectionExists(String collectionName) {
        try {
            String url = String.format("http://%s:%d/collections/%s", qdrantHost, qdrantPort, collectionName);
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful(); // 200 OK bedeutet, Collection existiert
            }
        } catch (Exception e) {
            logger.error("Fehler beim Prüfen der Collection {}: {}", collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * Erstellt eine neue Qdrant-Collection
     */
    private boolean createCollection(String collectionName) {
        try {
            String url = String.format("http://%s:%d/collections/%s", qdrantHost, qdrantPort, collectionName);

            // JSON-Payload für die Collection-Erstellung
            String json = String.format(
                    "{\n" +
                            "  \"vectors\": {\n" +
                            "    \"size\": %d,\n" +
                            "    \"distance\": \"Cosine\"\n" +
                            "  }\n" +
                            "}", EMBEDDING_SIZE);

            RequestBody body = RequestBody.create(json, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .put(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            logger.error("Fehler beim Erstellen der Collection {}: {}", collectionName, e.getMessage());
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

        // Wenn In-Memory-Store konfiguriert ist, diesen verwenden
        if (useInMemoryStore) {
            logger.info("Verwende In-Memory-Store für Job {}", jobId);
            embeddingStore = new InMemoryEmbeddingStore<>();
        } else {
            // Versuche Qdrant zu verwenden, mit Fallback zu In-Memory-Store
            try {
                // Prüfe, ob Qdrant erreichbar ist
                logger.info("Prüfe, ob Qdrant-Server erreichbar ist...");
                if (isQdrantReachable()) {
                    // Prüfe, ob Collection existiert
                    if (!collectionExists(collectionName)) {
                        logger.info("Collection {} existiert nicht. Wird erstellt...", collectionName);
                        boolean created = createCollection(collectionName);
                        if (!created) {
                            throw new IOException("Konnte Collection nicht erstellen");
                        }
                        logger.info("Collection {} erfolgreich erstellt", collectionName);
                    } else {
                        logger.info("Collection {} existiert bereits", collectionName);
                    }

                    // QdrantEmbeddingStore initialisieren
                    embeddingStore = QdrantEmbeddingStore.builder()
                            .host(qdrantHost)
                            .port(qdrantPort)
                            .collectionName(collectionName)
                            .build();

                    logger.info("Verbindung zu Qdrant (Port {}) erfolgreich", qdrantPort);
                } else {
                    throw new IOException("Qdrant-Server nicht erreichbar");
                }
            } catch (Exception e) {
                logger.error("Fehler bei der Verbindung zu Qdrant: {}", e.getMessage());
                logger.info("Verwende In-Memory-Store als Fallback");
                embeddingStore = new InMemoryEmbeddingStore<>();
            }
        }

        embeddingStores.put(jobId, embeddingStore);

        // Gecrawlte JSON-Dateien laden und indexieren
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

                processedCount++;
                if (processedCount % 10 == 0) {
                    logger.info("Verarbeitet: {} von {} Dokumenten", processedCount, documents.size());
                }
            } catch (Exception e) {
                logger.error("Fehler beim Verarbeiten von Dokument {}: {}", doc.getId(), e.getMessage());
            }
        }

        logger.info("RAG-System für Job {} initialisiert mit {} Dokumenten", jobId, documents.size());
    }

    /**
     * Prüft, ob der Qdrant-Server erreichbar ist
     */
    private boolean isQdrantReachable() {
        try {
            String url = String.format("http://%s:%d/collections", qdrantHost, qdrantPort);
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            logger.error("Qdrant-Server nicht erreichbar: {}", e.getMessage());
            return false;
        }
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
                logger.error("Fehler beim Lesen der Datei {}: {}", filePath, e.getMessage());
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
                        // Wenn nicht bereits in der Liste, hinzufügen
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
            logger.info("Generiere Antwort für Anfrage: '{}'", query);
            String response = openAiChatModel.generate(prompt);
            logger.info("Antwort generiert");
            return response;

        } catch (Exception e) {
            logger.error("Fehler bei der Generierung der Antwort: {}", e.getMessage());
            return "Entschuldigung, es gab einen Fehler bei der Verarbeitung deiner Anfrage. Bitte versuche es später erneut.";
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
}