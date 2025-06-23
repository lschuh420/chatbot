package com.hhn.studyChat.util.bolt;

import com.digitalpebble.stormcrawler.Metadata;
import com.hhn.studyChat.util.StudyChatConstants;
import com.hhn.studyChat.util.MetadataUtils;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kombinierter Bolt: Extrahiert URLs aus geparsten Inhalten UND kontrolliert die Crawler-Tiefe
 * Direkte Rückgabe an URLPartitioner für rekursives Crawling
 */
public class URLExtractorBolt extends BaseRichBolt {

    private static final Logger logger = LoggerFactory.getLogger(URLExtractorBolt.class);
    private OutputCollector collector;

    // Tiefenkontrolle
    private int maxDepth = StudyChatConstants.DEFAULT_MAX_DEPTH;

    // Statistiken
    private int totalExtractedUrls = 0;
    private int totalFilteredByDepth = 0;
    private int totalPassedUrls = 0;

    // In-Memory Duplikatsprüfung
    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;

        // Maximale Tiefe aus der Konfiguration lesen
        Object maxDepthObj = stormConf.get(StudyChatConstants.MAX_DEPTH_CONFIG_KEY);
        if (maxDepthObj != null) {
            try {
                this.maxDepth = Integer.parseInt(maxDepthObj.toString());
                logger.info("Max depth set to: {}", this.maxDepth);
            } catch (NumberFormatException e) {
                logger.warn("Invalid max.depth value: {}, using default: {}", maxDepthObj, this.maxDepth);
            }
        } else {
            logger.info("No max.depth configured, using default: {}", this.maxDepth);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            byte[] content = tuple.getBinaryByField("content");
            Metadata metadata = (Metadata) tuple.getValueByField("metadata");

            // Aktuelle Tiefe aus Metadaten abrufen
            int currentDepth = MetadataUtils.getDepth(metadata);

            logger.debug("Extracting URLs from {} at depth {}", url, currentDepth);

            // HTML-Inhalt parsen
            String html = new String(content, StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, url);

            // URLs aus Links extrahieren
            Set<String> extractedUrls = extractUrls(doc, url);
            totalExtractedUrls += extractedUrls.size();

            logger.info("Extracted {} URLs from {} at depth {}", extractedUrls.size(), url, currentDepth);

            // Jede extrahierte URL prüfen und emittieren
            for (String extractedUrl : extractedUrls) {
                if (isValidUrl(extractedUrl) && !seenUrls.contains(extractedUrl)) {

                    // Tiefenkontrolle: Neue Tiefe berechnen
                    int nextDepth = currentDepth + 1;

                    // Prüfen, ob maximale Tiefe überschritten wird
                    if (nextDepth > maxDepth) {
                        logger.debug("URL filtered due to depth limit: {} (depth: {} > max: {})",
                                extractedUrl, nextDepth, maxDepth);
                        totalFilteredByDepth++;
                        continue; // URL überspringen
                    }

                    // URL als gesehen markieren
                    seenUrls.add(extractedUrl);

                    // Metadata für neue URL erstellen
                    Metadata newMetadata = new Metadata();
                    MetadataUtils.setDepth(newMetadata, nextDepth);
                    newMetadata.addValue(StudyChatConstants.PARENT_URL_KEY, url);
                    newMetadata.addValue(StudyChatConstants.CURRENT_DEPTH_KEY, String.valueOf(currentDepth));
                    newMetadata.addValue(StudyChatConstants.NEXT_DEPTH_KEY, String.valueOf(nextDepth));
                    newMetadata.addValue(StudyChatConstants.MAX_DEPTH_KEY, String.valueOf(maxDepth));

                    // URL emittieren (geht direkt zurück zum URLPartitioner)
                    collector.emit(tuple, new Values(extractedUrl, newMetadata));
                    totalPassedUrls++;

                    logger.debug("Emitted URL: {} with depth {} (parent depth: {})",
                            extractedUrl, nextDepth, currentDepth);
                }
            }

            // Periodische Statistik-Ausgabe
            if (totalExtractedUrls % 50 == 0 && totalExtractedUrls > 0) {
                logger.info("URL Extraction Stats - Total extracted: {}, Passed: {}, Filtered by depth: {}, Seen URLs: {}",
                        totalExtractedUrls, totalPassedUrls, totalFilteredByDepth, seenUrls.size());
            }

            collector.ack(tuple);

        } catch (Exception e) {
            logger.error("Error extracting URLs from tuple: {}", e.getMessage(), e);
            collector.fail(tuple);
        }
    }

    /**
     * Extrahiert URLs aus einem HTML-Dokument
     */
    private Set<String> extractUrls(Document doc, String baseUrl) {
        Set<String> urls = new HashSet<>();

        // Links aus <a href="..."> Tags
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }

        // Links aus <link href="..."> Tags (z.B. canonical)
        Elements linkTags = doc.select("link[href]");
        for (Element link : linkTags) {
            String href = link.attr("abs:href");
            String rel = link.attr("rel");

            // Nur bestimmte Link-Typen verfolgen
            if ("canonical".equals(rel) || "alternate".equals(rel)) {
                if (!href.isEmpty()) {
                    urls.add(href);
                }
            }
        }

        // Spezielle HHN-Website Patterns
        extractHHNSpecificUrls(doc, urls);

        return urls;
    }

    /**
     * Extrahiert spezielle URLs von der HHN-Website
     */
    private void extractHHNSpecificUrls(Document doc, Set<String> urls) {
        // Navigation Links
        Elements navLinks = doc.select("nav a, .navigation a, .main-menu a, .navbar a");
        for (Element link : navLinks) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }

        // Breadcrumb Links
        Elements breadcrumbs = doc.select(".breadcrumb a, .breadcrumbs a");
        for (Element link : breadcrumbs) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }

        // Studiengang-Links
        Elements courseLinks = doc.select("a[href*='/studium/'], a[href*='/bachelor/'], a[href*='/master/']");
        for (Element link : courseLinks) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }

        // News und Event Links
        Elements newsEventLinks = doc.select("a[href*='/news/'], a[href*='/events/'], a[href*='/aktuelles/']");
        for (Element link : newsEventLinks) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }

        // Fakultäts-Links
        Elements facultyLinks = doc.select("a[href*='/fakultaet/'], a[href*='/faculty/']");
        for (Element link : facultyLinks) {
            String href = link.attr("abs:href");
            if (!href.isEmpty()) {
                urls.add(href);
            }
        }
    }

    /**
     * Validiert eine URL und prüft gegen erweiterte Filter-Regeln
     */
    private boolean isValidUrl(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return false;
        }

        try {
            URL url = new URL(urlStr);
            String protocol = url.getProtocol();
            String host = url.getHost();
            String path = url.getPath().toLowerCase();

            // Nur HTTP/HTTPS URLs
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                logger.debug("Filtered URL (invalid protocol): {}", urlStr);
                return false;
            }

            // Host darf nicht leer sein
            if (host == null || host.trim().isEmpty()) {
                logger.debug("Filtered URL (empty host): {}", urlStr);
                return false;
            }

            // Nur HHN-Domain erlauben (erweiterte Patterns)
            if (!host.contains("hs-heilbronn.de") && !host.contains("heilbronn-university.com")) {
                logger.debug("Filtered URL (wrong domain): {}", urlStr);
                return false;
            }

            // === DATEITYP-FILTER ===
            if (path.endsWith(".pdf") || path.endsWith(".doc") || path.endsWith(".docx") ||
                    path.endsWith(".xls") || path.endsWith(".xlsx") || path.endsWith(".ppt") ||
                    path.endsWith(".pptx") || path.endsWith(".zip") || path.endsWith(".rar") ||
                    path.endsWith(".exe") || path.endsWith(".dmg") || path.endsWith(".iso")) {
                logger.debug("Filtered URL (document file): {}", urlStr);
                return false;
            }

            // === MEDIEN-FILTER ===
            if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                    path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".webp") ||
                    path.endsWith(".mp4") || path.endsWith(".avi") || path.endsWith(".mov") ||
                    path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".css") ||
                    path.endsWith(".js") || path.endsWith(".ico") || path.endsWith(".woff") ||
                    path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".eot")) {
                logger.debug("Filtered URL (media file): {}", urlStr);
                return false;
            }

            // === FRAGMENT-FILTER ===
            if (urlStr.contains("#")) {
                logger.debug("Filtered URL (contains fragment): {}", urlStr);
                return false;
            }

            // === PFAD-FILTER ===
            if (path.contains("/api/") || path.contains("/admin/") ||
                    path.contains("/wp-admin/") || path.contains("/login") ||
                    path.contains("/logout") || path.contains("/assets/") ||
                    path.contains("/static/") || path.contains("/css/") ||
                    path.contains("/js/") || path.contains("/images/") ||
                    path.contains("/img/") || path.contains("/fonts/")) {
                logger.debug("Filtered URL (admin/static path): {}", urlStr);
                return false;
            }

            // === QUERY-PARAMETER-FILTER ===
            String query = url.getQuery();
            if (query != null) {
                // Filter bestimmte Parameter
                if (query.contains("print=") || query.contains("popup=") ||
                        query.contains("download=") || query.contains("format=pdf") ||
                        query.startsWith("utm_") || query.contains("&utm_")) {
                    logger.debug("Filtered URL (unwanted parameters): {}", urlStr);
                    return false;
                }

                // Zu viele Parameter (vermutlich dynamisch generiert)
                if (query.split("&").length > 5) {
                    logger.debug("Filtered URL (too many parameters): {}", urlStr);
                    return false;
                }
            }

            // === PFAD-TIEFE-FILTER ===
            String[] pathSegments = path.split("/");
            if (pathSegments.length > 8) { // Maximal 8 Pfad-Segmente
                logger.debug("Filtered URL (path too deep): {}", urlStr);
                return false;
            }

            // === SPEZIELLE HHN-FILTER ===
            // Erlaube wichtige Bereiche
            if (path.contains("/studium/") || path.contains("/forschung/") ||
                    path.contains("/international/") || path.contains("/news/") ||
                    path.contains("/aktuelles/") || path.contains("/events/") ||
                    path.contains("/veranstaltungen/") || path.contains("/fakultaet/") ||
                    path.contains("/faculty/") || path.contains("/profil/") ||
                    path.contains("/leitbild") || path.contains("/kontakt/") ||
                    path.contains("/hochschule/") || path.contains("/campus/") ||
                    path.equals("/de") || path.equals("/en") || path.equals("/de/") ||
                    path.equals("/en/") || path.equals("/")) {
                return true;
            }

            // Wenn es keine spezielle Kategorie ist, trotzdem erlauben (aber konservativ)
            // Nur wenn der Pfad nicht zu speziell aussieht
            if (!path.contains("?") && !path.contains("=") &&
                    pathSegments.length <= 4) {
                return true;
            }

            logger.debug("Filtered URL (no matching category): {}", urlStr);
            return false;

        } catch (MalformedURLException e) {
            logger.debug("Filtered URL (malformed): {}", urlStr);
            return false;
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        logger.info("URLExtractorBolt cleanup - Final stats: Total extracted: {}, Passed: {}, Filtered by depth: {}, Unique URLs seen: {}",
                totalExtractedUrls, totalPassedUrls, totalFilteredByDepth, seenUrls.size());
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // Einfache Ausgabe: URL und Metadata zurück zum URLPartitioner
        declarer.declare(new Fields("url", "metadata"));
    }

    /**
     * Getter für Monitoring und Tests
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    public int getTotalExtractedUrls() {
        return totalExtractedUrls;
    }

    public int getTotalFilteredByDepth() {
        return totalFilteredByDepth;
    }

    public int getTotalPassedUrls() {
        return totalPassedUrls;
    }

    public int getUniqueUrlsCount() {
        return seenUrls.size();
    }
}