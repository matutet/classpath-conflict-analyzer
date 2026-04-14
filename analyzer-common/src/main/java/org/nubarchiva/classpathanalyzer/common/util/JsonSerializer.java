package org.nubarchiva.classpathanalyzer.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized JSON serialization/deserialization utility
 * with common configuration for the entire suite.
 */
public final class JsonSerializer {

    private static final ObjectMapper MAPPER = createMapper();

    private JsonSerializer() {
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Returns the shared ObjectMapper (immutable in configuration).
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Serializes an object to JSON and writes it to the specified file.
     */
    public static void writeToFile(Object value, Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        MAPPER.writeValue(outputFile.toFile(), value);
    }

    /**
     * Reads a JSON file and deserializes it to the specified type.
     */
    public static <T> T readFromFile(Path inputFile, Class<T> type) throws IOException {
        return MAPPER.readValue(inputFile.toFile(), type);
    }

    /**
     * Serializes an object to a JSON String.
     */
    public static String toJson(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }
}
