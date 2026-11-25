package com.crm.api.dto;

import java.util.List;

public final class PartnerCreateDtos {
    private PartnerCreateDtos() { }
    public record Request(String name, String shortName, String creditCode, String legalPerson,
            String regCapital, String foundDate, String industry, String city, String address,
            String website, String brief, String type, List<String> productLines, String level,
            String status, Long ownerId, List<Long> collaboratorIds, String settlementContact,
            List<Object> tags, Invoice invoice, Supply supply) { }
    public record Invoice(String title, String taxNo, String bank, String account) { }
    public record Supply(List<String> supplierType, Integer cities, Integer hotels, String roomType,
            String peakGuarantee, List<String> coopMode, String coopStart, String settleType,
            Integer creditDays, String settleContact, String accessStatus, String rejectReason) { }
    public record Result(Long id, String name, String status) { }
}
