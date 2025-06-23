package com.hhn.studyChat;

import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.digitalpebble.stormcrawler.Constants;
import com.digitalpebble.stormcrawler.bolt.*;
import com.digitalpebble.stormcrawler.spout.MemorySpout;
import com.digitalpebble.stormcrawler.tika.ParserBolt;
import com.digitalpebble.stormcrawler.tika.RedirectionBolt;
import com.hhn.studyChat.util.bolt.HHNStructuredDataBolt;
import com.hhn.studyChat.util.bolt.RAGJSONFileWriterBolt;
import com.hhn.studyChat.util.bolt.URLExtractorBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;
import org.apache.storm.Config;

import java.util.Properties;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.File;

/**
 * Vereinfachte Topologie für Web-Crawling mit Sitemap-Unterstützung
 */
public class CrawlTopology extends ConfigurableTopology {

	private final String[] seedUrls;
	private Config runtimeConfig;

	public CrawlTopology() {
		this(new String[]{"https://www.hs-heilbronn.de/de"});
	}

	public CrawlTopology(String[] seedUrls) {
		this.seedUrls = seedUrls;
	}

	public CrawlTopology(String[] seedUrls, Config runtimeConfig) {
		this.seedUrls = seedUrls;
		this.runtimeConfig = runtimeConfig;
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

		// Verwende Runtime-Config falls verfügbar
		Config configToUse = runtimeConfig != null ? runtimeConfig : conf;

		// Prüfe, ob Sitemap-Crawling aktiviert ist
		boolean sitemapCrawlEnabled = false;
		try {
			Object sitemapEnabled = configToUse.get("sitemap.crawl.enabled");
			if (sitemapEnabled != null) {
				if (sitemapEnabled instanceof Boolean) {
					sitemapCrawlEnabled = (Boolean) sitemapEnabled;
				} else {
					sitemapCrawlEnabled = Boolean.parseBoolean(sitemapEnabled.toString());
				}
			}
		} catch (Exception e) {
			System.err.println("Fehler beim Lesen der Sitemap-Konfiguration: " + e.getMessage());
		}

		System.out.println("=== Building SIMPLIFIED Crawl Topology ===");
		System.out.println("Sitemap Crawling: " + (sitemapCrawlEnabled ? "ENABLED" : "DISABLED"));

		// 1. SPOUT: Startet mit Seed-URLs
		builder.setSpout("spout", new MemorySpout(seedUrls), 1);
		System.out.println("✓ Spout configured with " + seedUrls.length + " seed URLs");

		// 2. URL PARTITIONER: Verteilt URLs nach Host
		if (sitemapCrawlEnabled) {
			// Bei Sitemap-Crawling: Empfange URLs vom Spout UND vom Sitemap-Parser
			builder.setBolt("partitioner", new URLPartitionerBolt(), 1)
					.shuffleGrouping("spout")
					.shuffleGrouping("sitemap", Constants.StatusStreamName); // Direkt vom SiteMapParserBolt
			System.out.println("✓ URL Partitioner configured (Sitemap mode - receives URLs from sitemap parser)");
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

		// 4. SITEMAP PARSER: Verarbeitet Sitemaps UND normale Seiten
		builder.setBolt("sitemap", new SiteMapParserBolt(), 1)
				.localOrShuffleGrouping("fetch");
		System.out.println("✓ Sitemap Parser configured");

		// 5. FEED PARSER: Verarbeitet RSS/Atom Feeds
		builder.setBolt("feeds", new FeedParserBolt(), 1)
				.localOrShuffleGrouping("sitemap");

		// 6. HTML PARSER: Extrahiert Text und Links
		builder.setBolt("parse", new JSoupParserBolt(), 1)
				.localOrShuffleGrouping("feeds")
				.localOrShuffleGrouping("sitemap");
		System.out.println("✓ HTML Parser configured");

		// 7. REDIRECTION HANDLER: Behandelt Weiterleitungen
		builder.setBolt("shunt", new RedirectionBolt(), 1)
				.localOrShuffleGrouping("parse");

		// 8. TIKA PARSER: Verarbeitet Nicht-HTML-Dokumente
		builder.setBolt("tika", new ParserBolt(), 1)
				.localOrShuffleGrouping("shunt", "tika");

		// === REKURSIVE URL-VERARBEITUNG (nur bei normalem Crawling) ===
		if (!sitemapCrawlEnabled) {
			// URL EXTRACTOR mit DEPTH CONTROL (nur im normalen Modus)
			builder.setBolt("urlextractor", new URLExtractorBolt(), 1)
					.localOrShuffleGrouping("parse")
					.localOrShuffleGrouping("tika");
			System.out.println("✓ URL Extractor configured (Normal mode only)");
		} else {
			System.out.println("✓ URL Extractor DISABLED (Sitemap mode - URLs come from sitemap parser)");
		}

		// === DATENEXTRAKTION ===

		// 9. HHN STRUCTURED DATA: Extrahiert strukturierte Daten
		builder.setBolt("hhnstructured", new HHNStructuredDataBolt(), 1)
				.localOrShuffleGrouping("parse")
				.localOrShuffleGrouping("tika");
		System.out.println("✓ HHN Structured Data Extractor configured");

		// 10. JSON WRITER: Schreibt Ergebnisse in JSON-Dateien
		String outputDir = configToUse.get("output.dir") != null ?
				configToUse.get("output.dir").toString() : "./collected-content";
		builder.setBolt("ragjson", new RAGJSONFileWriterBolt(outputDir), 1)
				.localOrShuffleGrouping("hhnstructured");
		System.out.println("✓ JSON Writer configured with output dir: " + outputDir);

		System.out.println("=== SIMPLIFIED Topology Complete ===");
		if (sitemapCrawlEnabled) {
			System.out.println("SITEMAP MODE Data Flow:");
			System.out.println("  Spout → Partitioner → Fetch → SitemapParser");
			System.out.println("            ↑                       ↓");
			System.out.println("            └── New URLs ←──────────┘");
			System.out.println("  SitemapParser → Parse → HHNStructured → JSONWriter");
		} else {
			System.out.println("NORMAL MODE Data Flow:");
			System.out.println("  Spout → Partitioner → Fetch → SitemapParser → Parse → URLExtractor");
			System.out.println("            ↑                                              ↓");
			System.out.println("            └─────── New URLs (filtered) ←─────────────────┘");
			System.out.println("  Parse → HHNStructured → JSONWriter");
		}
		System.out.println("========================================");

		return builder;
	}
}