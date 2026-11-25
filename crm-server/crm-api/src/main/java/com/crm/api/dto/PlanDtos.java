package com.crm.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class PlanDtos {
    private PlanDtos() { }

    public record Request(LocalDate date, String note, Integer version) { }
    public record Owner(Long id, String name) { }
    public record Plan(LocalDate date, String note, Owner owner, int version, OffsetDateTime updatedAt) { }
    public record Saved(Long orgId, LocalDate date, String note, int version) { }
}
