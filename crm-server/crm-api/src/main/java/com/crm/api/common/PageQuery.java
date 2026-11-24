package com.crm.api.common;

/**
 * 分页入参：{@code ?page=1&size=20}，默认 1/20，上限 100。
 *
 * <p>边界不合法时抛出 {@link ErrorCode#PARAM_INVALID}（1001）。</p>
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (page < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "page 必须大于等于 1");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, "size 必须在 1-" + MAX_SIZE + " 之间");
        }
    }

    /**
     * 从可空的请求参数构造分页条件；缺省 page=1、size=20。
     */
    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(
                page == null ? DEFAULT_PAGE : page,
                size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) (page - 1) * size;
    }
}
