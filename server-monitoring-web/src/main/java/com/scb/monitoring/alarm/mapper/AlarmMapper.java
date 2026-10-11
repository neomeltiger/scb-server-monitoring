package com.scb.monitoring.alarm.mapper;

import java.sql.Timestamp;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.scb.monitoring.alarm.domain.Alarm;

/** 서버 장애 알람 Mapper. SQL: resources/mapper/alarm/AlarmMapper.xml (ANSI SQL) */
@Mapper
public interface AlarmMapper {

    /** 진행 중 알람 (OPEN / ACK) - CRITICAL 먼저, 최근 발생 순 */
    List<Alarm> selectActiveAlarmList();

    /** since 이후 해소(CLOSED)된 알람 최근 maxRows 건 */
    List<Alarm> selectClosedAlarmList(@Param("since") Timestamp since, @Param("maxRows") int maxRows);

    Alarm selectAlarm(@Param("alarmId") Long alarmId);

    int insertAlarm(Alarm alarm);

    /** 조건이 풀린 알람 해소 (해당 서버/종류의 OPEN·ACK 모두) */
    int closeAlarm(@Param("serverId") Long serverId, @Param("alarmType") String alarmType);

    int ackAlarm(@Param("alarmId") Long alarmId, @Param("ackUser") String ackUser, @Param("actionMemo") String actionMemo);

    int deleteAlarmsByServer(@Param("serverId") Long serverId);
}
