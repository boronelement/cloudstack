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
-- Schema upgrade from 4.17.1.0 to 4.18.0.0
--;


ALTER TABLE `cloud`.`networks` ADD COLUMN `public_iface_mtu` bigint unsigned comment "MTU for VR public interface" ;
ALTER TABLE `cloud`.`networks` ADD COLUMN `private_iface_mtu` bigint unsigned comment "MTU for VR private interfaces" ;
ALTER TABLE `cloud`.`vpc` ADD COLUMN `public_mtu` bigint unsigned comment "MTU for VPC VR public interface" ;
ALTER TABLE `cloud`.`nics` ADD COLUMN `mtu` bigint unsigned comment "MTU for the VR interface" ;