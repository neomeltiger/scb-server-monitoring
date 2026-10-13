package com.scb.monitoring.retention.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.retention.domain.Retention;
import com.scb.monitoring.retention.domain.RetentionStat;

/**
 * 로그 · 이력 보관 기간 Mapper. SQL: resources/mapper/retention/RetentionMapper.xml (ANSI SQL)
 * table / dtCol / cond 는 RetentionService 의 고정 목록에서만 넘김 (사용자 입력 아님)
 */
@Mapper
public interface RetentionMapper {

    List<Retention> selectAll();

    Retention selectOne(@Param("categoryCd") String categoryCd);

    int update(@Param("categoryCd") String categoryCd, @Param("keepDays") int keepDays, @Param("updUser") String updUser);

    int insert(@Param("categoryCd") String categoryCd, @Param("keepDays") int keepDays, @Param("updUser") String updUser);

    /** 대상 테이블 행 수와 가장 오래된 일시 */
    RetentionStat selectStat(@Param("table") String table, @Param("dtCol") String dtCol, @Param("cond") String cond);

    /** before 이전 행 삭제 (cond 가 있으면 함께) */
    int deleteBefore(@Param("table") String table, @Param("dtCol") String dtCol, @Param("cond") String cond, @Param("before") Timestamp before);
}
