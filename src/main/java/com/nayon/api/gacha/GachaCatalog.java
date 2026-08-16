package com.nayon.api.gacha;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class GachaCatalog {
    private final String version;
    private final List<Entry> equipment;

    public GachaCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(
                "gacha/equipment-catalog-v1.json").getInputStream()) {
            CatalogFile file = objectMapper.readValue(input, CatalogFile.class);
            this.version = file.version();
            this.equipment = List.copyOf(file.equipment());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load gacha catalog", exception);
        }
        if (equipment.isEmpty()) {
            throw new IllegalStateException("Gacha catalog is empty");
        }
    }

    public String version() {
        return version;
    }

    public List<Entry> candidates(String grade, boolean chroma) {
        return equipment.stream()
                .filter(entry -> entry.grade().equals(grade) && entry.chroma() == chroma)
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record CatalogFile(String version, List<Entry> equipment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Entry(String code, int type, String grade, boolean chroma) {
    }
}
