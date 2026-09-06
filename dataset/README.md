# BovinBI 数据集说明

## 数据来源

「智慧牧场·奶牛养殖」**合成业务数据集**,由 [build_bovine_dwh.py](build_bovine_dwh.py) 按固定随机种子(random_state=42)生成,**可审计、可复现**。
业务口径参考规模化奶牛养殖场真实运营模式:6 个牧场、1,200 头泌乳牛、2025-09 ~ 2026-08 共 12 个月、每月 5/15/25 日三次采样挤奶记录。

> 旧版数据集(UCI Online Retail 电商零售)已归档至 `archive/retail-uci-dataset/`,随时可切回。

## 快速使用

```bash
python3 dataset/build_bovine_dwh.py   # 一键重新生成(python3 即可,无第三方依赖)
mvn spring-boot:run                   # 后端启动时自动建表 + 装载,无需手工导入
```

## 数据规模

| 表 | 行数 | 说明 |
| --- | --- | --- |
| `dwh_dim_farm` | 6 | 牧场(华北/西北/东北/华东,大/中/小型) |
| `dwh_dim_cattle` | 1,200 | 牛只(荷斯坦/西门塔尔/娟姗,胎次 1~4,牛舍 A~F) |
| `dwh_fact_milk` | 36,984 | 挤奶记录(产奶量/乳脂率/乳蛋白率/泌乳阶段) |

## 内置业务规律(供 BI 分析发现)

生成器不是随机数堆砌,内置了 5 条真实牧业的量化规律,分析问题都能"问出答案":

| 规律 | 参数 |
| --- | --- |
| 品种差异 | 荷斯坦单产最高(基准 30kg/日),娟姗乳脂率最高(基准 5.2%),西门塔尔居中 |
| 胎次曲线 | 第 2~3 胎为泌乳高峰(×1.00/×1.02),头胎偏低(×0.86),4 胎回落(×0.94) |
| 泌乳阶段 | 初期爬坡(×0.92) → 中期平台(×1.00) → 后期下降(×0.72);每头牛每年 2 个干奶月,干奶期无记录 |
| 季节规律 | 夏季(6-8 月)热应激减产 ×0.88,冬季(12-2 月)增产 ×1.06 |
| 牧场管理 | 大型牧场 ×1.06 > 中型 ×1.00 > 小型 ×0.93 |

乳脂率/乳蛋白率与单产弱负相关(模拟"稀释效应"),并叠加品种基准与噪声。

## 数据字典

**dwh_fact_milk(挤奶记录事实表)**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGINT | 主键 |
| record_date | DATE | 挤奶记录日期(每月 5/15/25 日) |
| cattle_id | BIGINT | 牛只外键 → dwh_dim_cattle.id |
| farm_id | BIGINT | 牧场外键 → dwh_dim_farm.id |
| milk_yield | DECIMAL(8,1) | 单次挤奶产奶量(千克) |
| fat_rate | DECIMAL(5,2) | 乳脂率(%) |
| protein_rate | DECIMAL(5,2) | 乳蛋白率(%) |
| lactation_stage | VARCHAR(16) | 泌乳初期/中期/后期 |

**dwh_dim_cattle(牛只维度)**:id, cattle_code(耳标号,如 F001-C0042), breed(品种), parity(胎次), barn(牛舍), farm_id

**dwh_dim_farm(牧场维度)**:id, farm_code, farm_name, region(地区), scale(规模)

## 与语义层的对应

启动时 `DataLoader` 自动把上述字段注册为「牧场养殖分析」数据集的语义层(维度/指标/同义词/口径),
业务黑话如"奶量/头数/单产/乳脂"即可命中字段 —— 与通用语义层 Dataset/Dimension/Metric 的建模思想一致。
