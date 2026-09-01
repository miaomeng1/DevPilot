package com.devpilot.server.nginx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.nginx.entity.NginxHostSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NginxHostSnapshotMapper extends BaseMapper<NginxHostSnapshotEntity> {
}
