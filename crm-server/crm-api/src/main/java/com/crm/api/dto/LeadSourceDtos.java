package com.crm.api.dto;

/**
 * 来源字典接口的请求/响应体（BE-API-050~052）。
 */
public final class LeadSourceDtos {

    private LeadSourceDtos() {
    }

    /**
     * 新建 / 改名入参：{@code {"name": "新来源"}}。
     */
    public record NameRequest(String name) {
    }

    /**
     * 新建出参：{@code {id, name, sort}}。
     */
    public record CreatedSource(Long id, String name, Integer sort) {
    }

    /**
     * 改名出参：{@code {id, name}}。
     */
    public record RenamedSource(Long id, String name) {
    }
}
