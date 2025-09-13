package com.yourorg.sagin.io;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

public class StateWriter {
    private final Path bridgeDir;
    private final ObjectMapper om = new ObjectMapper();

    public StateWriter(Path bridgeDir){ this.bridgeDir = bridgeDir; }

    public void write(long tick, Map<String,Object> state){
        try{
            Files.createDirectories(bridgeDir.resolve("tmp"));
            String tmpName = String.format("state_%06d.json.tmp", tick);
            String finName = String.format("state_%06d.json", tick);
            Path tmp = bridgeDir.resolve("tmp").resolve(tmpName);
            Path fin = bridgeDir.resolve(finName);
            om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }catch (IOException e){ throw new RuntimeException(e); }
    }
}
