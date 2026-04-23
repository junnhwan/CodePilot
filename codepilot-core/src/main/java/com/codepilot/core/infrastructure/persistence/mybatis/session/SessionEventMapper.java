package com.codepilot.core.infrastructure.persistence.mybatis.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SessionEventMapper {

    List<SessionEventRow> selectBySessionId(@Param("sessionId") String sessionId);

    void deleteBySessionId(@Param("sessionId") String sessionId);

    void insert(@Param("row") SessionEventRow sessionEventRow);
}
