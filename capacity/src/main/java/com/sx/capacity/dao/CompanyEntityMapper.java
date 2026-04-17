package com.sx.capacity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sx.capacity.model.Company;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyEntityMapper extends BaseMapper<Company> {

    /**
     * 用于生成下一车队业务编码：COALESCE(MAX(team_id), 2000) + 1；含已逻辑删除行，避免复用号码。
     */
    @Select("SELECT COALESCE(MAX(team_id), 2000) FROM company")
    Long selectMaxTeamIdBaseline();
}
