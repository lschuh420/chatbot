package com.hhn.studyChat.util;

import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.generated.StormTopology;
import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.hhn.studyChat.CrawlTopology;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

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

        // HTTP Agent Konfiguration setzen
        conf.put("http.agent.name", "StudyChat-Bot");
        conf.put("http.agent.version", "1.0");
        conf.put("http.agent.description", "StudyChat Web Crawler");
        conf.put("http.agent.url", "http://example.com/bot");
        conf.put("http.agent.email", "contact@example.com");

        // Benutzerdefinierte Konfiguration aus Properties-Datei laden
        loadCustomConfig(conf);

        // CrawlTopology erstellen
        CrawlTopology topology = new CrawlTopology(seedUrls);

        try (LocalCluster cluster = new LocalCluster()) {
            cluster.submitTopology(jobId, conf, topology.createTopology().createTopology());

            // Warte auf Abschluss (z.B. 5 Minuten oder spezifisches Event)
            System.out.println("Topology gestartet, warte auf Abschluss...");
            Thread.sleep(300000); // 5 Minuten

            // Topologie herunterfahren
            System.out.println("Beende Topology...");
            cluster.killTopology(jobId);
            System.out.println("Topology beendet");
        }
    }

    /**
     * Lädt zusätzliche Konfigurationen aus einer Properties-Datei
     */
    private static void loadCustomConfig(Config conf) {
        try {
            // Zuerst versuchen, die Datei im Ressourcenverzeichnis zu finden
            InputStream is = TopologyRunner.class.getClassLoader().getResourceAsStream("crawler-config.properties");

            // Wenn nicht als Ressource verfügbar, als Datei im aktuellen Verzeichnis suchen
            if (is == null) {
                File file = new File("crawler-config.properties");
                if (file.exists()) {
                    is = new FileInputStream(file);
                }
            }

            // Wenn eine Konfigurationsdatei gefunden wurde, laden
            if (is != null) {
                Properties props = new Properties();
                props.load(is);

                // Alle Properties in die Storm-Konfiguration übertragen
                for (String key : props.stringPropertyNames()) {
                    conf.put(key, props.getProperty(key));
                }

                is.close();
                System.out.println("Zusätzliche Crawler-Konfiguration erfolgreich geladen.");
            }
        } catch (Exception e) {
            System.err.println("Fehler beim Laden der zusätzlichen Crawler-Konfiguration: " + e.getMessage());
            e.printStackTrace();
        }
    }
}