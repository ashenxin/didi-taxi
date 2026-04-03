-- 派单联调用示例数据（capacity 库）
-- 目标：保证 /api/v1/dispatch/nearest-driver 在杭州(330100)+ECONOMY 能返回一个在线且可接单司机

USE `capacity`;

-- company
DELETE FROM `company` WHERE `id` IN (40001);
INSERT INTO `company` (
  `id`, `company_name`, `company_address`, `business_license`, `legal_person`,
  `legal_person_phone`, `contact_name`, `contact_phone`,
  `province_code`, `province_name`, `city_code`, `city_name`,
  `is_deleted`
) VALUES
(40001, '杭州运力公司-联调', '杭州市', 'BL-TEST-40001', '张三',
 '13800000001', '李四', '13800000002',
 '330000', '浙江省', '330100', '杭州市',
 0);

-- driver：在线(1) + 可接单(1)
DELETE FROM `driver` WHERE `id` IN (20001);
INSERT INTO `driver` (
  `id`, `driver_source`, `city_code`, `city_name`, `company_id`,
  `name`, `id_card`, `phone`,
  `monitor_status`, `can_accept_order`,
  `is_deleted`
) VALUES
(20001, 1, '330100', '杭州市', 40001,
 '张师傅-联调', '330102199001010011', '13900000001',
 1, 1,
 0);

-- car：绑定 driver_id=20001，ride_type_id=ECONOMY
DELETE FROM `car` WHERE `id` IN (30011);
INSERT INTO `car` (
  `id`, `driver_id`, `city_code`, `city_name`, `car_no`,
  `ride_type_id`, `business_type_id`, `car_state`, `car_num`,
  `is_deleted`
) VALUES
(30011, 20001, '330100', '杭州市', '浙A10001',
 'ECONOMY', '快车', 0, 4,
 0);

