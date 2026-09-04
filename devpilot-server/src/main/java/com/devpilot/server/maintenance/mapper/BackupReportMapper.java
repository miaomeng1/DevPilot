package com.devpilot.server.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.maintenance.entity.BackupReportEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BackupReportMapper extends BaseMapper<BackupReportEntity> {
    @Select("SELECT * FROM maintenance_backup_report WHERE sha256 = #{sha256} LIMIT 1")
    BackupReportEntity selectBySha256(@Param("sha256") String sha256);

    @Select("SELECT * FROM maintenance_backup_report ORDER BY created_at DESC LIMIT #{limit}")
    List<BackupReportEntity> selectRecent(@Param("limit") int limit);
}
