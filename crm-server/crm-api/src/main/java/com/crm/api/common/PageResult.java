package com.crm.api.common;

import java.util.List;

/**
 * 分页出参：{@code { list, total, page, size }}；空结果固定返回空数组而不是 null。
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    public PageResult {
        list = list == null ? List.of() : List.copyOf(list);
    }

    public static <T> PageResult<T> empty(PageQuery query) {
        return new PageResult<>(List.of(), 0L, query.page(), query.size());
    }

    public static <T> PageResult<T> of(List<T> list, long total, PageQuery query) {
        return new PageResult<>(list, total, query.page(), query.size());
    }
}
