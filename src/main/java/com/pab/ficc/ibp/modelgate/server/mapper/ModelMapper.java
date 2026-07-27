package com.pab.ficc.ibp.modelgate.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMapper extends BaseMapper<ModelInstanceEntity> {
}
