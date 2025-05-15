package com.hhn.studyChat.util;

import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.generated.StormTopology;
import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.hhn.studyChat.CrawlTopology;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;

public class TopologyRunner {

    public static void runTopology(String[] seedUrls, int maxDepth, String outputDir, String jobId) throws Exception {
        // Config-Map erstellen
        Config conf = new Config();

        // Wichtige Konfigurationen setzen
        conf.put("crawler.id", jobId);
        conf.put("fetcher.continue.at.depth", true);
        conf.put("metadata.track.depth", true);
        conf.put("max.depth", maxDepth);
        conf.put("output.dir", outputDir);

        // CrawlTopology erstellen
        CrawlTopology topology = new CrawlTopology(seedUrls);

        try (LocalCluster cluster = new LocalCluster()) {
            cluster.submitTopology(jobId, conf, topology.createTopology().createTopology());

            // Warte auf Abschluss (z.B. 5 Minuten oder spezifisches Event)
            Thread.sleep(300000);

            // Topologie herunterfahren
            cluster.killTopology(jobId);
        }
    }
}