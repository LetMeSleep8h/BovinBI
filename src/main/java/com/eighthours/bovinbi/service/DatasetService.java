package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.dto.FieldUpdateReq;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据集与指标口径管理。
 * 数据隔离边界:数据集目录是全局共享的(所有用户可读、可问);
 * 但口径配置(字段别名/同义词/聚合方式)影响全体用户的生成质量 —— 只允许 ADMIN 修改。
 */
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetMapper datasetMapper;
    private final DatasetFieldMapper fieldMapper;

    public List<Dataset> list() {
        return datasetMapper.selectList(new LambdaQueryWrapper<Dataset>().orderByAsc(Dataset::getId));
    }

    public List<DatasetField> fields(Long datasetId) {
        return fieldMapper.selectList(new LambdaQueryWrapper<DatasetField>()
                .eq(DatasetField::getDatasetId, datasetId)
                .orderByAsc(DatasetField::getId));
    }

    /** 口径变更是全局生效的管理操作:仅 ADMIN(校验放服务层,不依赖端点自觉) */
    public void updateField(Long fieldId, FieldUpdateReq req) {
        if (!UserContext.isAdmin()) {
            throw new BizException(403, "数据集口径仅管理员可修改");
        }
        DatasetField f = fieldMapper.selectById(fieldId);
        if (f == null) {
            throw new BizException(404, "字段不存在");
        }
        DatasetField upd = new DatasetField();
        upd.setId(fieldId);
        if (req.alias() != null) upd.setAlias(req.alias());
        if (req.synonyms() != null) upd.setSynonyms(req.synonyms());
        if (req.description() != null) upd.setDescription(req.description());
        if (req.aggType() != null) upd.setAggType(req.aggType());
        fieldMapper.updateById(upd);
    }
}
