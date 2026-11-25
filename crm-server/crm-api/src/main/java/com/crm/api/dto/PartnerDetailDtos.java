package com.crm.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class PartnerDetailDtos {
    private PartnerDetailDtos() {}

    public record User(long id, String name) {}
    public record Invoice(String title, String taxNo, String bank, String accountMasked) {}
    public record Supply(List<String> supplierType, Integer cities, Integer hotels, String roomType,
                         String peakGuarantee, List<String> coopMode, LocalDate coopStart, String settleType,
                         Integer creditDays, String settleContact, String accessStatus, String rejectReason) {}
    public record Contact(long id, String name, String title, String phoneMasked, String roleTag, boolean isPrimary) {}
    public record Attachment(long id, String fileName, String mime, long size, long uploadedBy, OffsetDateTime createdAt) {}
    public record Follow(long id, String type, String content, User createdBy, OffsetDateTime createdAt,
                         boolean deleted, String deleteReason) {}
    public record Plan(LocalDate date, String note, User owner, int version) {}
    public record Metrics(long openDeals, long monthFollows, long contacts, LocalDate nextPlanDate) {}
    public record Detail(long id, String name, String shortName, String creditCode, String legalPerson,
                         String regCapital, LocalDate foundDate, String industry, String city, String address,
                         String website, String brief, String type, List<String> productLines, String level,
                         String status, String settlementContact, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                         User owner, List<User> collaborators, List<String> tags, Invoice invoice, Supply supply,
                         List<Contact> contacts, List<Attachment> attachments, List<Follow> recentFollows,
                         Plan plan, Metrics metrics) {}
}
