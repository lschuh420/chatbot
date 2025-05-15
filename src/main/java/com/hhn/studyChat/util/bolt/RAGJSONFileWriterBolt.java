package com.hhn.studyChat.util.bolt;

import com.digitalpebble.stormcrawler.Metadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced JSON File Writer Bolt für RAG-Anwendungsfälle:
 * 1. Speichert jede Seite als separate, strukturierte JSON-Datei
 * 2. Pflegt eine Index-Datei mit Zusammenfassungen aller gecrawlten URLs
 * 3. Verbesserte Textextraktion und -strukturierung
 */
public class RAGJSONFileWriterBolt extends BaseRichBolt {

    private OutputCollector collector;
    private final String outputDir;
    private final String indexFileName;
    private ObjectMapper mapper;
    private Path indexFilePath;
    private final ConcurrentHashMap<String, AtomicInteger> domainCounters = new ConcurrentHashMap<>();

    private static final Pattern SECTION_PATTERN = Pattern.compile("SECTION:\\s*(.+)\\n([\\s\\S]*?)(?=SECTION:|LIST:|$)");
    private static final Pattern LIST_PATTERN = Pattern.compile("LIST:\\n([\\s\\S]*?)(?=SECTION:|LIST:|$)");

    public RAGJSONFileWriterBolt(String outputDir, String indexFileName) {
        this.outputDir = outputDir;
        this.indexFileName = indexFileName != null ? indexFileName : "crawl_index.json";
    }

