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
 * Stabile Topologie für Web-Crawling
 * Verwendet Standard StormCrawler Komponenten für maximale Kompatibilität
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

		System.out.println("=== Building Crawl Topology ===");

		// 1. SPOUT: Startet mit Seed-URLs
		builder.setSpout("spout", new MemorySpout(seedUrls), 1);
		System.out.println("✓ Spout configured with " + seedUrls.length + " seed URLs");

		// 2. URL PARTITIONER: Verteilt URLs nach Host
		builder.setBolt("partitioner", new URLPartitionerBolt(), 1)
				.shuffleGrouping("spout")
				.shuffleGrouping("urlextractor"); // Empfängt neue URLs direkt vom URL-Extractor
		System.out.println("✓ URL Partitioner configured");

		// 3. FETCHER: Lädt Webseiten herunter
		builder.setBolt("fetch", new FetcherBolt(), 1)
				.fieldsGrouping("partitioner", new Fields("key"));
		System.out.println("✓ Fetcher configured");

		// 4. SITEMAP PARSER: Verarbeitet Sitemaps
		builder.setBolt("sitemap", new SiteMapParserBolt(), 1)
				.localOrShuffleGrouping("fetch");

		// 5. FEED PARSER: Verarbeitet RSS/Atom Feeds
		builder.setBolt("feeds", new FeedParserBolt(), 1)
				.localOrShuffleGrouping("sitemap");

		// 6. HTML PARSER: Extrahiert Text und Links
		// WICHTIG: parser.emitOutlinks muss auf true sein für Rekursion!
		builder.setBolt("parse", new JSoupParserBolt(), 1)
				.localOrShuffleGrouping("feeds");
		System.out.println("✓ Parser chain configured");

		// 7. REDIRECTION HANDLER: Behandelt Weiterleitungen
		builder.setBolt("shunt", new RedirectionBolt(), 1)
				.localOrShuffleGrouping("parse");

		// 8. TIKA PARSER: Verarbeitet Nicht-HTML-Dokumente
		builder.setBolt("tika", new ParserBolt(), 1)
				.localOrShuffleGrouping("shunt", "tika");

		// === REKURSIVE URL-VERARBEITUNG ===

		// 9. URL EXTRACTOR mit DEPTH CONTROL: Kombinierter Bolt
		// Extrahiert URLs UND kontrolliert Tiefe UND filtert URLs in einem Schritt
		builder.setBolt("urlextractor", new URLExtractorBolt(), 1)
				.localOrShuffleGrouping("parse")
				.localOrShuffleGrouping("tika");
		System.out.println("✓ URL Extractor with integrated Depth Control and Filtering configured");

		// === DATENEXTRAKTION ===

		// 11. HHN STRUCTURED DATA: Extrahiert strukturierte Daten
		builder.setBolt("hhnstructured", new HHNStructuredDataBolt(), 1)
				.localOrShuffleGrouping("parse")
				.localOrShuffleGrouping("tika");
		System.out.println("✓ HHN Structured Data Extractor configured");

		// 12. JSON WRITER: Schreibt Ergebnisse in JSON-Dateien
		builder.setBolt("ragjson", new RAGJSONFileWriterBolt("./collected-content"), 1)
				.localOrShuffleGrouping("hhnstructured");
		System.out.println("✓ JSON Writer configured");

		System.out.println("=== Topology Complete ===");
		System.out.println("Data Flow:");
		System.out.println("  Spout → Partitioner → Fetch → Parse → URLExtractor");
		System.out.println("            ↑                             ↓");
		System.out.println("            └─── New URLs (filtered) ←────┘");
		System.out.println("  Parse → HHNStructured → JSONWriter");
		System.out.println("========================================");

		return builder;
	}
}