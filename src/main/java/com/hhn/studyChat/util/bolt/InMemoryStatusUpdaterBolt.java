package com.hhn.studyChat.util.bolt;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.persistence.Status;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.hhn.studyChat.util.StudyChatConstants;
import com.hhn.studyChat.util.MetadataUtils;

/**
 * In-Memory Status Updater für URL-Management
 * Verwaltet den Status der URLs und leitet neue URLs zurück in die Pipeline
 */
public class InMemoryStatusUpdaterBolt extends BaseRichBolt {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryStatusUpdaterBolt.class);

    private OutputCollector collector;

    // In-Memory Status-Speicher
    private final Map<String, UrlStatus> urlStatusMap = new ConcurrentHashMap<>();

    // Statistiken
    private final AtomicInteger discoveredUrls = new AtomicInteger(0);
    private final AtomicInteger processedUrls = new AtomicInteger(0);
    private final AtomicInteger failedUrls = new AtomicInteger(0);
    private final AtomicInteger duplicateUrls = new AtomicInteger(0);

    /**
     * Interne Klasse für URL-Status
     */
    private static class UrlStatus {
        final String url;
        final Status status;
        final Metadata metadata;
        final long timestamp;
        int retryCount;

        UrlStatus(String url, Status status, Metadata metadata) {
            this.url = url;
            this.status = status;
            this.metadata = metadata;
            this.timestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        logger.info("InMemoryStatusUpdaterBolt initialized");
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            Metadata metadata = (Metadata) tuple.getValueByField("metadata");

            // Status aus dem Tuple ermitteln
            Status status = getStatusFromTuple(tuple);

            logger.debug("Processing URL: {} with status: {}", url, status);

            // Je nach Status unterschiedlich verarbeiten
            switch (status) {
                case DISCOVERED:
                    handleDiscoveredUrl(url, metadata, tuple);
                    break;
                case FETCHED:
                    handleFetchedUrl(url, metadata, tuple);
                    break;
                case ERROR:
                case FETCH_ERROR:
                    handleErrorUrl(url, metadata, tuple);
                    break;
                case REDIRECTION:
                    handleRedirectionUrl(url, metadata, tuple);
                    break;
                default:
                    logger.debug("Unhandled status: {} for URL: {}", status, url);
                    collector.ack(tuple);
                    break;
            }

            // Periodische Statistik-Ausgabe
            if ((discoveredUrls.get() + processedUrls.get()) % StudyChatConstants.STATS_LOG_INTERVAL == 0) {
                logStatistics();
            }

        } catch (Exception e) {
            logger.error("Error processing tuple in StatusUpdater: {}", e.getMessage(), e);
            collector.fail(tuple);
        }
    }

    /**
     * Behandelt neu entdeckte URLs
     */
    private void handleDiscoveredUrl(String url, Metadata metadata, Tuple tuple) {
        // Prüfen, ob URL bereits bekannt ist
        if (urlStatusMap.containsKey(url)) {
            logger.debug("Duplicate URL discovered: {}", url);
            duplicateUrls.incrementAndGet();
            collector.ack(tuple);
            return;
        }

        // URL als entdeckt markieren
        UrlStatus urlStatus = new UrlStatus(url, Status.DISCOVERED, metadata);
        urlStatusMap.put(url, urlStatus);
        discoveredUrls.incrementAndGet();

        logger.debug("New URL discovered: {}", url);

        // URL für das Crawling zur Verfügung stellen (zurück in die Pipeline)
        collector.emit("status", tuple, new Values(url, metadata, Status.DISCOVERED));
        collector.ack(tuple);
    }

    /**
     * Behandelt erfolgreich gefetchte URLs
     */
    private void handleFetchedUrl(String url, Metadata metadata, Tuple tuple) {
        UrlStatus urlStatus = urlStatusMap.get(url);
        if (urlStatus != null) {
            // Status auf FETCHED aktualisieren
            UrlStatus updatedStatus = new UrlStatus(url, Status.FETCHED, metadata);
            urlStatusMap.put(url, updatedStatus);
        }

        processedUrls.incrementAndGet();
        logger.debug("URL successfully fetched: {}", url);
        collector.ack(tuple);
    }

    /**
     * Behandelt URLs mit Fehlern
     */
    private void handleErrorUrl(String url, Metadata metadata, Tuple tuple) {
        UrlStatus urlStatus = urlStatusMap.get(url);
        if (urlStatus != null) {
            urlStatus.retryCount++;

            // Begrenzte Anzahl von Wiederholungsversuchen
            if (urlStatus.retryCount < StudyChatConstants.DEFAULT_MAX_RETRIES) {
                logger.debug("Retrying URL (attempt {}): {}", urlStatus.retryCount, url);

                // URL erneut für das Crawling zur Verfügung stellen
                collector.emit("status", tuple, new Values(url, metadata, Status.DISCOVERED));
            } else {
                logger.warn("URL failed after {} attempts: {}", urlStatus.retryCount, url);
                UrlStatus errorStatus = new UrlStatus(url, Status.ERROR, metadata);
                urlStatusMap.put(url, errorStatus);
            }
        }

        failedUrls.incrementAndGet();
        collector.ack(tuple);
    }

    /**
     * Behandelt Weiterleitungen
     */
    private void handleRedirectionUrl(String url, Metadata metadata, Tuple tuple) {
        // Bei Weiterleitungen die neue URL extrahieren und verarbeiten
        String redirectUrl = metadata.getFirstValue("_redirTo");
        if (redirectUrl != null && !redirectUrl.equals(url)) {
            logger.debug("Redirect from {} to {}", url, redirectUrl);

            // Neue URL als entdeckt markieren
            if (!urlStatusMap.containsKey(redirectUrl)) {
                Metadata newMetadata = MetadataUtils.copyMetadataWithValue(metadata,
                        StudyChatConstants.REDIRECT_SOURCE_KEY, url);

                collector.emit("status", tuple, new Values(redirectUrl, newMetadata, Status.DISCOVERED));
            }
        }

        // Original-URL als weitergeleitet markieren
        UrlStatus redirectStatus = new UrlStatus(url, Status.REDIRECTION, metadata);
        urlStatusMap.put(url, redirectStatus);

        collector.ack(tuple);
    }

    /**
     * Ermittelt den Status aus dem Tuple
     */
    private Status getStatusFromTuple(Tuple tuple) {
        // Versuche Status aus dem Tuple zu extrahieren
        try {
            if (tuple.contains("status")) {
                Object statusObj = tuple.getValueByField("status");
                if (statusObj instanceof Status) {
                    return (Status) statusObj;
                } else if (statusObj instanceof String) {
                    return Status.valueOf((String) statusObj);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract status from tuple, using default");
        }

        // Fallback: Status basierend auf dem Stream bestimmen
        String streamId = tuple.getSourceStreamId();
        if (Constants.StatusStreamName.equals(streamId)) {
            // Wenn es aus dem Status-Stream kommt, versuche Metadaten zu analysieren
            try {
                Metadata metadata = (Metadata) tuple.getValueByField("metadata");
                String statusStr = metadata.getFirstValue(StudyChatConstants.STATUS_KEY);
                if (statusStr != null) {
                    return Status.valueOf(statusStr);
                }
            } catch (Exception e) {
                logger.debug("Could not determine status from metadata");
            }
            return Status.FETCHED; // Standard für Status-Stream
        }

        // Standard: neu entdeckte URL
        return Status.DISCOVERED;
    }

    /**
     * Gibt Statistiken aus
     */
    private void logStatistics() {
        logger.info("URL Status Statistics - Discovered: {}, Processed: {}, Failed: {}, Duplicates: {}, Total in memory: {}",
                discoveredUrls.get(), processedUrls.get(), failedUrls.get(),
                duplicateUrls.get(), urlStatusMap.size());
    }

    @Override
    public void cleanup() {
        super.cleanup();
        logger.info("InMemoryStatusUpdaterBolt cleanup - Final statistics:");
        logStatistics();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // Standard output für weiterzuleitende URLs
        declarer.declare(new Fields("url", "metadata"));

        // Status stream für neue URLs
        declarer.declareStream("status", new Fields("url", "metadata", "status"));
    }

    /**
     * Getter für Monitoring und Tests
     */
    public int getDiscoveredUrlsCount() {
        return discoveredUrls.get();
    }

    public int getProcessedUrlsCount() {
        return processedUrls.get();
    }

    public int getFailedUrlsCount() {
        return failedUrls.get();
    }

    public int getTotalUrlsInMemory() {
        return urlStatusMap.size();
    }
}