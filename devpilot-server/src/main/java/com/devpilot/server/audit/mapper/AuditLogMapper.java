package com.devpilot.server.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.devpilot.server.audit.entity.AuditLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    @Select("""
            <script>
            SELECT * FROM audit_log
            <where>
              <if test="action != null">AND action = #{action}</if>
              <if test="result != null">AND result = #{result}</if>
              <if test="query != null">AND (LOWER(COALESCE(username,'')) LIKE #{query}
                OR LOWER(COALESCE(resource_name,'')) LIKE #{query}
                OR LOWER(action) LIKE #{query})</if>
              <if test="from != null">AND occurred_at &gt;= #{from}</if>
              <if test="to != null">AND occurred_at &lt;= #{to}</if>
            </where>
            ORDER BY occurred_at DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLogEntity> selectPage(@Param("action") String action, @Param("result") String result,
                                    @Param("query") String query, @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to, @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM audit_log
            <where>
              <if test="action != null">AND action = #{action}</if>
              <if test="result != null">AND result = #{result}</if>
              <if test="query != null">AND (LOWER(COALESCE(username,'')) LIKE #{query}
                OR LOWER(COALESCE(resource_name,'')) LIKE #{query}
                OR LOWER(action) LIKE #{query})</if>
              <if test="from != null">AND occurred_at &gt;= #{from}</if>
              <if test="to != null">AND occurred_at &lt;= #{to}</if>
            </where>
            </script>
            """)
    long countFiltered(@Param("action") String action, @Param("result") String result,
                       @Param("query") String query, @Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to);

    @Select("SELECT DISTINCT action FROM audit_log ORDER BY action ASC")
    List<String> selectActions();
}
