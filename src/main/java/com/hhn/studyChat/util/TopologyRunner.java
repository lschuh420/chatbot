package com.hhn.studyChat.util;

import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import com.hhn.studyChat.CrawlTopology;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class TopologyRunner {

    public static void runTopology(String[] seedUrls, int maxDepth, String outputDir, String jobId) throws Exception {
        // Config-Map erstellen
        Config conf = new Config();

        // === WICHTIGE CRAWLER-KONFIGURATION ===
        conf.put(StudyChatConstants.CRAWLER_ID_CONFIG_KEY, jobId);
        conf.put(StudyChatConstants.MAX_DEPTH_CONFIG_KEY, maxDepth);
        conf.put(StudyChatConstants.OUTPUT_DIR_CONFIG_KEY, outputDir);

        // HTTP Agent Konfiguration
        conf.put("http.agent.name", StudyChatConstants.USER_AGENT_NAME);
        conf.put("http.agent.version", StudyChatConstants.USER_AGENT_VERSION);
        conf.put("http.agent.description", StudyChatConstants.USER_AGENT_DESCRIPTION);
        conf.put("http.agent.url", StudyChatConstants.USER_AGENT_URL);
        conf.put("http.agent.email", StudyChatConstants.USER_AGENT_EMAIL);

        // === REKURSIVE CRAWLER-KONFIGURATION ===
        // WICHTIG: Diese Einstellungen aktivieren rekursives Crawling!
        conf.put("parser.emitOutlinks", true);  // URLs aus geparsten Seiten extrahieren
        conf.put("parser.emitOutlinks.max.per.page", 60); // Max URLs pro Seite
        conf.put("fetcher.continue.at.depth", true); // Crawling in der Tiefe fortsetzen
        conf.put("metadata.track.depth", true); // Tiefe in Metadaten verfolgen

        // === STABILITÄT & PERFORMANCE ===
        conf.put("fetcher.server.delay", 2.0); // 2 Sekunden Delay zwischen Requests
        conf.put("fetcher.threads.number", 1); // Nur 1 Thread für Stabilität
        conf.put("fetcher.max.urls", 50); // Max URLs pro Batch

        // Storm-Konfiguration für Stabilität
        conf.put("topology.message.timeout.secs", 120); // 2 Minuten Timeout
        conf.put("topology.max.spout.pending", 5); // Max 5 URLs gleichzeitig
        conf.put("topology.acker.executors", 1);
        conf.put("topology.workers", 1);
        conf.put("topology.debug", false); // Auf true setzen für detailliertes Logging

        // === URL-FILTER KONFIGURATION ===
        // Verwende basic-urlfilter.txt falls vorhanden
        File filterFile = new File("src/main/resources/basic-urlfilter.txt");
        if (filterFile.exists()) {
            conf.put("urlfilter.basic.file", "basic-urlfilter.txt");
            System.out.println("✓ URL-Filter konfiguriert: basic-urlfilter.txt");
        } else {
            System.out.println("⚠ Keine URL-Filter-Datei gefunden - verwende Standard-Filter");
        }

        // Benutzerdefinierte Konfiguration laden
        loadCustomConfig(conf);

        // === KONFIGURATION AUSGEBEN ===
        System.out.println("=== Simplified Crawler Configuration ===");
        System.out.println("Job ID: " + jobId);
        System.out.println("Seed URLs: " + String.join(", ", seedUrls));
        System.out.println("Max Depth: " + maxDepth);
        System.out.println("Output Directory: " + outputDir);
        System.out.println("Parser emits outlinks: " + conf.get("parser.emitOutlinks"));
        System.out.println("Max URLs per page: " + conf.get("parser.emitOutlinks.max.per.page"));
        System.out.println("Fetcher delay: " + conf.get("fetcher.server.delay") + " seconds");
        System.out.println("Max spout pending: " + conf.get("topology.max.spout.pending"));
        System.out.println("=======================================");

        // CrawlTopology erstellen und starten
        CrawlTopology topology = new CrawlTopology(seedUrls);

        LocalCluster cluster = null;
        try {
            cluster = new LocalCluster();
            cluster.submitTopology(jobId, conf, topology.createTopology().createTopology());

            System.out.println("✓ Topology erfolgreich gestartet!");
            System.out.println("Warte auf Abschluss...");

            // Dynamische Wartezeit basierend auf der Tiefe
            int waitTimeSeconds = Math.max(120, maxDepth * 60); // Min 2 Min, dann 1 Min pro Tiefe
            System.out.println("Wartezeit: " + waitTimeSeconds + " Sekunden");

            // Warten mit Status-Updates
            int checkInterval = 15; // Alle 15 Sekunden
            int totalWaited = 0;

            while (totalWaited < waitTimeSeconds) {
                Thread.sleep(checkInterval * 1000);
                totalWaited += checkInterval;

                if (totalWaited % 30 == 0) { // Alle 30 Sekunden Status
                    System.out.println("⏱ Crawler läuft seit " + totalWaited + " Sekunden...");

                    // Prüfe Output-Verzeichnis
                    checkOutputDirectory(outputDir);
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Fehler während des Crawlings: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            // Sicheres Herunterfahren
            if (cluster != null) {
                try {
                    System.out.println("🛑 Beende Topology...");
                    cluster.killTopology(jobId);
                    Thread.sleep(5000); // Kurz warten
                    cluster.close();
                    System.out.println("✓ Topology erfolgreich beendet");
                } catch (Exception e) {
                    System.err.println("⚠ Fehler beim Beenden: " + e.getMessage());
                }
            }
        }

        // Finale Statistiken
        System.out.println("=== Crawling Abgeschlossen ===");
        checkOutputDirectory(outputDir);
        System.out.println("Job ID: " + jobId);
        System.out.println("============================");
    }

    /**
     * Prüft das Output-Verzeichnis und zeigt Statistiken
     */
    private static void checkOutputDirectory(String outputDir) {
        try {
            File domainsDir = new File(outputDir + "/domains");
            if (domainsDir.exists() && domainsDir.isDirectory()) {
                File[] domains = domainsDir.listFiles();
                if (domains != null && domains.length > 0) {
                    int totalFiles = 0;
                    System.out.println("📁 Gecrawlte Domains:");
                    for (File domain : domains) {
                        if (domain.isDirectory()) {
                            File[] files = domain.listFiles((dir, name) -> name.endsWith(".json"));
                            if (files != null) {
                                System.out.println("  📄 " + domain.getName() + ": " + files.length + " Dateien");
                                totalFiles += files.length;
                            }
                        }
                    }
                    System.out.println("📊 Gesamt: " + totalFiles + " JSON-Dateien erstellt");
                } else {
                    System.out.println("⚠ Keine Domains im Output-Verzeichnis gefunden");
                }
            } else {
                System.out.println("⚠ Output-Verzeichnis nicht gefunden: " + outputDir);
            }

            // Index-Datei prüfen
            File indexFile = new File(outputDir + "/crawl_index.json");
            if (indexFile.exists()) {
                System.out.println("✓ Index-Datei erstellt: " + indexFile.length() + " bytes");
            }

        } catch (Exception e) {
            System.err.println("Fehler beim Prüfen des Output-Verzeichnisses: " + e.getMessage());
        }
    }

    /**
     * Lädt zusätzliche Konfigurationen aus einer Properties-Datei
     */
    private static void loadCustomConfig(Config conf) {
        try {
            InputStream is = TopologyRunner.class.getClassLoader().getResourceAsStream("crawler-config.properties");

            if (is == null) {
                File file = new File("crawler-config.properties");
                if (file.exists()) {
                    is = new FileInputStream(file);
                }
            }

            if (is != null) {
                Properties props = new Properties();
                props.load(is);

                int loadedProps = 0;
                for (String key : props.stringPropertyNames()) {
                    String value = props.getProperty(key);

                    // Smart Type Conversion
                    if (value.matches("\\d+")) {
                        conf.put(key, Integer.parseInt(value));
                    } else if (value.matches("\\d+\\.\\d+")) {
                        conf.put(key, Double.parseDouble(value));
                    } else if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                        conf.put(key, Boolean.parseBoolean(value));
                    } else {
                        conf.put(key, value);
                    }
                    loadedProps++;
                }

                is.close();
                System.out.println("✓ " + loadedProps + " zusätzliche Konfigurationen geladen");
            }
        } catch (Exception e) {
            System.err.println("⚠ Fehler beim Laden der Konfiguration: " + e.getMessage());
        }
    }

    /**
     * Test-Methode für lokale Entwicklung
     */
    public static void main(String[] args) throws Exception {
        String[] testUrls = {"https://www.hs-heilbronn.de/de"};
        int testDepth = 2;
        String testOutputDir = "./test-crawl-output";
        String testJobId = "test-job-" + System.currentTimeMillis();

        System.out.println("Starte Test-Crawl...");
        runTopology(testUrls, testDepth, testOutputDir, testJobId);
        System.out.println("Test-Crawl abgeschlossen!");
    }
}