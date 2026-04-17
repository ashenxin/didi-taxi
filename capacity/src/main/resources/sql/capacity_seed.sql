-- =============================================================================
-- capacity 库：全量种子（TRUNCATE 后写入演示数据；建表请先执行 capacity_schema.sql）
-- 规模：company 3 | driver 30 | driver_audit_record 30 | car 24 | driver_team_change_request 4
-- 司机审核：24 通过 | 2 待完善 | 2 审核中 | 2 驳回（通过占比 80%）
-- 换队：2 条 PENDING、1 条 APPROVED（并同步更新司机 company_id）、1 条 REJECTED
-- 执行前务必备份；仅用于本地/测试环境。
-- =============================================================================

USE `capacity`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE `car`;
TRUNCATE TABLE `driver_team_change_request`;
TRUNCATE TABLE `driver_audit_record`;
TRUNCATE TABLE `driver`;
TRUNCATE TABLE `company`;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 运力公司（3 家：杭州×2、上海×1；team_id 全局唯一）
-- -----------------------------------------------------------------------------
INSERT INTO `company` (
  `id`, `city_code`, `city_name`, `province_code`, `province_name`,
  `company_no`, `company_name`, `team_id`, `team`, `is_deleted`
) VALUES
(800001, '330100', '杭州市', '330000', '浙江省', 'CP-HZ-001', '钱江新城出行', 910001001, '钱江车队', 0),
(800002, '330100', '杭州市', '330000', '浙江省', 'CP-HZ-002', '西湖快运', 910002001, '西湖车队', 0),
-- 上海示例：档案市级字段落区县码 310104「徐汇区」（与国标一致）
(800003, '310104', '徐汇区', '310000', '上海市', 'CP-SH-001', '浦东畅行', 910003001, '浦东车队', 0);

