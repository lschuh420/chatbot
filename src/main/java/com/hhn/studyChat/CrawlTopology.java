package com.hhn.studyChat;

import com.digitalpebble.stormcrawler.ConfigurableTopology;
import com.digitalpebble.stormcrawler.bolt.*;
import com.digitalpebble.stormcrawler.spout.MemorySpout;
import com.digitalpebble.stormcrawler.tika.ParserBolt;
import com.digitalpebble.stormcrawler.tika.RedirectionBolt;
import com.hhn.studyChat.util.bolt.HHNStructuredDataBolt;
import com.hhn.studyChat.util.bolt.RAGJSONFileWriterBolt;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.Fields;

import java.util.Properties;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.File;

/**
 * Topologie für Web-Crawling
 */
public class CrawlTopology extends ConfigurableTopology {

	private final String[] seedUrls;

	// Standardkonstruktor für die CLI-Ausführung
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
		// Die von der Elternklasse geerbte Konfiguration verwenden

		// Konfiguration aus der Eigenschaftsdatei laden
		loadCustomConfiguration();

		// Topologie erstellen und einreichen
		return submit("crawl", conf, createTopology());
	}

	/**
	 * Lädt benutzerdefinierte Konfigurationen aus einer Properties-Datei
	 */
	private void loadCustomConfiguration() {
		try {
			// Zuerst versuchen, die Datei im Ressourcenverzeichnis zu finden
			InputStream is = getClass().getClassLoader().getResourceAsStream("crawler-config.properties");

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
				System.out.println("Crawler-Konfiguration erfolgreich geladen.");
			} else {
				System.err.println("WARNUNG: crawler-config.properties nicht gefunden. Verwende Standardkonfiguration.");
				// Mindestens den HTTP-Agent setzen, um den Fehler zu vermeiden
				conf.put("http.agent.name", "StudyChat-Bot/1.0");
			}
		} catch (Exception e) {
			System.err.println("Fehler beim Laden der Crawler-Konfiguration: " + e.getMessage());
			e.printStackTrace();
			// Mindestens den HTTP-Agent setzen, um den Fehler zu vermeiden
			conf.put("http.agent.name", "StudyChat-Bot/1.0");
		}
	}

	public TopologyBuilder createTopology() {
		TopologyBuilder builder = new TopologyBuilder();

		// Verwende die seedUrls-Instanzvariable
		builder.setSpout("spout", new MemorySpout(seedUrls));

		builder.setBolt("partitioner", new URLPartitionerBolt()).shuffleGrouping("spout");

		builder.setBolt("fetch", new FetcherBolt()).fieldsGrouping("partitioner", new Fields("key"));

		builder.setBolt("sitemap", new SiteMapParserBolt()).localOrShuffleGrouping("fetch");

		builder.setBolt("feeds", new FeedParserBolt()).localOrShuffleGrouping("sitemap");

		builder.setBolt("parse", new JSoupParserBolt()).localOrShuffleGrouping("feeds");

		builder.setBolt("shunt", new RedirectionBolt()).localOrShuffleGrouping("parse");

		builder.setBolt("tika", new ParserBolt()).localOrShuffleGrouping("shunt", "tika");

		builder.setBolt("hhnstructured", new HHNStructuredDataBolt()).localOrShuffleGrouping("parse");

		builder.setBolt("ragjson", new RAGJSONFileWriterBolt("./collected-content")).localOrShuffleGrouping("hhnstructured");

		// TopologyBuilder.createTopology() gibt bereits einen StormTopology zurück
		return builder;
	}
}