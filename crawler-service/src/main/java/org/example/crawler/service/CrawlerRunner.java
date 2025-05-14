package org.example.crawler.service;

import org.example.crawler.topology.CrawlTopology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CrawlerRunner {
    @Value("${storm.topology.name}") private String topologyName;

    public void startTopology() throws Exception {
        CrawlTopology topology = new CrawlTopology();
        topology.run(new String[]{topologyName});
    }
}