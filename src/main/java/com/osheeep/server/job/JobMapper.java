package com.osheeep.server.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.osheeep.server.job.entity.JobEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobMapper extends BaseMapper<JobEntity> {
}
