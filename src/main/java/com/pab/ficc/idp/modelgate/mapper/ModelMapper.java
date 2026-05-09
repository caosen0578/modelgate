package com.pab.ficc.idp.modelgate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ModelMapper extends BaseMapper<ModelInstance> {
}
