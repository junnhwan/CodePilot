package com.codepilot.core.infrastructure.persistence.mybatis.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewPatternMapper {

    List<ReviewPatternRow> selectByProjectId(@Param("projectId") String projectId);

    void deleteByProjectId(@Param("projectId") String projectId);

    void insertAll(@Param("rows") List<ReviewPatternRow> reviewPatterns);
}
