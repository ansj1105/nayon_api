package com.nayon.api.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.nayon.api.time.KstGameTimeCalculator;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;

@Configuration
public class KstJsonTimeConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer kstInstantSerializer() {
        return builder -> builder.serializerByType(
                Instant.class, new JsonSerializer<Instant>() {
                    @Override
                    public void serialize(
                            Instant value,
                            JsonGenerator generator,
                            SerializerProvider serializers) throws IOException {
                        generator.writeString(value.atZone(KstGameTimeCalculator.KST)
                                .toOffsetDateTime().toString());
                    }
                });
    }
}
