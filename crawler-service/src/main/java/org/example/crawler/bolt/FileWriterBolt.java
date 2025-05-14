package org.example.crawler.bolt;

import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;

import java.nio.file.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FileWriterBolt extends BaseRichBolt {

    private OutputCollector collector;
    private String outputDir;

    public FileWriterBolt(String outputDir) {
        this.outputDir = outputDir;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
        // Stelle sicher, dass das Verzeichnis existiert:
        try { Files.createDirectories(Paths.get(outputDir)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override
    public void execute(Tuple tuple) {
        String url  = tuple.getStringByField("url");
        String text = tuple.getStringByField("text"); // Das JSoupParserBolt–Feld
        try {
            // aus der URL einen Dateinamen bauen
            String fname = URLEncoder.encode(url, "UTF-8") + ".txt";
            Path   path  = Paths.get(outputDir, fname);
            Files.write(path, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            // Fehlerprotokollierung – tuple nicht acken, damit man es nochmal versuchen kann
            collector.reportError(e);
            return;
        }
        collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // kein Output
    }
}
