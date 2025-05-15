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

/**
 * Enhanced JSON File Writer Bolt that:
 * 1. Stores each page as a separate JSON file with detailed structure
 * 2. Maintains an index file with summaries of all crawled URLs
 * 3. Organizes content by domain to avoid filesystem issues
 */
public class EnhancedJSONFileWriterBolt extends BaseRichBolt {

    private OutputCollector collector;
    private final String outputDir;
    private final String indexFileName;
    private ObjectMapper mapper;
    private Path indexFilePath;
    private final ConcurrentHashMap<String, AtomicInteger> domainCounters = new ConcurrentHashMap<>();
    // GEÄNDERT: DateTimeFormatter entfernt als Instanzvariable

    public EnhancedJSONFileWriterBolt(String outputDir, String indexFileName) {
        this.outputDir = outputDir;
        this.indexFileName = indexFileName != null ? indexFileName : "crawl_index.json";
    }

    public EnhancedJSONFileWriterBolt(String outputDir) {
        this(outputDir, "crawl_index.json");
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        this.mapper = new ObjectMapper();

        // HINZUGEFÜGT: Ausgabeverzeichnis mit Debug-Ausgabe
        System.out.println("Initializing EnhancedJSONFileWriterBolt with output directory: " + outputDir);

        try {
            // Create main output directory
            Files.createDirectories(Paths.get(outputDir));
            System.out.println("Created output directory: " + outputDir);

            // Initialize or load the index file
            this.indexFilePath = Paths.get(outputDir, indexFileName);

            if (!Files.exists(indexFilePath)) {
                // Create a new index file with an empty array
                ObjectNode rootNode = mapper.createObjectNode();
                rootNode.put("last_updated", Instant.now().toString());
                rootNode.putArray("crawled_urls");

                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
                Files.write(indexFilePath, json.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("Created index file: " + indexFilePath);
            }

        } catch (Exception e) {
            System.err.println("Failed to initialize EnhancedJSONFileWriterBolt: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize EnhancedJSONFileWriterBolt", e);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            String timestamp = Instant.now().toString();

            System.out.println("Processing URL: " + url); // Debug-Ausgabe

            // Get standard fields
            List<String> events = null;
            List<String> news = null;
            String text = null;

            try {
                @SuppressWarnings("unchecked")
                List<String> eventsList = (List<String>) tuple.getValueByField("events");
                events = eventsList;
            } catch (Exception e) {
                System.out.println("No events found in tuple: " + e.getMessage());
                events = Collections.emptyList(); // Leere Liste statt null
            }

            try {
                @SuppressWarnings("unchecked")
                List<String> newsList = (List<String>) tuple.getValueByField("news");
                news = newsList;
            } catch (Exception e) {
                System.out.println("No news found in tuple: " + e.getMessage());
                news = Collections.emptyList(); // Leere Liste statt null
            }

            try {
                text = tuple.getStringByField("text");
            } catch (Exception e) {
                System.out.println("No text found in tuple: " + e.getMessage());
                text = ""; // Leerer String statt null
            }

            // Try to get metadata if available
            Metadata metadata = null;
            try {
                metadata = (Metadata) tuple.getValueByField("metadata");
            } catch (Exception e) {
                System.out.println("No metadata found in tuple: " + e.getMessage());
            }

            // Create the detailed document JSON
            ObjectNode doc = createDetailedDocument(url, events, news, text, metadata, timestamp);

            // Get domain to organize files
            String domain = extractDomain(url);
            Path domainDir = Paths.get(outputDir, "domains", domain);
            Files.createDirectories(domainDir);

            System.out.println("Created domain directory: " + domainDir); // Debug-Ausgabe

            // Create a unique filename
            int count = domainCounters.computeIfAbsent(domain, k -> new AtomicInteger(0)).incrementAndGet();
            String filename = String.format("%s_%d.json", sanitizeForFilename(domain), count);
            Path filePath = domainDir.resolve(filename);

            // Write detailed JSON to file
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Files.write(filePath, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("Wrote detailed JSON to: " + filePath); // Debug-Ausgabe

            // Update the index file with a summary
            updateIndex(url, domain, filePath.toString(), timestamp, events, news);

            System.out.println("Updated index file"); // Debug-Ausgabe

            collector.ack(tuple);
        } catch (Exception e) {
            System.err.println("Error processing tuple: " + e.getMessage());
            e.printStackTrace();
            collector.reportError(e);
            collector.fail(tuple);
        }
    }

    /**
     * Creates a detailed JSON document with all available information
     */
    private ObjectNode createDetailedDocument(String url, List<String> events, List<String> news,
                                              String text, Metadata metadata, String timestamp) {
        ObjectNode doc = mapper.createObjectNode();

        // Basic information
        doc.put("url", url);
        doc.put("crawl_timestamp", timestamp);
        doc.put("domain", extractDomain(url));

        // Structured data
        doc.putPOJO("events", events);
        doc.putPOJO("news", news);

        // Content section
        ObjectNode contentNode = doc.putObject("content");
        contentNode.put("full_text", text != null ? text : "");

        // Extract title if possible (first line or first 100 chars)
        String title = "";
        if (text != null && !text.isEmpty()) {
            int endOfLine = text.indexOf('\n');
            if (endOfLine > 0) {
                title = text.substring(0, endOfLine).trim();
            } else {
                title = text.substring(0, Math.min(text.length(), 100)).trim();
            }
        }
        contentNode.put("title", title);

        // Text summary (first 200 chars)
        if (text != null && !text.isEmpty()) {
            String summary = text.substring(0, Math.min(text.length(), 200)).trim();
            if (summary.length() == 200) summary += "...";
            contentNode.put("summary", summary);
        } else {
            contentNode.put("summary", "");
        }

        // Calculate word count
        if (text != null) {
            String[] words = text.split("\\s+");
            contentNode.put("word_count", words.length);
        } else {
            contentNode.put("word_count", 0);
        }

        // Metadata if available
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
        }

        return doc;
    }

    /**
     * Updates the index file with a summary of the crawled URL
     */
    private synchronized void updateIndex(String url, String domain, String filePath,
                                          String timestamp, List<String> events, List<String> news) throws IOException {
        // Read the current index
        String indexContent = new String(Files.readAllBytes(indexFilePath), StandardCharsets.UTF_8);
        ObjectNode rootNode = (ObjectNode) mapper.readTree(indexContent);

        // Update last_updated timestamp
        rootNode.put("last_updated", Instant.now().toString());

        // Get the crawled_urls array
        ArrayNode urlsArray = (ArrayNode) rootNode.get("crawled_urls");

        // Create a summary for this URL
        ObjectNode urlSummary = mapper.createObjectNode();
        urlSummary.put("url", url);
        urlSummary.put("domain", domain);
        urlSummary.put("file_path", filePath);
        urlSummary.put("crawl_timestamp", timestamp);
        urlSummary.put("events_count", events != null ? events.size() : 0);
        urlSummary.put("news_count", news != null ? news.size() : 0);

        // Add to the array
        urlsArray.add(urlSummary);

        // Write the updated index back
        String updatedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);
        Files.write(indexFilePath, updatedJson.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Extracts the domain from a URL
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
     * Creates a filesystem-safe name from the given string
     */
    private String sanitizeForFilename(String input) {
        return input.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // This bolt does not emit anything
        declarer.declare(new Fields());
    }

    @Override
    public void cleanup() {
        // Any final cleanup could go here
    }
}