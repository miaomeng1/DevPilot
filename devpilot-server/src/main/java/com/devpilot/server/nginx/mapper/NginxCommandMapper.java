package com.devpilot.server.nginx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.nginx.entity.NginxCommandEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NginxCommandMapper extends BaseMapper<NginxCommandEntity> {
    @Select("SELECT COUNT(*) FROM nginx_command WHERE config_id = #{configId} AND status IN ('REQUESTED','CLAIMED')")
    long countPending(@Param("configId") Long configId);

    @Select("SELECT * FROM nginx_command WHERE server_id = #{serverId} AND status = 'REQUESTED' ORDER BY requested_at LIMIT 1")
    NginxCommandEntity selectNext(@Param("serverId") Long serverId);

    @Update("UPDATE nginx_command SET status = 'CLAIMED', claimed_at = #{now}, updated_at = #{now} WHERE id = #{id} AND status = 'REQUESTED'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM nginx_command WHERE status IN ('REQUESTED','CLAIMED') AND requested_at < #{cutoff}")
    List<NginxCommandEntity> selectExpired(@Param("cutoff") LocalDateTime cutoff);
}
