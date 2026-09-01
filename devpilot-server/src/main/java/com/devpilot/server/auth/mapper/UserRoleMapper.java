package com.devpilot.server.auth.mapper;

import com.devpilot.server.auth.entity.UserRoleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface UserRoleMapper {

    @Insert("""
            INSERT INTO sys_user_role (user_id, role_id, created_at)
            VALUES (#{userId}, #{roleId}, #{createdAt})
            """)
    int insert(UserRoleEntity relation);

    @Select("""
            SELECT r.code
            FROM sys_role r
            JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.id
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
