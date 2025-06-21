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
        runTopology(seedUrls, maxDepth, outputDir, jobId, false);
    }

    public static void runTopology(String[] seedUrls, int maxDepth, String outputDir, String jobId, boolean sitemapCrawl) throws Exception {
        Config conf = new Config();

        // === WICHTIGE CRAWLER-KONFIGURATION ===
        conf.put(StudyChatConstants.CRAWLER_ID_CONFIG_KEY, jobId);
        conf.put(StudyChatConstants.MAX_DEPTH_CONFIG_KEY, maxDepth);
        conf.put(StudyChatConstants.OUTPUT_DIR_CONFIG_KEY, outputDir);
        conf.put("output.dir", outputDir); // Zusätzlich für die Topologie

        // === SITEMAP-KONFIGURATION ===
        conf.put("sitemap.crawl.enabled", sitemapCrawl);
        if (sitemapCrawl) {
            System.out.println("🗺️ SITEMAP-MODUS AKTIVIERT");

            // Sitemap-spezifische Konfiguration
            conf.put("parser.emitOutlinks", false);
            conf.put("sitemap.discovery", true);
            conf.put("sitemap.strict.mode", false); // Weniger strikt für bessere Kompatibilität
            conf.put("fetcher.max.urls", 1000);
            conf.put("sitemap.maxlinks", 50000); // Erhöht für große Sitemaps

            // StormCrawler-spezifische Sitemap-Konfiguration
            conf.put("sitemap.index.discovery", true);
            conf.put("sitemap.outlinks.filter", false);
            conf.put("parser.extract.outlinks", false);

            // URLs für Sitemap vorbereiten
            StringBuilder sitemapUrls = new StringBuilder();
            for (int i = 0; i < seedUrls.length; i++) {
                String url = seedUrls[i];

                // Automatisch /sitemap.xml anhängen, wenn nicht vorhanden
                if (!url.endsWith("sitemap.xml") && !url.contains("sitemap")) {
                    if (url.endsWith("/")) {
                        url = url + "sitemap.xml";
                    } else {
                        url = url + "/sitemap.xml";
                    }
                    seedUrls[i] = url;
                    System.out.println("📍 Sitemap-URL konstruiert: " + url);
                } else {
                    System.out.println("📍 Sitemap-URL direkt verwendet: " + url);
                }
                sitemapUrls.append(url);
                if (i < seedUrls.length - 1) {
                    sitemapUrls.append(",");
                }
            }
            conf.put("sitemap.urls", sitemapUrls.toString());

        } else {
            System.out.println("🌐 NORMALER CRAWL-MODUS");
            conf.put("parser.emitOutlinks", true);
            conf.put("parser.emitOutlinks.max.per.page", 60);
            conf.put("fetcher.continue.at.depth", true);
            conf.put("metadata.track.depth", true);
        }

        // === HTTP AGENT KONFIGURATION ===
        conf.put("http.agent.name", StudyChatConstants.USER_AGENT_NAME);
        conf.put("http.agent.version", StudyChatConstants.USER_AGENT_VERSION);
        conf.put("http.agent.description", StudyChatConstants.USER_AGENT_DESCRIPTION);
        conf.put("http.agent.url", StudyChatConstants.USER_AGENT_URL);
        conf.put("http.agent.email", StudyChatConstants.USER_AGENT_EMAIL);

        // === PERFORMANCE & STABILITÄT ===
        conf.put("fetcher.server.delay", 2.0);
        conf.put("fetcher.threads.number", 1);
        if (!sitemapCrawl) {
            conf.put("fetcher.max.urls", 50);
        }

        // Storm-Konfiguration
        conf.put("topology.message.timeout.secs", 180); // 3 Minuten für Sitemap-Verarbeitung
        conf.put("topology.max.spout.pending", 10);
        conf.put("topology.acker.executors", 1);
        conf.put("topology.workers", 1);
        conf.put("topology.debug", false); // Debug ausschalten für Performance

        // URL-Filter
        File filterFile = new File("src/main/resources/basic-urlfilter.txt");
        if (filterFile.exists()) {
            conf.put("urlfilter.basic.file", "basic-urlfilter.txt");
            System.out.println("✓ URL-Filter konfiguriert: basic-urlfilter.txt");
        }

        // Custom-Config laden
        loadCustomConfig(conf);

        // === KONFIGURATION AUSGEBEN ===
        System.out.println("=== Crawler Configuration ===");
        System.out.println("Job ID: " + jobId);
        System.out.println("Seed URLs: " + String.join(", ", seedUrls));
        System.out.println("Max Depth: " + maxDepth);
        System.out.println("Output Directory: " + outputDir);
        System.out.println("Sitemap Crawling: " + (sitemapCrawl ? "ENABLED" : "DISABLED"));
        if (sitemapCrawl) {
            System.out.println("Sitemap Discovery: " + conf.get("sitemap.discovery"));
            System.out.println("Max Sitemap Links: " + conf.get("sitemap.maxlinks"));
        } else {
            System.out.println("Parser emits outlinks: " + conf.get("parser.emitOutlinks"));
            System.out.println("Max URLs per page: " + conf.get("parser.emitOutlinks.max.per.page"));
        }
        System.out.println("=======================================");

        // Topologie erstellen und starten
        CrawlTopology topology = new CrawlTopology(seedUrls, conf);

        LocalCluster cluster = null;
        try {
            cluster = new LocalCluster();
            cluster.submitTopology(jobId, conf, topology.createTopology().createTopology());

            System.out.println("✓ Topology erfolgreich gestartet!");
            System.out.println("Warte auf Abschluss...");

            // Wartezeit berechnen
            int waitTimeSeconds;
            if (sitemapCrawl) {
                waitTimeSeconds = Math.max(240, seedUrls.length * 90); // Min 4 Min, dann 1.5 Min pro Sitemap
            } else {
                waitTimeSeconds = Math.max(120, maxDepth * 60);
            }
            System.out.println("Wartezeit: " + waitTimeSeconds + " Sekunden");

            // Warten mit Status-Updates
            int checkInterval = 20;
            int totalWaited = 0;

            while (totalWaited < waitTimeSeconds) {
                Thread.sleep(checkInterval * 1000);
                totalWaited += checkInterval;

                if (totalWaited % 40 == 0) { // Alle 40 Sekunden Status
                    System.out.println("⏱ Crawler läuft seit " + totalWaited + " Sekunden...");
                    checkOutputDirectory(outputDir);
                }
            }

        } catch (Exception e) {
            System.err.println("❌ Fehler während des Crawlings: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            if (cluster != null) {
                try {
                    System.out.println("🛑 Beende Topology...");
                    cluster.killTopology(jobId);
                    Thread.sleep(5000);
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
        System.out.println("Modus: " + (sitemapCrawl ? "Sitemap" : "Normal"));
        System.out.println("============================");
    }

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

            File indexFile = new File(outputDir + "/crawl_index.json");
            if (indexFile.exists()) {
                System.out.println("✓ Index-Datei erstellt: " + indexFile.length() + " bytes");
            }

        } catch (Exception e) {
            System.err.println("Fehler beim Prüfen des Output-Verzeichnisses: " + e.getMessage());
        }
    }

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

    public static void main(String[] args) throws Exception {
        String[] testUrls = {"https://www.hs-heilbronn.de/de"};
        int testDepth = 2;
        String testOutputDir = "./test-crawl-output";
        String testJobId = "test-job-" + System.currentTimeMillis();
        boolean testSitemapCrawl = true;

        System.out.println("Starte Test-Crawl mit Sitemap...");
        runTopology(testUrls, testDepth, testOutputDir, testJobId, testSitemapCrawl);
        System.out.println("Test-Crawl abgeschlossen!");
    }
}