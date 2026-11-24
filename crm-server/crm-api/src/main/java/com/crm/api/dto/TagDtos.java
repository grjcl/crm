package com.crm.api.dto;

/**
 * 标签字典接口的请求/响应体（BE-API-019~022）。
 */
public final class TagDtos {

    private TagDtos() {
    }

    /**
     * 新建 / 改名入参：{@code {"name": "新标签"}}。
     */
    public record NameRequest(String name) {
    }

    /**
     * 列表项：{@code {id, name, sort, enabled, orgCount}}。
     *
     * <p>{@code orgCount} 只统计未软删（{@code deleted_at is null}）的合作商。</p>
     */
    public record TagItem(Long id, String name, Integer sort, Boolean enabled, Long orgCount) {
    }

    /**
     * 新建出参：{@code {id, name, sort}}。
     */
    public record CreatedTag(Long id, String name, Integer sort) {
    }

    /**
     * 改名出参：{@code {id, name}}。
     */
    public record RenamedTag(Long id, String name) {
    }
}
