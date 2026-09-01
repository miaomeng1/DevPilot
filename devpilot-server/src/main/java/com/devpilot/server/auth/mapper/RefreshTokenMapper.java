package com.devpilot.server.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.auth.entity.RefreshTokenEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {

    @Select("SELECT * FROM auth_refresh_token WHERE token_hash = #{hash} LIMIT 1 FOR UPDATE")
    RefreshTokenEntity selectByHashForUpdate(@Param("hash") String hash);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = #{now}, replaced_by_hash = #{replacementHash}
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int rotate(@Param("id") Long id,
               @Param("replacementHash") String replacementHash,
               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = COALESCE(revoked_at, #{now})
            WHERE family_id = #{familyId} AND revoked_at IS NULL
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE auth_refresh_token
            SET revoked_at = #{now}
            WHERE token_hash = #{hash} AND revoked_at IS NULL
            """)
    int revokeByHash(@Param("hash") String hash, @Param("now") LocalDateTime now);

    @Update("UPDATE auth_refresh_token SET revoked_at = COALESCE(revoked_at, #{now}) WHERE user_id = #{userId}")
    int revokeByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
