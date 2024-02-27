-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- cloud.security_group_view source


DROP VIEW IF EXISTS `cloud`.`security_group_view`;

CREATE VIEW `cloud`.`security_group_view` AS
select
    `security_group`.`id` AS `id`,
    `security_group`.`name` AS `name`,
    `security_group`.`description` AS `description`,
    `security_group`.`uuid` AS `uuid`,
    `account`.`id` AS `account_id`,
    `account`.`uuid` AS `account_uuid`,
    `account`.`account_name` AS `account_name`,
    `account`.`type` AS `account_type`,
    `domain`.`id` AS `domain_id`,
    `domain`.`uuid` AS `domain_uuid`,
    `domain`.`name` AS `domain_name`,
    `domain`.`path` AS `domain_path`,
    `projects`.`id` AS `project_id`,
    `projects`.`uuid` AS `project_uuid`,
    `projects`.`name` AS `project_name`,
    `security_group_rule`.`id` AS `rule_id`,
    `security_group_rule`.`uuid` AS `rule_uuid`,
    `security_group_rule`.`type` AS `rule_type`,
    `security_group_rule`.`start_port` AS `rule_start_port`,
    `security_group_rule`.`end_port` AS `rule_end_port`,
    `security_group_rule`.`protocol` AS `rule_protocol`,
    `security_group_rule`.`allowed_network_id` AS `rule_allowed_network_id`,
    `security_group_rule`.`allowed_ip_cidr` AS `rule_allowed_ip_cidr`,
    `security_group_rule`.`create_status` AS `rule_create_status`,
    `resource_tags`.`id` AS `tag_id`,
    `resource_tags`.`uuid` AS `tag_uuid`,
    `resource_tags`.`key` AS `tag_key`,
    `resource_tags`.`value` AS `tag_value`,
    `resource_tags`.`domain_id` AS `tag_domain_id`,
    `resource_tags`.`account_id` AS `tag_account_id`,
    `resource_tags`.`resource_id` AS `tag_resource_id`,
    `resource_tags`.`resource_uuid` AS `tag_resource_uuid`,
    `resource_tags`.`resource_type` AS `tag_resource_type`,
    `resource_tags`.`customer` AS `tag_customer`,
    `async_job`.`id` AS `job_id`,
    `async_job`.`uuid` AS `job_uuid`,
    `async_job`.`job_status` AS `job_status`,
    `async_job`.`account_id` AS `job_account_id`
from
    ((((((`security_group`
left join `security_group_rule` on
    ((`security_group`.`id` = `security_group_rule`.`security_group_id`)))
join `account` on
    ((`security_group`.`account_id` = `account`.`id`)))
join `domain` on
    ((`security_group`.`domain_id` = `domain`.`id`)))
left join `projects` on
    ((`projects`.`project_account_id` = `security_group`.`account_id`)))
left join `resource_tags` on
    (((`resource_tags`.`resource_id` = `security_group`.`id`)
        and (`resource_tags`.`resource_type` = 'SecurityGroup'))))
left join `async_job` on
    (((`async_job`.`instance_id` = `security_group`.`id`)
        and (`async_job`.`instance_type` = 'SecurityGroup')
            and (`async_job`.`job_status` = 0))));
