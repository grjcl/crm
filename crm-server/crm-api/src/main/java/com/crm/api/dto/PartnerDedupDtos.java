package com.crm.api.dto;

import java.util.List;

public final class PartnerDedupDtos {
    private PartnerDedupDtos() { }

    public record Request(String name, String creditCode) { }
    public record Match(long id, String name, String owner, String level, double similarity) { }
    public record Result(boolean hit, String type, List<Match> matches) { }
}
