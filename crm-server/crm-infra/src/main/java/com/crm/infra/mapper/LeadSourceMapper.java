package com.crm.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crm.domain.entity.LeadSource;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LeadSourceMapper extends BaseMapper<LeadSource> {
}
