package com.devpilot.server.nginx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.nginx.entity.NginxConfigHistoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NginxConfigHistoryMapper extends BaseMapper<NginxConfigHistoryEntity> {
    @Select("SELECT * FROM nginx_config_history WHERE config_id = #{configId} ORDER BY created_at DESC")
    List<NginxConfigHistoryEntity> selectByConfig(@Param("configId") Long configId);

    @Select("SELECT * FROM nginx_config_history WHERE command_id = #{commandId}")
    NginxConfigHistoryEntity selectByCommand(@Param("commandId") Long commandId);
}
