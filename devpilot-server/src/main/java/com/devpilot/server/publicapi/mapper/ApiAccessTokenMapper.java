package com.devpilot.server.publicapi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.publicapi.entity.ApiAccessTokenEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApiAccessTokenMapper extends BaseMapper<ApiAccessTokenEntity> {
    @Select("SELECT * FROM api_access_token ORDER BY created_at DESC")
    List<ApiAccessTokenEntity> selectAll();

    @Select("SELECT * FROM api_access_token WHERE token_hash = #{hash} AND status = 'ACTIVE' "
            + "AND (expires_at IS NULL OR expires_at > #{now}) LIMIT 1")
    ApiAccessTokenEntity selectValid(@Param("hash") String hash, @Param("now") LocalDateTime now);

    @Update("UPDATE api_access_token SET last_used_at = #{now} WHERE id = #{id}")
    int touch(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE api_access_token SET status = 'REVOKED', revoked_at = #{now} "
            + "WHERE id = #{id} AND status = 'ACTIVE'")
    int revoke(@Param("id") Long id, @Param("now") LocalDateTime now);
}
