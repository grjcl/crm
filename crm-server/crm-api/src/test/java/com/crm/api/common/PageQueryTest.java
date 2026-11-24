package com.crm.api.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageQueryTest {

    @Test
    void defaultsToFirstPageAndTwentyItems() {
        PageQuery query = PageQuery.of(null, null);
        assertEquals(1, query.page());
        assertEquals(20, query.size());
        assertEquals(0L, query.offset());
    }

    @Test
    void rejectsPageBelowOne() {
        BizException exception = assertThrows(BizException.class, () -> PageQuery.of(0, 20));
        assertEquals(1001, exception.getCode());
    }

    @Test
    void rejectsSizeAboveHundred() {
        BizException exception = assertThrows(BizException.class, () -> PageQuery.of(1, 101));
        assertEquals(1001, exception.getCode());
    }

    @Test
    void computesOffsetForLaterPages() {
        assertEquals(40L, PageQuery.of(3, 20).offset());
    }

    @Test
    void emptyResultKeepsEmptyListInsteadOfNull() {
        PageResult<String> result = PageResult.empty(PageQuery.of(1, 20));
        assertEquals(0L, result.total());
        assertEquals(0, result.list().size());
        assertEquals(List.of(), result.list());
    }
}
