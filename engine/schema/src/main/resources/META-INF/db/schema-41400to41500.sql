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

--;
-- Schema upgrade from 4.14.0.0 to 4.15.0.0
--;

-- Project roles
CREATE TABLE IF NOT EXISTS `cloud`.`project_role` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(255) UNIQUE,
  `name` varchar(255) COMMENT 'unique name of the dynamic project role',
  `removed` datetime COMMENT 'date removed',
  `description` text COMMENT 'description of the project role',
  `project_id` bigint(20) unsigned COMMENT 'Id of the project to which the role belongs',
  PRIMARY KEY (`id`),
  KEY `i_project_role__name` (`name`),
  UNIQUE KEY (`name`),
  CONSTRAINT `fk_project_role__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Project role permissions table
CREATE TABLE IF NOT EXISTS `cloud`.`project_role_permissions` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(255) UNIQUE,
  `project_id` bigint(20) unsigned NOT NULL COMMENT 'id of the role',
  `project_role_id` bigint(20) unsigned NOT NULL COMMENT 'id of the role',
  `rule` varchar(255) NOT NULL COMMENT 'rule for the role, api name or wildcard',
  `permission` varchar(255) NOT NULL COMMENT 'access authority, allow or deny',
  `description` text COMMENT 'description of the rule',
  `sort_order` bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT 'permission sort order',
  PRIMARY KEY (`id`),
  KEY `fk_project_role_permissions__project_role_id` (`project_role_id`),
  KEY `i_project_role_permissions__sort_order` (`sort_order`),
  UNIQUE KEY (`project_role_id`, `rule`),
  CONSTRAINT `fk_project_role_permissions__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_role_permissions__project_role_id` FOREIGN KEY (`project_role_id`) REFERENCES `project_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Alter project accounts table to include user_id and project_role_id for role based users in projects
DROP TABLE IF EXISTS `cloud`.`project_account`;
CREATE TABLE  `cloud`.`project_account` (
  `id` bigint unsigned NOT NULL auto_increment,
  `account_id` bigint unsigned NOT NULL COMMENT'account id',
  `user_id` bigint unsigned COMMENT 'ID of user to be added to the project',
  `account_role` varchar(255) NOT NULL DEFAULT 'Regular' COMMENT 'Account role in the project (Owner or Regular)',
  `project_id` bigint unsigned NOT NULL COMMENT 'project id',
  `project_account_id` bigint unsigned NOT NULL,
  `project_role_id` bigint unsigned COMMENT 'Project role id',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_project_account__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__user_id` FOREIGN KEY(`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__project_account_id` FOREIGN KEY(`project_account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__project_role_id` FOREIGN KEY (`project_role_id`) REFERENCES `project_role` (`id`),
  UNIQUE (`account_id`, `user_id`, `project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Alter project invitations table to include user_id for invites sent to specific users of an account
DROP TABLE IF EXISTS `cloud`.`project_invitations`;
CREATE TABLE  `cloud`.`project_invitations` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `project_id` bigint unsigned NOT NULL COMMENT 'project id',
  `account_id` bigint unsigned COMMENT 'account id',
  `user_id` bigint unsigned COMMENT 'ID of user to be added to the project',
  `domain_id` bigint unsigned COMMENT 'domain id',
  `account_role` varchar(255) NOT NULL DEFAULT 'Regular' COMMENT 'Account role in the project (Owner or Regular)',
  `email` varchar(255) COMMENT 'email',
  `token` varchar(255) COMMENT 'token',
  `state` varchar(255) NOT NULL DEFAULT 'Pending' COMMENT 'the state of the invitation',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_project_invitations__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_invitations__user_id` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_invitations__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_invitations__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE,
  UNIQUE (`project_id`, `account_id`,`user_id`),
  UNIQUE (`project_id`, `email`),
  UNIQUE (`project_id`, `token`),
  CONSTRAINT `uc_project_invitations__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Alter project_invitation_view to incroporate user_id as a field
ALTER VIEW `cloud`.`project_invitation_view` AS
    select
        project_invitations.id,
        project_invitations.uuid,
        project_invitations.email,
        project_invitations.created,
        project_invitations.state,
        projects.id project_id,
        projects.uuid project_uuid,
        projects.name project_name,
        account.id account_id,
        account.uuid account_uuid,
        account.account_name,
        account.type account_type,
        user.id user_id,
        domain.id domain_id,
        domain.uuid domain_uuid,
        domain.name domain_name,
        domain.path domain_path
    from
        `cloud`.`project_invitations`
            left join
        `cloud`.`account` ON project_invitations.account_id = account.id
            left join
        `cloud`.`domain` ON project_invitations.domain_id = domain.id
            left join
        `cloud`.`projects` ON projects.id = project_invitations.project_id
            left join
        `cloud`.`user` ON project_invitations.user_id = user.id;

-- Alter project_account_view to incorporate user id
ALTER VIEW `cloud`.`project_account_view` AS
    select
        project_account.id,
        account.id account_id,
        account.uuid account_uuid,
        account.account_name,
        account.type account_type,
        user.id user_id,
        user.uuid user_uuid,
        user.username user_name,
        project_account.account_role,
        project_role.id project_role_id,
        project_role.uuid project_role_uuid,
        projects.id project_id,
        projects.uuid project_uuid,
        projects.name project_name,
        domain.id domain_id,
        domain.uuid domain_uuid,
        domain.name domain_name,
        domain.path domain_path
    from
        `cloud`.`project_account`
            inner join
        `cloud`.`account` ON project_account.account_id = account.id
            inner join
        `cloud`.`domain` ON account.domain_id = domain.id
            inner join
        `cloud`.`projects` ON projects.id = project_account.project_id
            left join
        `cloud`.`project_role` ON project_account.project_role_id = project_role.id
            left join
        `cloud`.`user` ON (project_account.user_id = user.id);

-- Fix Debian 10 32-bit hypervisor mappings on VMware, debian10-32bit OS ID in guest_os table is 292, not 282
UPDATE `cloud`.`guest_os_hypervisor` SET guest_os_id=292 WHERE guest_os_id=282 AND hypervisor_type="VMware" AND guest_os_name="debian10Guest";
-- Fix CentOS 32-bit mapping for VMware 5.5 which does not have a centos6Guest but only centosGuest and centos64Guest
UPDATE `cloud`.`guest_os_hypervisor` SET guest_os_name='centosGuest' where hypervisor_type="VMware" and hypervisor_version="5.5" and guest_os_name="centos6Guest";

ALTER TABLE `cloud`.`roles` ADD COLUMN `is_default` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'is this a default role';
UPDATE `cloud`.`roles` SET `is_default` = 1 WHERE id IN (1, 2, 3, 4);

-- Updated Default CloudStack roles with read-only and support admin and user roles
INSERT INTO `cloud`.`roles` (`uuid`, `name`, `role_type`, `description`, `is_default`) VALUES (UUID(), 'Read-Only Admin - Default', 'Admin', 'Default read-only admin role', 1);
INSERT INTO `cloud`.`roles` (`uuid`, `name`, `role_type`, `description`, `is_default`) VALUES (UUID(), 'Read-Only User - Default', 'User', 'Default read-only user role', 1);
INSERT INTO `cloud`.`roles` (`uuid`, `name`, `role_type`, `description`, `is_default`) VALUES (UUID(), 'Support Admin - Default', 'Admin', 'Default support admin role', 1);
INSERT INTO `cloud`.`roles` (`uuid`, `name`, `role_type`, `description`, `is_default`) VALUES (UUID(), 'Support User - Default', 'User', 'Default support user role', 1);