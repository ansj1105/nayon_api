package com.nayon.api.economy;

import java.util.UUID;

public record EconomyCreditResult(UUID ledgerId, EconomySnapshot economy) {
}
