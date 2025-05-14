package org.example.crawler.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.tuple.Fields;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StructuredDataBolt extends BaseRichBolt {

    private OutputCollector collector;

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector coll) {
        this.collector = coll;
    }

    @Override
    public void execute(Tuple tuple) {
        String url     = tuple.getStringByField("url");
        byte[] content = tuple.getBinaryByField("content");
        String html    = new String(content, StandardCharsets.UTF_8);

        // JSoup-Parsen des HTML
        Document doc = Jsoup.parse(html, url);

        // Beispiel: Events extrahieren
        Elements eventEls = doc.select(".event-item");
        List<String> events = eventEls.stream()
                .map(el -> {
                    String date  = el.selectFirst(".event-date").text();
                    String title = el.selectFirst(".event-title").text();
                    return date + " – " + title;
                })
                .collect(Collectors.toList());

        // Beispiel: News-Teaser extrahieren
        Elements newsEls = doc.select(".news-item");
        List<String> news = newsEls.stream()
                .map(el -> el.selectFirst(".news-title").text())
                .collect(Collectors.toList());

        // Original-Text weitergeben, falls gewünscht
        String fullText = tuple.getStringByField("text");

        // Emit eines neuen, strukturierten Tuples
        collector.emit(tuple, new Values(url, events, news, fullText));
        collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer decl) {
        decl.declare(new Fields("url", "events", "news", "text"));
    }
}
