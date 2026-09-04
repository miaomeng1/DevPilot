package com.devpilot.server.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.maintenance.entity.RestoreDrillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RestoreDrillMapper extends BaseMapper<RestoreDrillEntity> {
    @Select("""
            SELECT d.*, b.file_name AS backup_file_name, u.display_name AS performed_by_name
            FROM maintenance_restore_drill d
            LEFT JOIN maintenance_backup_report b ON b.id = d.backup_report_id
            LEFT JOIN sys_user u ON u.id = d.performed_by
            ORDER BY d.performed_at DESC LIMIT 1
            """)
    RestoreDrillEntity selectLatest();
}
