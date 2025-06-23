package com.hhn.studyChat.util.bolt;

import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.Metadata;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hhn.studyChat.util.StudyChatConstants;
import com.hhn.studyChat.util.MetadataUtils;

import java.util.Map;

/**
 * Kontrolliert die Crawler-Tiefe und verhindert das Crawlen über die maximale Tiefe hinaus
 */
public class DepthControlBolt extends BaseRichBolt {

    private static final Logger logger = LoggerFactory.getLogger(DepthControlBolt.class);

    private OutputCollector collector;
    private int maxDepth = StudyChatConstants.DEFAULT_MAX_DEPTH; // Standard-Maximaltiefe
    private int totalFilteredUrls = 0;
    private int totalPassedUrls = 0;

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
            Metadata metadata = (Metadata) tuple.getValueByField("metadata");

            // Aktuelle Tiefe aus Metadaten abrufen
            int currentDepth = MetadataUtils.getDepth(metadata);

            // Neue Tiefe für die nächste Ebene berechnen
            int nextDepth = currentDepth + 1;

            logger.debug("Processing URL: {} at current depth: {}, next depth would be: {}, max depth: {}",
                    url, currentDepth, nextDepth, maxDepth);

            // Prüfen, ob die maximale Tiefe bereits erreicht wurde
            if (nextDepth > maxDepth) {
                logger.debug("URL filtered due to depth limit: {} (depth: {} > max: {})", url, nextDepth, maxDepth);
                totalFilteredUrls++;

                // URL wird nicht weitergeleitet - Ende der Kette für diese URL
                collector.ack(tuple);
                return;
            }

            // URL ist innerhalb der erlaubten Tiefe
            totalPassedUrls++;

            // Neue Metadaten mit aktualisierter Tiefe erstellen
            Metadata newMetadata = MetadataUtils.copyMetadataWithValues(metadata,
                    StudyChatConstants.DEPTH_KEY, String.valueOf(nextDepth),
                    StudyChatConstants.CURRENT_DEPTH_KEY, String.valueOf(currentDepth),
                    StudyChatConstants.NEXT_DEPTH_KEY, String.valueOf(nextDepth),
                    StudyChatConstants.MAX_DEPTH_KEY, String.valueOf(maxDepth)
            );

            logger.debug("URL passed depth control: {} (next depth: {})", url, nextDepth);

            // Tuple mit aktualisierter Tiefe weiterleiten
            collector.emit(tuple, new Values(url, newMetadata));
            collector.ack(tuple);

            // Periodisches Logging der Statistiken
            if ((totalFilteredUrls + totalPassedUrls) % StudyChatConstants.DETAILED_STATS_LOG_INTERVAL == 0) {
                logger.info("Depth Control Stats - Passed: {}, Filtered: {}, Total: {}",
                        totalPassedUrls, totalFilteredUrls, totalPassedUrls + totalFilteredUrls);
            }

        } catch (Exception e) {
            logger.error("Error in DepthControlBolt for URL: {}",
                    tuple.getStringByField("url"), e);
            collector.fail(tuple);
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        logger.info("DepthControlBolt cleanup - Final stats: Passed: {}, Filtered: {}, Total: {}",
                totalPassedUrls, totalFilteredUrls, totalPassedUrls + totalFilteredUrls);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "metadata"));
    }

    /**
     * Getter für Tests und Monitoring
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    public int getTotalFilteredUrls() {
        return totalFilteredUrls;
    }

    public int getTotalPassedUrls() {
        return totalPassedUrls;
    }
}