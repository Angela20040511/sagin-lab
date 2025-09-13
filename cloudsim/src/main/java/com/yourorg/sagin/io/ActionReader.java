package com.yourorg.sagin.io;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ActionReader {
    private final Path bridgeDir;
    private final long timeoutMillis;
    private final ObjectMapper om = new ObjectMapper();

    public ActionReader(Path bridgeDir, double timeoutSimSeconds){
        this.bridgeDir = bridgeDir;
        this.timeoutMillis = (long)(timeoutSimSeconds * 1000);
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> read(long tick){
        String fileName = String.format("action_%06d.json", tick);
        Path fin = bridgeDir.resolve(fileName);
        long start = System.currentTimeMillis();
        while(System.currentTimeMillis() - start < timeoutMillis){
            if(Files.exists(fin)){
                try { return om.readValue(fin.toFile(), Map.class); }
                catch (IOException ignored) {}
            }
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        return (Map)Collections.singletonMap("actions", Collections.<Map<String,Object>>emptyList());
    }
}
