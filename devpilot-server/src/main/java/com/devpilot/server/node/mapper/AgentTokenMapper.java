package com.devpilot.server.node.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.node.entity.AgentTokenEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentTokenMapper extends BaseMapper<AgentTokenEntity> {

    @Select("SELECT * FROM agent_token WHERE token_hash = #{hash} LIMIT 1")
    AgentTokenEntity selectByHash(@Param("hash") String hash);

    @Update("""
            UPDATE agent_token
            SET status = 'ACTIVE', last_used_at = #{now}
            WHERE id = #{id} AND revoked_at IS NULL AND status IN ('PENDING', 'ACTIVE')
            """)
    int activate(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_token SET last_used_at = #{now}
            WHERE id = #{id} AND status = 'ACTIVE' AND revoked_at IS NULL
            """)
    int touchActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_token SET status = 'REVOKED', revoked_at = #{now} WHERE server_id = #{serverId} AND revoked_at IS NULL")
    int revokeByServer(@Param("serverId") Long serverId, @Param("now") LocalDateTime now);
}
