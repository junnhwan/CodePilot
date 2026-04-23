package com.codepilot.core.infrastructure.persistence.mybatis.session;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReviewSessionMapper {

    ReviewSessionRow selectById(@Param("sessionId") String sessionId);

    void upsert(@Param("row") ReviewSessionRow reviewSessionRow);
}
