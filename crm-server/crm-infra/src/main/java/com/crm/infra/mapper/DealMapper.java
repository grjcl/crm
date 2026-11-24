package com.crm.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crm.domain.entity.Deal;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DealMapper extends BaseMapper<Deal> {
}
