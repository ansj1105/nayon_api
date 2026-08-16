package com.nayon.api.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nayon.api.economy.EconomyBootstrapCommand;
import com.nayon.api.economy.EconomyBootstrapConflictException;
import com.nayon.api.economy.EconomyBootstrapRecord;
import com.nayon.api.economy.EconomyBootstrapResult;
import com.nayon.api.economy.EconomyRepository;
import com.nayon.api.economy.EconomySnapshot;
import com.nayon.api.economy.PlayerEquipment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.health.db.enabled=false")
@AutoConfigureMockMvc
@Import({SaveContractTest.Fakes.class, EconomyContractTest.EconomyFake.class})
class EconomyContractTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000501");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void economyRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/economy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAccountStartsEmpty() throws Exception {
        mvc.perform(get("/api/v1/economy").with(player("empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootstrapped").value(false))
                .andExpect(jsonPath("$.currencies").isEmpty())
                .andExpect(jsonPath("$.equipment").isArray());
    }

    @Test
    void bootstrapCreatesOnceAndIdenticalRetryReturnsOk() throws Exception {
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("bootstrap"))
                        .header("Idempotency-Key", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(100)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bootstrapped").value(true))
                .andExpect(jsonPath("$.currencies.DIAMOND").value(100))
                .andExpect(jsonPath("$.equipment.length()").value(1));

        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("bootstrap"))
                        .header("Idempotency-Key", REQUEST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(100)))
                .andExpect(status().isOk());
    }

    @Test
    void changedBootstrapReturnsConflict() throws Exception {
        UUID firstRequestId = UUID.randomUUID();
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("conflict"))
                        .header("Idempotency-Key", firstRequestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(100)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("conflict"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(101)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ECONOMY_ALREADY_BOOTSTRAPPED"));
    }

    @Test
    void invalidOrUnknownInputIsRejected() throws Exception {
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("invalid"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currencies": {"DIAMOND": -1},
                                  "items": {},
                                  "equipment": [],
                                  "clientCanMint": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void bootstrapRequiresIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("missing-key"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(100)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullEquipmentEntryIsRejectedAsContractError() throws Exception {
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("null-equipment"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currencies": {},
                                  "items": {},
                                  "equipment": [null]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void malformedIdempotencyKeyIsRejectedAsContractError() throws Exception {
        mvc.perform(post("/api/v1/economy/bootstrap")
                        .with(player("bad-key"))
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private JwtRequestPostProcessor player(String subject) {
        return jwt().jwt(token -> token
                .subject(subject)
                .claim("nayon:provider", "GOOGLE")
                .claim("token_use", "access")
                .claim("client_id", "nayon-unity-client"));
    }

    private String validRequest(long diamonds) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "currencies", Map.of("DIAMOND", diamonds),
                "items", Map.of("SILVER_KEY", 2),
                "equipment", List.of(Map.of(
                        "equipmentCode", "Weapon_01",
                        "grade", "COMMON",
                        "quantity", 1))));
    }

    @TestConfiguration
    static class EconomyFake {

        @Bean
        @Primary
        EconomyRepository economyRepository() {
            return new EconomyRepository() {
                private final Map<UUID, EconomySnapshot> snapshots = new HashMap<>();
                private final Map<UUID, EconomyBootstrapRecord> requests = new HashMap<>();

                @Override
                public EconomySnapshot findSnapshot(UUID accountId) {
                    return snapshots.getOrDefault(accountId, EconomySnapshot.empty(accountId));
                }

                @Override
                public Optional<EconomyBootstrapRecord> findBootstrapByAccountId(UUID accountId) {
                    return requests.values().stream()
                            .filter(record -> record.accountId().equals(accountId))
                            .findFirst();
                }

                @Override
                public Optional<EconomyBootstrapRecord> findBootstrapByRequestId(UUID requestId) {
                    return Optional.ofNullable(requests.get(requestId));
                }

                @Override
                public EconomyBootstrapResult createBootstrap(
                        UUID accountId,
                        UUID requestId,
                        String requestHash,
                        EconomyBootstrapCommand command) {
                    Optional<EconomyBootstrapRecord> existing =
                            findBootstrapByAccountId(accountId);
                    if (existing.isPresent()) {
                        EconomyBootstrapRecord previous = existing.get();
                        if (previous.requestId().equals(requestId)
                                && previous.requestHash().equals(requestHash)) {
                            return new EconomyBootstrapResult(previous.snapshot(), true);
                        }
                        throw new EconomyBootstrapConflictException();
                    }
                    List<PlayerEquipment> equipment = new ArrayList<>();
                    command.equipment().forEach(item -> equipment.add(new PlayerEquipment(
                            UUID.randomUUID(), item.equipmentCode(), item.grade(), 1, false)));
                    EconomySnapshot snapshot = new EconomySnapshot(
                            accountId, command.currencies(), command.items(), equipment, true);
                    snapshots.put(accountId, snapshot);
                    requests.put(requestId, new EconomyBootstrapRecord(
                            accountId, requestId, requestHash, snapshot, Instant.now()));
                    return new EconomyBootstrapResult(snapshot, false);
                }

                @Override
                public EconomySnapshot creditCurrency(
                        UUID accountId, UUID requestId, String currencyCode, long amount,
                        String reasonCode, String referenceType, UUID referenceId) {
                    EconomySnapshot current = findSnapshot(accountId);
                    Map<String, Long> currencies = new HashMap<>(current.currencies());
                    currencies.merge(currencyCode, amount, Long::sum);
                    EconomySnapshot updated = new EconomySnapshot(
                            accountId, currencies, current.items(), current.equipment(),
                            current.bootstrapped());
                    snapshots.put(accountId, updated);
                    return updated;
                }
            };
        }
    }
}