    public RAGJSONFileWriterBolt(String outputDir) {
        this(outputDir, "crawl_index.json");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        this.mapper = new ObjectMapper();

        System.out.println("Initializing RAGJSONFileWriterBolt with output directory: " + outputDir);

        try {
            // Ausgabeverzeichnis erstellen
            Files.createDirectories(Paths.get(outputDir));
            System.out.println("Created output directory: " + outputDir);

            // Index-Datei initialisieren oder laden
            this.indexFilePath = Paths.get(outputDir, indexFileName);

            if (!Files.exists(indexFilePath)) {
                // Neue Index-Datei mit leerem Array erstellen
                ObjectNode rootNode = mapper.createObjectNode();
                rootNode.put("last_updated", Instant.now().toString());
                rootNode.putArray("crawled_urls");

                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                Files.write(indexFilePath, json.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("Created index file: " + indexFilePath);
            }

        } catch (Exception e) {
            System.err.println("Failed to initialize RAGJSONFileWriterBolt: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize RAGJSONFileWriterBolt", e);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            String timestamp = Instant.now().toString();

            System.out.println("Processing URL: " + url);

            // Standardfelder abrufen
            List<Map<String, String>> events = null;
            List<Map<String, String>> news = null;
            List<String> courses = null;
            String text = null;

            try {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> eventsList = (List<Map<String, String>>) tuple.getValueByField("events");
                events = eventsList;
            } catch (Exception e) {
                System.out.println("No events found in tuple: " + e.getMessage());
                events = Collections.emptyList();
            }

            try {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> newsList = (List<Map<String, String>>) tuple.getValueByField("news");
                news = newsList;
            } catch (Exception e) {
                System.out.println("No news found in tuple: " + e.getMessage());
                news = Collections.emptyList();
            }

            try {
                @SuppressWarnings("unchecked")
                List<String> coursesList = (List<String>) tuple.getValueByField("courses");
                courses = coursesList;
            } catch (Exception e) {
                System.out.println("No courses found in tuple: " + e.getMessage());
                courses = Collections.emptyList();
            }

            try {
                text = tuple.getStringByField("text");
            } catch (Exception e) {
                System.out.println("No text found in tuple: " + e.getMessage());
                text = "";
            }

            // Metadaten abrufen, falls verfügbar
            Metadata metadata = null;
            try {
                metadata = (Metadata) tuple.getValueByField("metadata");
            } catch (Exception e) {
                System.out.println("No metadata found in tuple: " + e.getMessage());
                metadata = new Metadata();
            }

            // Detailliertes JSON-Dokument erstellen
            ObjectNode doc = createDetailedDocument(url, events, news, courses, text, metadata, timestamp);

            // Domain extrahieren, um Dateien zu organisieren
            String domain = extractDomain(url);
            Path domainDir = Paths.get(outputDir, "domains", domain);
            Files.createDirectories(domainDir);

            System.out.println("Created domain directory: " + domainDir);

            // Eindeutigen Dateinamen erstellen
            int count = domainCounters.computeIfAbsent(domain, k -> new AtomicInteger(0)).incrementAndGet();
            String filename = String.format("%s_%d.json", sanitizeForFilename(domain), count);
            Path filePath = domainDir.resolve(filename);

            // Detailliertes JSON in Datei schreiben
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Wrote detailed JSON to: " + filePath);

            // Index-Datei mit Zusammenfassung aktualisieren
            updateIndex(url, domain, filePath.toString(), timestamp, events, news, courses);

            System.out.println("Updated index file");

            collector.ack(tuple);
        } catch (Exception e) {
            System.err.println("Error processing tuple: " + e.getMessage());
            e.printStackTrace();
            collector.reportError(e);
            collector.fail(tuple);
        }
    }

    /**
     * Erstellt ein detailliertes JSON-Dokument mit allen verfügbaren Informationen
     */
    private ObjectNode createDetailedDocument(String url, List<Map<String, String>> events,
                                              List<Map<String, String>> news, List<String> courses,
                                              String text, Metadata metadata, String timestamp) {
        ObjectNode doc = mapper.createObjectNode();

        // Grundlegende Informationen
        doc.put("url", url);
        doc.put("crawl_timestamp", timestamp);
        doc.put("domain", extractDomain(url));

        // Strukturierte Daten: Events
        ArrayNode eventsArray = doc.putArray("events");
        if (events != null) {
            for (Map<String, String> event : events) {
                ObjectNode eventNode = mapper.createObjectNode();
                for (Map.Entry<String, String> entry : event.entrySet()) {
                    eventNode.put(entry.getKey(), entry.getValue());
                }
                eventsArray.add(eventNode);
            }
        }

        // Strukturierte Daten: News
        ArrayNode newsArray = doc.putArray("news");
        if (news != null) {
            for (Map<String, String> newsItem : news) {
                ObjectNode newsNode = mapper.createObjectNode();
                for (Map.Entry<String, String> entry : newsItem.entrySet()) {
                    newsNode.put(entry.getKey(), entry.getValue());
                }
                newsArray.add(newsNode);
            }
        }

        // Strukturierte Daten: Studiengänge
        ArrayNode coursesArray = doc.putArray("courses");
        if (courses != null) {
            for (String course : courses) {
                coursesArray.add(course);
            }
        }

        // Content-Sektion mit strukturiertem Text
        ObjectNode contentNode = doc.putObject("content");

        // Volltext
        if (text != null && !text.isEmpty()) {
            contentNode.put("full_text", text);

            // Titel extrahieren (erster TITLE:-Abschnitt oder erste Zeile)
            String title = "";
            if (text.startsWith("TITLE:")) {
                int endOfTitle = text.indexOf("\n\n");
                if (endOfTitle > 0) {
                    title = text.substring(6, endOfTitle).trim();
                }
            } else {
                int endOfLine = text.indexOf('\n');
                if (endOfLine > 0) {
                    title = text.substring(0, endOfLine).trim();
                } else {
                    title = text.substring(0, Math.min(text.length(), 100)).trim();
                }
            }
            contentNode.put("title", title);

            // Textzusammenfassung (erste 200 Zeichen oder erster Abschnitt)
            String summary = "";
            if (text.contains("SECTION:")) {
                Matcher m = SECTION_PATTERN.matcher(text);
                if (m.find()) {
                    summary = m.group(1) + ": " + m.group(2).trim();
                    summary = summary.substring(0, Math.min(summary.length(), 200));
                    if (summary.length() == 200) summary += "...";
                }
            } else {
                summary = text.substring(0, Math.min(text.length(), 200)).trim();
                if (summary.length() == 200) summary += "...";
            }
            contentNode.put("summary", summary);

            // Wortanzahl berechnen
            String[] words = text.split("\\s+");
            contentNode.put("word_count", words.length);

            // Abschnitte extrahieren
            ArrayNode sectionsArray = contentNode.putArray("sections");

            // SECTION: Abschnitte finden
            Matcher sectionMatcher = SECTION_PATTERN.matcher(text);
            while (sectionMatcher.find()) {
                String heading = sectionMatcher.group(1).trim();
                String content = sectionMatcher.group(2).trim();

                if (!content.isEmpty()) {
                    ObjectNode sectionNode = mapper.createObjectNode();
                    sectionNode.put("heading", heading);
                    sectionNode.put("content", content);
                    sectionsArray.add(sectionNode);
                }
            }

            // LIST: Abschnitte finden
            ArrayNode listsArray = contentNode.putArray("lists");
            Matcher listMatcher = LIST_PATTERN.matcher(text);
            while (listMatcher.find()) {
                String listContent = listMatcher.group(1).trim();
                String[] items = listContent.split("\n");

                ArrayNode itemsArray = mapper.createArrayNode();
                for (String item : items) {
                    item = item.trim();
                    if (item.startsWith("- ")) {
                        item = item.substring(2);
                    }
                    if (!item.isEmpty()) {
                        itemsArray.add(item);
                    }
                }

                if (itemsArray.size() > 0) {
                    listsArray.add(itemsArray);
                }
            }
        } else {
            contentNode.put("full_text", "");
            contentNode.put("title", "");
            contentNode.put("summary", "");
            contentNode.put("word_count", 0);
        }

        // Metadaten, falls verfügbar
        if (metadata != null) {
            ObjectNode metadataNode = doc.putObject("metadata");

            for (String key : metadata.keySet()) {
                String[] values = metadata.getValues(key);
                if (values.length == 1) {
                    metadataNode.put(key, values[0]);
                } else if (values.length > 1) {
                    ArrayNode valuesArray = metadataNode.putArray(key);
                    for (String value : values) {
                        valuesArray.add(value);
                    }
                }
            }

            // Metadaten-basierte Kategorisierung
            if (url.contains("/studium/") ||
                    (metadata.getFirstValue("navigation") != null &&
                            metadata.getFirstValue("navigation").contains("Studium"))) {
                doc.put("category", "studium");
            } else if (url.contains("/forschung/") ||
                    (metadata.getFirstValue("navigation") != null &&
                            metadata.getFirstValue("navigation").contains("Forschung"))) {
                doc.put("category", "forschung");
            } else if (url.contains("/international") ||
                    (metadata.getFirstValue("navigation") != null &&
                            metadata.getFirstValue("navigation").contains("International"))) {
                doc.put("category", "international");
            } else {
                doc.put("category", "allgemein");
            }
        }

        return doc;
    }

    /**
     * Aktualisiert die Index-Datei mit einer Zusammenfassung der gecrawlten URL
     */
    private synchronized void updateIndex(String url, String domain, String filePath,
                                          String timestamp, List<Map<String, String>> events,
                                          List<Map<String, String>> news, List<String> courses) throws IOException {
        // Aktuelle Index-Datei lesen
        String indexContent = new String(Files.readAllBytes(indexFilePath), StandardCharsets.UTF_8);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(indexContent);

        // last_updated Zeitstempel aktualisieren
        rootNode.put("last_updated", Instant.now().toString());

        // crawled_urls Array abrufen
        ArrayNode urlsArray = (ArrayNode) rootNode.get("crawled_urls");

        // Zusammenfassung für diese URL erstellen
        ObjectNode urlSummary = mapper.createObjectNode();
        urlSummary.put("url", url);
        urlSummary.put("domain", domain);
        urlSummary.put("file_path", filePath);
        urlSummary.put("crawl_timestamp", timestamp);
        urlSummary.put("events_count", events != null ? events.size() : 0);
        urlSummary.put("news_count", news != null ? news.size() : 0);
        urlSummary.put("courses_count", courses != null ? courses.size() : 0);

        // URL-Typ identifizieren (für bessere Kategorisierung im RAG-System)
        if (url.contains("/studium/")) {
            urlSummary.put("page_type", "studium");
        } else if (url.contains("/forschung/")) {
            urlSummary.put("page_type", "forschung");
        } else if (url.contains("/news/") || url.contains("/aktuelles/")) {
            urlSummary.put("page_type", "news");
        } else if (url.contains("/events/") || url.contains("/veranstaltungen/")) {
            urlSummary.put("page_type", "events");
        } else if (url.contains("/kontakt/")) {
            urlSummary.put("page_type", "kontakt");
        } else {
            urlSummary.put("page_type", "allgemein");
        }

        // Zum Array hinzufügen
        urlsArray.add(urlSummary);

        // Aktualisierte Index-Datei zurückschreiben
        String updatedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        Files.write(indexFilePath, updatedJson.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Extrahiert die Domain aus einer URL
     */
    private String extractDomain(String url) {
        try {
            URI uri = new URI(url);
            String domain = uri.getHost();
            return domain != null ? domain.startsWith("www.") ? domain.substring(4) : domain : "unknown-domain";
        } catch (URISyntaxException e) {
            return "invalid-url";
        }
    }

    /**
     * Erstellt einen dateisystemsicheren Namen aus dem angegebenen String
     */
    private String sanitizeForFilename(String input) {
        return input.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields());
    }
}