INSERT INTO `driver` (
  `id`, `driver_source`, `city_code`, `city_name`, `province_code`, `province_name`, `company_id`, `brand_no`, `brand_name`,
  `name`, `id_card`, `id_card_photo_a`, `id_card_photo_b`, `phone`, `password_hash`,
  `gender`, `birthday`, `nationality`, `nation`, `marital_status`,
  `photo_oss`, `with_car_photo`, `license_photo_oss_a`, `license_photo_oss_b`,
  `get_driver_license_date`, `driver_license_on`, `driver_license_off`,
  `rpt_status`, `monitor_status`, `can_accept_order`, `audit_status`, `audit_last_record_id`, `is_deleted`
) VALUES
(900001, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900001', '合作品牌', '张伟01', '33010619880101000X', 'https://img.example.com/d/900001/id1.jpg', 'https://img.example.com/d/900001/id2.jpg', '13900100001', NULL, 1, '1988-01-01', '中国', '汉族', 1, 'https://img.example.com/d/900001/face.jpg', 'https://img.example.com/d/900001/car.jpg', 'https://img.example.com/d/900001/lic1.jpg', 'https://img.example.com/d/900001/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 1, 1, 2, NULL, 0),
(900002, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900002', '合作品牌', '李芳02', '33010619880202001X', 'https://img.example.com/d/900002/id1.jpg', 'https://img.example.com/d/900002/id2.jpg', '13900100002', NULL, 2, '1988-02-02', '中国', '汉族', 1, 'https://img.example.com/d/900002/face.jpg', 'https://img.example.com/d/900002/car.jpg', 'https://img.example.com/d/900002/lic1.jpg', 'https://img.example.com/d/900002/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900003, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900003', '合作品牌', '王强03', '33010619880303002X', 'https://img.example.com/d/900003/id1.jpg', 'https://img.example.com/d/900003/id2.jpg', '13900100003', NULL, 1, '1988-03-03', '中国', '汉族', 1, 'https://img.example.com/d/900003/face.jpg', 'https://img.example.com/d/900003/car.jpg', 'https://img.example.com/d/900003/lic1.jpg', 'https://img.example.com/d/900003/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900004, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900004', '合作品牌', '刘敏04', '33010619880404003X', 'https://img.example.com/d/900004/id1.jpg', 'https://img.example.com/d/900004/id2.jpg', '13900100004', NULL, 2, '1988-04-04', '中国', '汉族', 1, 'https://img.example.com/d/900004/face.jpg', 'https://img.example.com/d/900004/car.jpg', 'https://img.example.com/d/900004/lic1.jpg', 'https://img.example.com/d/900004/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900005, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900005', '合作品牌', '陈静05', '33010619880505004X', 'https://img.example.com/d/900005/id1.jpg', 'https://img.example.com/d/900005/id2.jpg', '13900100005', NULL, 1, '1988-05-05', '中国', '汉族', 1, 'https://img.example.com/d/900005/face.jpg', 'https://img.example.com/d/900005/car.jpg', 'https://img.example.com/d/900005/lic1.jpg', 'https://img.example.com/d/900005/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900006, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900006', '合作品牌', '杨丽06', '33010619880606005X', 'https://img.example.com/d/900006/id1.jpg', 'https://img.example.com/d/900006/id2.jpg', '13900100006', NULL, 2, '1988-06-06', '中国', '汉族', 1, 'https://img.example.com/d/900006/face.jpg', 'https://img.example.com/d/900006/car.jpg', 'https://img.example.com/d/900006/lic1.jpg', 'https://img.example.com/d/900006/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 1, 1, 2, NULL, 0),
(900007, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900007', '合作品牌', '赵军07', '33010619880707006X', 'https://img.example.com/d/900007/id1.jpg', 'https://img.example.com/d/900007/id2.jpg', '13900100007', NULL, 1, '1988-07-07', '中国', '汉族', 1, 'https://img.example.com/d/900007/face.jpg', 'https://img.example.com/d/900007/car.jpg', 'https://img.example.com/d/900007/lic1.jpg', 'https://img.example.com/d/900007/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900008, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900008', '合作品牌', '黄洋08', '33010619880808007X', 'https://img.example.com/d/900008/id1.jpg', 'https://img.example.com/d/900008/id2.jpg', '13900100008', NULL, 2, '1988-08-08', '中国', '汉族', 1, 'https://img.example.com/d/900008/face.jpg', 'https://img.example.com/d/900008/car.jpg', 'https://img.example.com/d/900008/lic1.jpg', 'https://img.example.com/d/900008/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900009, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900009', '合作品牌', '周勇09', '33010619880909008X', 'https://img.example.com/d/900009/id1.jpg', 'https://img.example.com/d/900009/id2.jpg', '13900100009', NULL, 1, '1988-09-09', '中国', '汉族', 1, 'https://img.example.com/d/900009/face.jpg', 'https://img.example.com/d/900009/car.jpg', 'https://img.example.com/d/900009/lic1.jpg', 'https://img.example.com/d/900009/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900010, 1, '330100', '杭州市', '330000', '浙江省', 800001, 'BR-900010', '合作品牌', '吴杰10', '33010619881010009X', 'https://img.example.com/d/900010/id1.jpg', 'https://img.example.com/d/900010/id2.jpg', '13900100010', NULL, 2, '1988-10-10', '中国', '汉族', 1, 'https://img.example.com/d/900010/face.jpg', 'https://img.example.com/d/900010/car.jpg', 'https://img.example.com/d/900010/lic1.jpg', 'https://img.example.com/d/900010/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900011, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900011', '合作品牌', '徐涛11', '33010619881111010X', 'https://img.example.com/d/900011/id1.jpg', 'https://img.example.com/d/900011/id2.jpg', '13900100011', NULL, 1, '1988-11-11', '中国', '汉族', 1, 'https://img.example.com/d/900011/face.jpg', 'https://img.example.com/d/900011/car.jpg', 'https://img.example.com/d/900011/lic1.jpg', 'https://img.example.com/d/900011/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 1, 1, 2, NULL, 0),
(900012, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900012', '合作品牌', '孙鹏12', '33010619881212011X', 'https://img.example.com/d/900012/id1.jpg', 'https://img.example.com/d/900012/id2.jpg', '13900100012', NULL, 2, '1988-12-12', '中国', '汉族', 1, 'https://img.example.com/d/900012/face.jpg', 'https://img.example.com/d/900012/car.jpg', 'https://img.example.com/d/900012/lic1.jpg', 'https://img.example.com/d/900012/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900013, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900013', '合作品牌', '胡娜13', '33010619880113012X', 'https://img.example.com/d/900013/id1.jpg', 'https://img.example.com/d/900013/id2.jpg', '13900100013', NULL, 1, '1988-01-13', '中国', '汉族', 1, 'https://img.example.com/d/900013/face.jpg', 'https://img.example.com/d/900013/car.jpg', 'https://img.example.com/d/900013/lic1.jpg', 'https://img.example.com/d/900013/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900014, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900014', '合作品牌', '朱超14', '33010619880214013X', 'https://img.example.com/d/900014/id1.jpg', 'https://img.example.com/d/900014/id2.jpg', '13900100014', NULL, 2, '1988-02-14', '中国', '汉族', 1, 'https://img.example.com/d/900014/face.jpg', 'https://img.example.com/d/900014/car.jpg', 'https://img.example.com/d/900014/lic1.jpg', 'https://img.example.com/d/900014/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900015, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900015', '合作品牌', '高磊15', '33010619880315014X', 'https://img.example.com/d/900015/id1.jpg', 'https://img.example.com/d/900015/id2.jpg', '13900100015', NULL, 1, '1988-03-15', '中国', '汉族', 1, 'https://img.example.com/d/900015/face.jpg', 'https://img.example.com/d/900015/car.jpg', 'https://img.example.com/d/900015/lic1.jpg', 'https://img.example.com/d/900015/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900016, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900016', '合作品牌', '林斌16', '33010619880416015X', 'https://img.example.com/d/900016/id1.jpg', 'https://img.example.com/d/900016/id2.jpg', '13900100016', NULL, 2, '1988-04-16', '中国', '汉族', 1, 'https://img.example.com/d/900016/face.jpg', 'https://img.example.com/d/900016/car.jpg', 'https://img.example.com/d/900016/lic1.jpg', 'https://img.example.com/d/900016/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 1, 1, 2, NULL, 0),
(900017, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900017', '合作品牌', '何浩17', '33010619880517016X', 'https://img.example.com/d/900017/id1.jpg', 'https://img.example.com/d/900017/id2.jpg', '13900100017', NULL, 1, '1988-05-17', '中国', '汉族', 1, 'https://img.example.com/d/900017/face.jpg', 'https://img.example.com/d/900017/car.jpg', 'https://img.example.com/d/900017/lic1.jpg', 'https://img.example.com/d/900017/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900018, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900018', '合作品牌', '郭颖18', '33010619880618017X', 'https://img.example.com/d/900018/id1.jpg', 'https://img.example.com/d/900018/id2.jpg', '13900100018', NULL, 2, '1988-06-18', '中国', '汉族', 1, 'https://img.example.com/d/900018/face.jpg', 'https://img.example.com/d/900018/car.jpg', 'https://img.example.com/d/900018/lic1.jpg', 'https://img.example.com/d/900018/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900019, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900019', '合作品牌', '马波19', '33010619880719018X', 'https://img.example.com/d/900019/id1.jpg', 'https://img.example.com/d/900019/id2.jpg', '13900100019', NULL, 1, '1988-07-19', '中国', '汉族', 1, 'https://img.example.com/d/900019/face.jpg', 'https://img.example.com/d/900019/car.jpg', 'https://img.example.com/d/900019/lic1.jpg', 'https://img.example.com/d/900019/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900020, 1, '330100', '杭州市', '330000', '浙江省', 800002, 'BR-900020', '合作品牌', '罗霞20', '33010619880820019X', 'https://img.example.com/d/900020/id1.jpg', 'https://img.example.com/d/900020/id2.jpg', '13900100020', NULL, 2, '1988-08-20', '中国', '汉族', 1, 'https://img.example.com/d/900020/face.jpg', 'https://img.example.com/d/900020/car.jpg', 'https://img.example.com/d/900020/lic1.jpg', 'https://img.example.com/d/900020/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900021, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900021', '合作品牌', '张伟21', '31011519880921020X', 'https://img.example.com/d/900021/id1.jpg', 'https://img.example.com/d/900021/id2.jpg', '13900100021', NULL, 1, '1988-09-21', '中国', '汉族', 1, 'https://img.example.com/d/900021/face.jpg', 'https://img.example.com/d/900021/car.jpg', 'https://img.example.com/d/900021/lic1.jpg', 'https://img.example.com/d/900021/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 1, 1, 2, NULL, 0),
(900022, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900022', '合作品牌', '李芳22', '31011519881022021X', 'https://img.example.com/d/900022/id1.jpg', 'https://img.example.com/d/900022/id2.jpg', '13900100022', NULL, 2, '1988-10-22', '中国', '汉族', 1, 'https://img.example.com/d/900022/face.jpg', 'https://img.example.com/d/900022/car.jpg', 'https://img.example.com/d/900022/lic1.jpg', 'https://img.example.com/d/900022/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900023, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900023', '合作品牌', '王强23', '31011519881123022X', 'https://img.example.com/d/900023/id1.jpg', 'https://img.example.com/d/900023/id2.jpg', '13900100023', NULL, 1, '1988-11-23', '中国', '汉族', 1, 'https://img.example.com/d/900023/face.jpg', 'https://img.example.com/d/900023/car.jpg', 'https://img.example.com/d/900023/lic1.jpg', 'https://img.example.com/d/900023/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
(900024, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900024', '合作品牌', '刘敏24', '31011519881224023X', 'https://img.example.com/d/900024/id1.jpg', 'https://img.example.com/d/900024/id2.jpg', '13900100024', NULL, 2, '1988-12-24', '中国', '汉族', 1, 'https://img.example.com/d/900024/face.jpg', 'https://img.example.com/d/900024/car.jpg', 'https://img.example.com/d/900024/lic1.jpg', 'https://img.example.com/d/900024/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 1, 2, NULL, 0),
-- 待完善行：phone 之后必须 13 个 NULL（password_hash～driver_license_off 共 13 列），此前少 1 个会报 1136
(900025, 0, '310104', '徐汇区', '310000', '上海市', 800003, NULL, NULL, '待完善25', NULL, NULL, NULL, '13900100025', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 0, 0, NULL, 0),
(900026, 0, '310104', '徐汇区', '310000', '上海市', 800003, NULL, NULL, '待完善26', NULL, NULL, NULL, '13900100026', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, 0, 0, NULL, 0),
(900027, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900027', '合作品牌', '赵军27', '31011519880327026X', 'https://img.example.com/d/900027/id1.jpg', 'https://img.example.com/d/900027/id2.jpg', '13900100027', NULL, 1, '1988-03-27', '中国', '汉族', 1, 'https://img.example.com/d/900027/face.jpg', 'https://img.example.com/d/900027/car.jpg', 'https://img.example.com/d/900027/lic1.jpg', 'https://img.example.com/d/900027/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 0, 1, NULL, 0),
(900028, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900028', '合作品牌', '黄洋28', '31011519880428027X', 'https://img.example.com/d/900028/id1.jpg', 'https://img.example.com/d/900028/id2.jpg', '13900100028', NULL, 2, '1988-04-28', '中国', '汉族', 1, 'https://img.example.com/d/900028/face.jpg', 'https://img.example.com/d/900028/car.jpg', 'https://img.example.com/d/900028/lic1.jpg', 'https://img.example.com/d/900028/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 0, 1, NULL, 0),
(900029, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900029', '合作品牌', '周勇29', '31011519880501028X', 'https://img.example.com/d/900029/id1.jpg', 'https://img.example.com/d/900029/id2.jpg', '13900100029', NULL, 1, '1988-05-01', '中国', '汉族', 1, 'https://img.example.com/d/900029/face.jpg', 'https://img.example.com/d/900029/car.jpg', 'https://img.example.com/d/900029/lic1.jpg', 'https://img.example.com/d/900029/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 0, 3, NULL, 0),
(900030, 1, '310104', '徐汇区', '310000', '上海市', 800003, 'BR-900030', '合作品牌', '吴杰30', '31011519880602029X', 'https://img.example.com/d/900030/id1.jpg', 'https://img.example.com/d/900030/id2.jpg', '13900100030', NULL, 2, '1988-06-02', '中国', '汉族', 1, 'https://img.example.com/d/900030/face.jpg', 'https://img.example.com/d/900030/car.jpg', 'https://img.example.com/d/900030/lic1.jpg', 'https://img.example.com/d/900030/lic2.jpg', DATE '2012-05-18', DATE '2018-06-01', DATE '2034-06-01', 0, 0, 0, 3, NULL, 0);

INSERT INTO `driver_audit_record` (`driver_id`, `result_status`, `reason`, `operator_type`, `operator_id`) VALUES
(900001, 2, '资料齐备，审核通过', 1, NULL),
(900002, 2, '资料齐备，审核通过', 1, NULL),
(900003, 2, '资料齐备，审核通过', 1, NULL),
(900004, 2, '资料齐备，审核通过', 1, NULL),
(900005, 2, '资料齐备，审核通过', 1, NULL),
(900006, 2, '资料齐备，审核通过', 1, NULL),
(900007, 2, '资料齐备，审核通过', 1, NULL),
(900008, 2, '资料齐备，审核通过', 1, NULL),
(900009, 2, '资料齐备，审核通过', 1, NULL),
(900010, 2, '资料齐备，审核通过', 1, NULL),
(900011, 2, '资料齐备，审核通过', 1, NULL),
(900012, 2, '资料齐备，审核通过', 1, NULL),
(900013, 2, '资料齐备，审核通过', 1, NULL),
(900014, 2, '资料齐备，审核通过', 1, NULL),
(900015, 2, '资料齐备，审核通过', 1, NULL),
(900016, 2, '资料齐备，审核通过', 1, NULL),
(900017, 2, '资料齐备，审核通过', 1, NULL),
(900018, 2, '资料齐备，审核通过', 1, NULL),
(900019, 2, '资料齐备，审核通过', 1, NULL),
(900020, 2, '资料齐备，审核通过', 1, NULL),
(900021, 2, '资料齐备，审核通过', 1, NULL),
(900022, 2, '资料齐备，审核通过', 1, NULL),
(900023, 2, '资料齐备，审核通过', 1, NULL),
(900024, 2, '资料齐备，审核通过', 1, NULL),
(900025, 0, '资料未提交完整', 1, NULL),
(900026, 0, '资料未提交完整', 1, NULL),
(900027, 1, '已提交，等待人工审核', 1, NULL),
(900028, 1, '已提交，等待人工审核', 1, NULL),
(900029, 3, '驾驶证副页模糊，请补拍后重新提交', 1, NULL),
(900030, 3, '风控：资料需复核，请按指引补件', 1, NULL);

INSERT INTO `car` (
  `id`, `driver_id`, `city_code`, `city_name`, `car_no`, `plate_color`, `vehicle_type`, `owner_name`,
  `certify_date_a`, `fuel_type`, `photo_oss`, `with_photo_oss`, `ride_type_id`, `business_type_id`, `car_state`, `car_num`, `is_deleted`
) VALUES
(960001, 900001, '330100', '杭州市', '浙A·D10001', '蓝色', '小型轿车', '张伟', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960001/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960002, 900002, '330100', '杭州市', '浙A·D10002', '蓝色', '小型轿车', '李芳', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960002/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960003, 900003, '330100', '杭州市', '浙A·D10003', '蓝色', '小型轿车', '王强', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960003/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960004, 900004, '330100', '杭州市', '浙A·D10004', '蓝色', '小型轿车', '刘敏', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960004/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960005, 900005, '330100', '杭州市', '浙A·D10005', '蓝色', '小型轿车', '陈静', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960005/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960006, 900006, '330100', '杭州市', '浙A·D10006', '蓝色', '小型轿车', '杨丽', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960006/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960007, 900007, '330100', '杭州市', '浙A·D10007', '蓝色', '小型轿车', '赵军', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960007/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960008, 900008, '330100', '杭州市', '浙A·D10008', '蓝色', '小型轿车', '黄洋', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960008/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960009, 900009, '330100', '杭州市', '浙A·D10009', '蓝色', '小型轿车', '周勇', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960009/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960010, 900010, '330100', '杭州市', '浙A·D10010', '蓝色', '小型轿车', '吴杰', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960010/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960011, 900011, '330100', '杭州市', '浙A·D10011', '蓝色', '小型轿车', '徐涛', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960011/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960012, 900012, '330100', '杭州市', '浙A·D10012', '蓝色', '小型轿车', '孙鹏', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960012/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960013, 900013, '330100', '杭州市', '浙A·D10013', '蓝色', '小型轿车', '胡娜', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960013/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960014, 900014, '330100', '杭州市', '浙A·D10014', '蓝色', '小型轿车', '朱超', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960014/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960015, 900015, '330100', '杭州市', '浙A·D10015', '蓝色', '小型轿车', '高磊', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960015/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960016, 900016, '330100', '杭州市', '浙A·D10016', '蓝色', '小型轿车', '林斌', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960016/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960017, 900017, '330100', '杭州市', '浙A·D10017', '蓝色', '小型轿车', '何浩', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960017/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960018, 900018, '330100', '杭州市', '浙A·D10018', '蓝色', '小型轿车', '郭颖', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960018/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960019, 900019, '330100', '杭州市', '浙A·D10019', '蓝色', '小型轿车', '马波', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960019/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960020, 900020, '330100', '杭州市', '浙A·D10020', '蓝色', '小型轿车', '罗霞', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960020/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960021, 900021, '310104', '徐汇区', '沪A·D20021', '蓝色', '小型轿车', '张伟', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960021/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960022, 900022, '310104', '徐汇区', '沪A·D20022', '蓝色', '小型轿车', '李芳', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960022/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960023, 900023, '310104', '徐汇区', '沪A·D20023', '蓝色', '小型轿车', '王强', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960023/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0),
(960024, 900024, '310104', '徐汇区', '沪A·D20024', '蓝色', '小型轿车', '刘敏', DATE '2019-03-01', '汽油', 'https://img.example.com/v/960024/v.jpg', NULL, 'ECONOMY', '快车', 0, 5, 0);

INSERT INTO `driver_team_change_request` (
  `id`, `driver_id`, `from_company_id`, `to_company_id`, `status`, `request_reason`,
  `requested_by`, `requested_at`, `reviewed_by`, `reviewed_at`, `review_reason`, `is_deleted`
) VALUES
(970001, 900005, 800001, 800003, 'PENDING', '常驻上海亲戚家，希望挂靠浦东车队', 'driver:900005', DATE_SUB(NOW(), INTERVAL 2 HOUR), NULL, NULL, NULL, 0),
(970002, 900015, 800002, 800001, 'PENDING', '想回钱江片区接单', 'driver:900015', DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, NULL, NULL, 0),
(970003, 900008, 800001, 800002, 'APPROVED', '同城换队测试', 'driver:900008', DATE_SUB(NOW(), INTERVAL 5 DAY), 'admin:seed', DATE_SUB(NOW(), INTERVAL 4 DAY), '材料齐全，同意', 0),
(970004, 900012, 800001, 800003, 'REJECTED', '申请去上海', 'driver:900012', DATE_SUB(NOW(), INTERVAL 3 DAY), 'admin:seed', DATE_SUB(NOW(), INTERVAL 2 DAY), '目标车队名额已满', 0);

-- 与 970003「已通过」一致：900008 已从钱江换入西湖车队
UPDATE `driver` SET `company_id` = 800002 WHERE `id` = 900008;

UPDATE `driver` d
INNER JOIN (
  SELECT `driver_id`, MAX(`id`) AS `rid`
  FROM `driver_audit_record`
  GROUP BY `driver_id`
) t ON t.`driver_id` = d.`id`
SET d.`audit_last_record_id` = t.`rid`
WHERE d.`is_deleted` = 0;

ALTER TABLE `company` AUTO_INCREMENT = 900000;
ALTER TABLE `driver` AUTO_INCREMENT = 910000;
ALTER TABLE `driver_audit_record` AUTO_INCREMENT = 960000;
ALTER TABLE `car` AUTO_INCREMENT = 970000;
ALTER TABLE `driver_team_change_request` AUTO_INCREMENT = 980000;

