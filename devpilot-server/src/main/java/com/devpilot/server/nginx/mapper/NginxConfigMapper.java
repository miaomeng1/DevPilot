package com.devpilot.server.nginx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.nginx.entity.NginxConfigEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NginxConfigMapper extends BaseMapper<NginxConfigEntity> {
    @Select("SELECT * FROM nginx_config WHERE active = 1 AND (#{serverId} IS NULL OR server_id = #{serverId}) ORDER BY filename")
    List<NginxConfigEntity> selectActive(@Param("serverId") Long serverId);

    @Select("SELECT * FROM nginx_config WHERE id = #{id} AND active = 1")
    NginxConfigEntity selectActiveById(@Param("id") Long id);

    @Select("SELECT * FROM nginx_config WHERE server_id = #{serverId} AND filename = #{filename}")
    NginxConfigEntity selectByFilename(@Param("serverId") Long serverId, @Param("filename") String filename);

    @Update("UPDATE nginx_config SET active = 0, updated_at = #{now} WHERE server_id = #{serverId} AND active = 1")
    int markInactive(@Param("serverId") Long serverId, @Param("now") LocalDateTime now);
}
