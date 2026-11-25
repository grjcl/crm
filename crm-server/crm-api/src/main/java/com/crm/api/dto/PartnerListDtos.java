package com.crm.api.dto;

import java.time.LocalDate;
import java.util.List;

public final class PartnerListDtos {
    private PartnerListDtos() {}

    public record Owner(long id, String name) {}
    public record Plan(LocalDate date, String note, long overdueDays, long daysFromToday) {}
    public record Item(long id, String name, String shortName, String level, String status,
                       String city, String type, String industry, Owner owner, List<String> tags,
                       LocalDate lastFollowAt, Plan plan) {}
}
