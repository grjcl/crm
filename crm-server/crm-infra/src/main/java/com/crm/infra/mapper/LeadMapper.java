package com.crm.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crm.domain.entity.Lead;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LeadMapper extends BaseMapper<Lead> {
}
