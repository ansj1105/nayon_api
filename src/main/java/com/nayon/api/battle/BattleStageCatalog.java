package com.nayon.api.battle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BattleStageCatalog {
    private final Configuration configuration;
    private final Map<String, BattleStageLimit> stages;

    public BattleStageCatalog(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource(
                "battle/stage-limits-v1.json").getInputStream()) {
            configuration = objectMapper.readValue(input, Configuration.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load battle stage limits", exception);
        }
        stages = configuration.stages().stream().collect(Collectors.toUnmodifiableMap(
                BattleStageLimit::stageCode, Function.identity()));
    }

    public BattleStageLimit require(String stageCode) {
        BattleStageLimit stage = stages.get(stageCode);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage code");
        }
        return stage;
    }

    public Configuration configuration() {
        return configuration;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Configuration(
            String version,
            int sessionTtlSeconds,
            int elapsedGraceSeconds,
            int clockDriftSeconds,
            int minimumClearServerSeconds,
            int killTolerancePercent,
            List<BattleStageLimit> stages) {
    }
}
