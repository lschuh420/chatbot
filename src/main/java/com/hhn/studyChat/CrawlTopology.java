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

		// Topologie erstellen und einreichen
		return submit("crawl", conf, createTopology());
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