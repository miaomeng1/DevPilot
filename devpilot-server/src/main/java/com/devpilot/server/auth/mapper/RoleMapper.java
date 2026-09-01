package com.devpilot.server.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.auth.entity.RoleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {

    @Select("SELECT * FROM sys_role WHERE code = #{code} LIMIT 1")
    RoleEntity selectByCode(@Param("code") String code);
}

