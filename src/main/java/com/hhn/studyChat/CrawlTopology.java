package com.hhn.studyChat;

import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.bolt.*;
import com.digitalpebble.stormcrawler.spout.MemorySpout;
import com.digitalpebble.stormcrawler.tika.ParserBolt;
import com.digitalpebble.stormcrawler.tika.RedirectionBolt;
import com.hhn.studyChat.util.bolt.HHNStructuredDataBolt;
import com.hhn.studyChat.util.bolt.RAGJSONFileWriterBolt;
import com.hhn.studyChat.util.bolt.DepthControlBolt;
import com.hhn.studyChat.util.bolt.URLExtractorBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.Properties;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.File;

/**
 * Erweiterte Topologie für Web-Crawling mit Sitemap-Unterstützung
 * Kann zwischen normalem Crawling und Sitemap-Crawling umschalten
 */
public class CrawlTopology extends ConfigurableTopology {

	private final String[] seedUrls;

	public CrawlTopology() {
		this(new String[]{"https://www.hs-heilbronn.de/de"});
	}

	public CrawlTopology(String[] seedUrls) {
		this.seedUrls = seedUrls;
	}

	public static void main(String[] args) throws Exception {
		ConfigurableTopology.start(new CrawlTopology(), args);
	}

	@Override
	protected int run(String[] args) {
		loadCustomConfiguration();
		return submit("crawl", conf, createTopology());
	}

	private void loadCustomConfiguration() {
		try {
			InputStream is = getClass().getClassLoader().getResourceAsStream("crawler-config.properties");

			if (is == null) {
				File file = new File("crawler-config.properties");
				if (file.exists()) {
					is = new FileInputStream(file);
				}
			}

			if (is != null) {
				Properties props = new Properties();
				props.load(is);

				for (String key : props.stringPropertyNames()) {
					conf.put(key, props.getProperty(key));
				}

				is.close();
				System.out.println("Crawler-Konfiguration erfolgreich geladen.");
			} else {
				System.err.println("WARNUNG: crawler-config.properties nicht gefunden. Verwende Standardkonfiguration.");
				conf.put("http.agent.name", "StudyChat-Bot/1.0");
			}
		} catch (Exception e) {
			System.err.println("Fehler beim Laden der Crawler-Konfiguration: " + e.getMessage());
			e.printStackTrace();
			conf.put("http.agent.name", "StudyChat-Bot/1.0");
		}
	}

