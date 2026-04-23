package com.codepilot.core.infrastructure.persistence.mybatis.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TeamConventionMapper {

    List<TeamConventionRow> selectByProjectId(@Param("projectId") String projectId);

    void deleteByProjectId(@Param("projectId") String projectId);

    void insertAll(@Param("rows") List<TeamConventionRow> teamConventions);
}
