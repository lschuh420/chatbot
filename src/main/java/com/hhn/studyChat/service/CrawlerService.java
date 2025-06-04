package com.hhn.studyChat.service;

import com.hhn.studyChat.model.CrawlJob;
import com.hhn.studyChat.util.TopologyRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CrawlerService {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerService.class);
    private final Map<String, CrawlJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    @Value("${crawler.output.dir}")
    private String defaultOutputDir;

    // Setter für RAGService (vermeidet zirkuläre Abhängigkeit)
    // Optional: Dependency Injection für RAGService
    @Setter
    private RAGService ragService;

    @PostConstruct
    public void init() {
        // Beim Start der Anwendung das Verzeichnis nach bestehenden Crawl-Ergebnissen scannen
        loadExistingCrawlJobs();
    }

    /**
     * Scannt das Verzeichnis nach bestehenden Crawl-Ergebnissen und
     * erstellt virtuelle Jobs für sie
     */
    private void loadExistingCrawlJobs() {
        Path outputPath = Paths.get(defaultOutputDir);
        if (!Files.exists(outputPath)) {
            logger.info("Output directory does not exist yet: {}", outputPath);
            return;
        }

        try {
            Path indexFile = outputPath.resolve("crawl_index.json");
            if (Files.exists(indexFile)) {
                logger.info("Found crawl index file: {}", indexFile);

                // Index-Datei lesen und Jobs erstellen
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(indexFile.toFile());
                JsonNode urlsArray = rootNode.get("crawled_urls");

                if (urlsArray != null && urlsArray.isArray()) {
                    // Domains gruppieren
                    Map<String, List<JsonNode>> domainGroups = new ConcurrentHashMap<>();

                    for (JsonNode urlNode : urlsArray) {
                        String domain = urlNode.get("domain").asText();
                        domainGroups.computeIfAbsent(domain, k -> new ArrayList<>()).add(urlNode);
                    }

                    // Für jede Domain einen Job erstellen
                    for (Map.Entry<String, List<JsonNode>> entry : domainGroups.entrySet()) {
                        String domain = entry.getKey();
                        List<JsonNode> domainUrls = entry.getValue();

                        if (!domainUrls.isEmpty()) {
                            // Ermittle die Seed-URL und Crawl-Zeit
                            List<String> seedUrls = new ArrayList<>();
                            LocalDateTime jobTime = null;

                            for (JsonNode urlNode : domainUrls) {
                                String url = urlNode.get("url").asText();
                                seedUrls.add(url);

                                // Versuche, einen Zeitstempel zu extrahieren
                                if (jobTime == null && urlNode.has("crawl_timestamp")) {
                                    try {
                                        String timestamp = urlNode.get("crawl_timestamp").asText();
                                        jobTime = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME);
                                    } catch (DateTimeParseException e) {
                                        // Ignorieren und weitermachen
                                    }
                                }
                            }

                            // Erstelle einen virtuellen Job
                            String jobId = "existing-" + UUID.randomUUID().toString();
                            CrawlJob job = CrawlJob.builder()
                                    .id(jobId)
                                    .seedUrls(seedUrls)
                                    .maxDepth(1) // Annahme
                                    .status("COMPLETED")
                                    .createdAt(jobTime != null ? jobTime : LocalDateTime.now())
                                    .startedAt(jobTime != null ? jobTime : LocalDateTime.now())
                                    .completedAt(jobTime != null ? jobTime : LocalDateTime.now())
                                    .outputDirectory(defaultOutputDir)
                                    .crawledUrlsCount(domainUrls.size())
                                    .build();

                            jobs.put(jobId, job);
                            logger.info("Created virtual job {} for domain {} with {} URLs", jobId, domain, domainUrls.size());
                        }
                    }
                } else {
                    logger.warn("No crawled URLs found in index file");
                }
            } else {
                // Wenn keine Index-Datei existiert, versuche die Verzeichnisstruktur zu durchsuchen
                scanDirectoryStructure(outputPath);
            }
        } catch (IOException e) {
            logger.error("Error scanning existing crawl jobs", e);
        }
    }

    /**
     * Scannt die Verzeichnisstruktur nach Domains und JSON-Dateien
     */
    private void scanDirectoryStructure(Path outputPath) throws IOException {
        Path domainsDir = outputPath.resolve("domains");

        if (Files.exists(domainsDir) && Files.isDirectory(domainsDir)) {
            try (Stream<Path> domainPaths = Files.list(domainsDir)) {
                domainPaths.filter(Files::isDirectory).forEach(domainPath -> {
                    try {
                        String domain = domainPath.getFileName().toString();
                        int fileCount = 0;
                        List<String> seedUrls = new ArrayList<>();

                        // JSON-Dateien zählen und erste URL extrahieren
                        try (Stream<Path> files = Files.list(domainPath)) {
                            List<Path> jsonFiles = files
                                    .filter(path -> path.toString().endsWith(".json"))
                                    .collect(Collectors.toList());

                            fileCount = jsonFiles.size();

                            if (!jsonFiles.isEmpty()) {
                                // Lese die erste JSON-Datei, um eine URL zu extrahieren
                                try {
                                    Path firstFile = jsonFiles.get(0);
                                    ObjectMapper mapper = new ObjectMapper();
                                    JsonNode rootNode = mapper.readTree(firstFile.toFile());

                                    if (rootNode.has("url")) {
                                        seedUrls.add(rootNode.get("url").asText());
                                    } else {
                                        // Fallback: Domain als URL verwenden
                                        seedUrls.add("https://" + domain);
                                    }
                                } catch (IOException e) {
                                    // Bei Fehler: Domain als URL verwenden
                                    seedUrls.add("https://" + domain);
                                    logger.warn("Could not read JSON file for domain {}", domain, e);
                                }
                            }
                        }

                        if (fileCount > 0) {
                            String jobId = "existing-" + UUID.randomUUID().toString();
                            CrawlJob job = CrawlJob.builder()
                                    .id(jobId)
                                    .seedUrls(seedUrls)
                                    .maxDepth(1) // Annahme
                                    .status("COMPLETED")
                                    .createdAt(LocalDateTime.now())
                                    .startedAt(LocalDateTime.now())
                                    .completedAt(LocalDateTime.now())
                                    .outputDirectory(defaultOutputDir)
                                    .crawledUrlsCount(fileCount)
                                    .build();

                            jobs.put(jobId, job);
                            logger.info("Created virtual job {} for domain {} with {} files", jobId, domain, fileCount);
                        }
                    } catch (IOException e) {
                        logger.error("Error scanning domain directory {}", domainPath, e);
                    }
                });
            }
        }
    }

    // Erstelle einen neuen Crawling-Job
    public CrawlJob createJob(List<String> seedUrls, int maxDepth, String outputDir) {
        CrawlJob job = CrawlJob.create(seedUrls, maxDepth, outputDir);
        jobs.put(job.getId(), job);
        return job;
    }

    // Starte einen existierenden Job
    public void startJob(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job == null || !"QUEUED".equals(job.getStatus())) {
            throw new IllegalStateException("Job nicht gefunden oder nicht in der Queue");
        }

        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());

        executorService.submit(() -> {
            try {
                // Topologie ausführen
                TopologyRunner.runTopology(
                        job.getSeedUrls().toArray(new String[0]),
                        job.getMaxDepth(),
                        job.getOutputDirectory(),
                        job.getId()
                );

                // Nach erfolgreichem Abschluss
                job.setStatus("COMPLETED");
                job.setCompletedAt(LocalDateTime.now());

                // Optional: RAG-System für diesen Job initialisieren
                if (ragService != null) {
                    try {
                        ragService.updateForNewCompletedJob(job.getId());
                    } catch (Exception e) {
                        logger.error("Fehler beim Initialisieren des RAG-Systems", e);
                    }
                }
            } catch (Exception e) {
                job.setStatus("FAILED");
                job.setCompletedAt(LocalDateTime.now());
                // Log-Exception
                logger.error("Fehler beim Ausführen des Crawl-Jobs", e);
            }
        });
    }

    // Hole Job nach ID
    public CrawlJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    // Liste alle Jobs
    public List<CrawlJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    // Liste alle abgeschlossenen Jobs
    public List<CrawlJob> getCompletedJobs() {
        return jobs.values().stream()
                .filter(job -> "COMPLETED".equals(job.getStatus()))
                .collect(Collectors.toList());
    }

    // Aktualisiere Job-Statistiken
    public void updateJobStats(String jobId, int crawledUrlsCount) {
        CrawlJob job = jobs.get(jobId);
        if (job != null) {
            job.setCrawledUrlsCount(crawledUrlsCount);
        }
    }
}