	public TopologyBuilder createTopology() {
		TopologyBuilder builder = new TopologyBuilder();

		// NEU: Prüfe, ob Sitemap-Crawling aktiviert ist
		boolean sitemapCrawlEnabled = false;
		try {
			Object sitemapEnabled = conf.get("sitemap.crawl.enabled");
			sitemapCrawlEnabled = sitemapEnabled != null && Boolean.parseBoolean(sitemapEnabled.toString());
		} catch (Exception e) {
			System.err.println("Fehler beim Lesen der Sitemap-Konfiguration: " + e.getMessage());
		}

		System.out.println("=== Building Crawl Topology ===");
		System.out.println("Sitemap Crawling: " + (sitemapCrawlEnabled ? "ENABLED" : "DISABLED"));

		// 1. SPOUT: Startet mit Seed-URLs
		builder.setSpout("spout", new MemorySpout(seedUrls), 1);
		System.out.println("✓ Spout configured with " + seedUrls.length + " seed URLs");

		// 2. URL PARTITIONER: Verteilt URLs nach Host
		if (sitemapCrawlEnabled) {
			// Bei Sitemap-Crawling: Nur vom Spout empfangen, keine rekursiven URLs
			builder.setBolt("partitioner", new URLPartitionerBolt(), 1)
					.shuffleGrouping("spout");
			System.out.println("✓ URL Partitioner configured (Sitemap mode - no recursive URLs)");
		} else {
			// Normaler Modus: Rekursive URLs vom URL-Extractor empfangen
			builder.setBolt("partitioner", new URLPartitionerBolt(), 1)
					.shuffleGrouping("spout")
					.shuffleGrouping("urlextractor");
			System.out.println("✓ URL Partitioner configured (Normal mode - recursive URLs enabled)");
		}

		// 3. FETCHER: Lädt Webseiten herunter
		builder.setBolt("fetch", new FetcherBolt(), 1)
				.fieldsGrouping("partitioner", new Fields("key"));
		System.out.println("✓ Fetcher configured");

		// 4. SITEMAP PARSER: Verarbeitet Sitemaps
		// WICHTIG: Bei Sitemap-Crawling wird dieser Bolt zum Hauptpunkt für URL-Extraktion
		builder.setBolt("sitemap", new SiteMapParserBolt(), 1)
				.localOrShuffleGrouping("fetch");
		System.out.println("✓ Sitemap Parser configured");

		// 5. FEED PARSER: Verarbeitet RSS/Atom Feeds
		builder.setBolt("feeds", new FeedParserBolt(), 1)
				.localOrShuffleGrouping("sitemap");

		// 6. HTML PARSER: Extrahiert Text und Links
		builder.setBolt("parse", new JSoupParserBolt(), 1)
				.localOrShuffleGrouping("feeds");
		System.out.println("✓ Parser chain configured");

		// 7. REDIRECTION HANDLER: Behandelt Weiterleitungen
		builder.setBolt("shunt", new RedirectionBolt(), 1)
				.localOrShuffleGrouping("parse");

		// 8. TIKA PARSER: Verarbeitet Nicht-HTML-Dokumente
		builder.setBolt("tika", new ParserBolt(), 1)
				.localOrShuffleGrouping("shunt", "tika");

		// === REKURSIVE URL-VERARBEITUNG (nur bei normalem Crawling) ===
		if (!sitemapCrawlEnabled) {
			// 9. URL EXTRACTOR mit DEPTH CONTROL: Kombinierter Bolt
			// Extrahiert URLs UND kontrolliert Tiefe UND filtert URLs in einem Schritt
			builder.setBolt("urlextractor", new URLExtractorBolt(), 1)
					.localOrShuffleGrouping("parse")
					.localOrShuffleGrouping("tika");
			System.out.println("✓ URL Extractor with integrated Depth Control and Filtering configured (Normal mode)");
		} else {
			System.out.println("✓ URL Extractor DISABLED (Sitemap mode - URLs come from sitemap parser)");
		}

		// === DATENEXTRAKTION ===

		// 10. HHN STRUCTURED DATA: Extrahiert strukturierte Daten
		builder.setBolt("hhnstructured", new HHNStructuredDataBolt(), 1)
				.localOrShuffleGrouping("parse")
				.localOrShuffleGrouping("tika");
		System.out.println("✓ HHN Structured Data Extractor configured");

		// 11. JSON WRITER: Schreibt Ergebnisse in JSON-Dateien
		builder.setBolt("ragjson", new RAGJSONFileWriterBolt("./collected-content"), 1)
				.localOrShuffleGrouping("hhnstructured");
		System.out.println("✓ JSON Writer configured");

		System.out.println("=== Topology Complete ===");
		if (sitemapCrawlEnabled) {
			System.out.println("SITEMAP MODE Data Flow:");
			System.out.println("  Spout → Partitioner → Fetch → SitemapParser");
			System.out.println("            ↑                        ↓");
			System.out.println("            └─── New Sitemap URLs ←──┘");
			System.out.println("  SitemapParser → Parse → HHNStructured → JSONWriter");
		} else {
			System.out.println("NORMAL MODE Data Flow:");
			System.out.println("  Spout → Partitioner → Fetch → Parse → URLExtractor");
			System.out.println("            ↑                             ↓");
			System.out.println("            └─── New URLs (filtered) ←────┘");
			System.out.println("  Parse → HHNStructured → JSONWriter");
		}
		System.out.println("========================================");

		return builder;
	}
}