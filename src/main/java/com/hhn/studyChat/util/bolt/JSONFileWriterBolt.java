package com.hhn.studyChat.util.bolt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Bolt that takes structured fields (url, events, news, text)
 * and writes each tuple as a JSON file into the output directory.
 */
public class JSONFileWriterBolt extends BaseRichBolt {

    private OutputCollector collector;
    private final String outputDir;
    private ObjectMapper mapper;

    public JSONFileWriterBolt(String outputDir) {
        this.outputDir = outputDir;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        this.mapper = new ObjectMapper();
        // Create output directory if it does not exist
        try {
            Files.createDirectories(Paths.get(outputDir));
        } catch (Exception e) {
            throw new RuntimeException("Could not create output directory: " + outputDir, e);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            @SuppressWarnings("unchecked")
            List<String> events = (List<String>) tuple.getValueByField("events");
            @SuppressWarnings("unchecked")
            List<String> news   = (List<String>) tuple.getValueByField("news");
            String text         = tuple.getStringByField("text");

            // Build JSON object
            ObjectNode doc = mapper.createObjectNode();
            doc.put("url", url);
            doc.putPOJO("events", events);
            doc.putPOJO("news", news);
            doc.put("text", text);

            // Create a safe filename from the URL
            String safeName = URLEncoder.encode(url, StandardCharsets.UTF_8.toString());
            if (safeName.length() > 200) {
                // Truncate long names to avoid filesystem issues
                safeName = safeName.substring(0, 200);
            }
            Path path = Paths.get(outputDir, safeName + ".json");

            // Write JSON to file (pretty-printed)
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));

            collector.ack(tuple);
        } catch (Exception e) {
            collector.reportError(e);
            collector.fail(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // This bolt does not emit anything
        declarer.declare(new Fields());
    }
}
