-- 开源版设备管理补丁：可重复执行
CREATE TABLE IF NOT EXISTS `ems_device` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL, `company_id` bigint NOT NULL, `station_id` bigint NOT NULL,
  `controller_id` bigint DEFAULT NULL, `device_code` varchar(64) NOT NULL, `device_name` varchar(128) NOT NULL,
  `device_type` varchar(64) NOT NULL DEFAULT 'OTHER', `rated_capacity` decimal(18,6) DEFAULT NULL,
  `model` varchar(128) DEFAULT NULL, `serial_no` varchar(128) DEFAULT NULL, `manufacturer` varchar(128) DEFAULT NULL,
  `firmware_version` varchar(128) DEFAULT NULL, `last_heartbeat_time` datetime DEFAULT NULL,
  `controller_version` varchar(128) DEFAULT NULL, `install_date` date DEFAULT NULL,
  `comm_status` varchar(32) DEFAULT 'UNKNOWN', `status` varchar(32) DEFAULT 'NORMAL',
  `del_flag` char(1) DEFAULT '0', `create_by` varchar(64) DEFAULT '', `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT '', `update_time` datetime DEFAULT NULL, `remark` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_ems_device_code` (`tenant_id`,`device_code`),
  KEY `idx_ems_device_station` (`tenant_id`,`station_id`), KEY `idx_ems_device_company` (`tenant_id`,`company_id`),
  KEY `idx_ems_device_type_status` (`device_type`,`status`), KEY `idx_ems_device_serial` (`serial_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='EMS设备表';

INSERT IGNORE INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`) VALUES
(4300,'设备管理',0,40,'ems-device','Layout',1,0,'M','0','0','','server','admin',NOW(),'EMS设备管理目录'),
(4301,'设备管理',4300,1,'index','ems/device/index',1,0,'C','0','0','ems:device:list','server','admin',NOW(),'开源版设备模拟管理');
INSERT IGNORE INTO `sys_menu` (`menu_id`,`menu_name`,`parent_id`,`order_num`,`path`,`component`,`is_frame`,`is_cache`,`menu_type`,`visible`,`status`,`perms`,`icon`,`create_by`,`create_time`,`remark`) VALUES
(5071,'设备新增',4301,1,'','','1','0','F','0','0','ems:device:add','#','admin',NOW(),''),
(5072,'设备修改',4301,2,'','','1','0','F','0','0','ems:device:edit','#','admin',NOW(),''),
(5073,'设备删除',4301,3,'','','1','0','F','0','0','ems:device:remove','#','admin',NOW(),'');

UPDATE `sys_tenant_package` SET `menu_ids` = CONCAT_WS(',', `menu_ids`, '4300')
WHERE `id` = 103 AND FIND_IN_SET('4300', `menu_ids`) = 0;
UPDATE `sys_tenant_package` SET `menu_ids` = CONCAT_WS(',', `menu_ids`, '4301')
WHERE `id` = 103 AND FIND_IN_SET('4301', `menu_ids`) = 0;
UPDATE `sys_tenant_package` SET `menu_ids` = CONCAT_WS(',', `menu_ids`, '5071')
WHERE `id` = 103 AND FIND_IN_SET('5071', `menu_ids`) = 0;
UPDATE `sys_tenant_package` SET `menu_ids` = CONCAT_WS(',', `menu_ids`, '5072')
WHERE `id` = 103 AND FIND_IN_SET('5072', `menu_ids`) = 0;
UPDATE `sys_tenant_package` SET `menu_ids` = CONCAT_WS(',', `menu_ids`, '5073')
WHERE `id` = 103 AND FIND_IN_SET('5073', `menu_ids`) = 0;
