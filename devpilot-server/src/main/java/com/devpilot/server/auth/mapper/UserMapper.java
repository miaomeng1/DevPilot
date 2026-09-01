package com.devpilot.server.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.auth.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("""
            SELECT * FROM sys_user
            WHERE username = #{username} AND deleted = 0
            LIMIT 1
            """)
    UserEntity selectActiveByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    UserEntity selectAnyByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0 LIMIT 1 FOR UPDATE")
    UserEntity selectActiveByUsernameForUpdate(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted = 0")
    UserEntity selectActiveById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0")
    long countActiveUsers();

    @Select("SELECT * FROM sys_user WHERE deleted = 0 ORDER BY created_at ASC")
    List<UserEntity> selectAllUsers();

    @Select("SELECT * FROM sys_user WHERE email = #{email} AND deleted = 0 LIMIT 1")
    UserEntity selectActiveByEmail(@Param("email") String email);

    @Select("SELECT * FROM sys_user WHERE email = #{email} LIMIT 1")
    UserEntity selectAnyByEmail(@Param("email") String email);

    @Select("""
            SELECT COUNT(*) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE u.deleted = 0 AND u.status = 'ACTIVE' AND r.code = 'ADMIN'
            """)
    long countActiveAdministrators();

    @Update("""
            UPDATE sys_user
            SET last_login_at = #{now}, failed_login_count = 0,
                locked_until = NULL, updated_at = #{now}
            WHERE id = #{id}
            """)
    int recordSuccessfulLogin(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash}, session_version = session_version + 1,
                failed_login_count = 0, locked_until = NULL, updated_at = #{now}
            WHERE id = #{id} AND deleted = 0
            """)
    int updatePasswordAndClearLock(@Param("id") Long id,
                                   @Param("passwordHash") String passwordHash,
                                   @Param("now") java.time.LocalDateTime now);

}
