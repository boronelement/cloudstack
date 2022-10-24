# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""
Tests of Non-Strict (host anti-affinity and host affinity) affinity groups
"""

import logging

from nose.plugins.attrib import attr
from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import startVirtualMachine, stopVirtualMachine

from marvin.lib.base import (Account,
                             AffinityGroup,
                             Domain,
                             Host,
                             ServiceOffering,
                             VirtualMachine,
                             Zone,
                             Network,
                             NetworkOffering)

from marvin.lib.common import (get_domain,
                               get_zone,
                               get_template)


class TestNonStrictAffinityGroups(cloudstackTestCase):
    """
    Test Non-Strict (host anti-affinity and host affinity) affinity groups
    """
    @classmethod
    def setUpClass(cls):
        cls.testClient = super(
            TestNonStrictAffinityGroups,
            cls).getClsTestClient()
        cls.apiclient = cls.testClient.getApiClient()
        cls.services = cls.testClient.getParsedTestDataConfig()

        zone = get_zone(cls.apiclient, cls.testClient.getZoneForTests())
        cls.zone = Zone(zone.__dict__)
        cls.template = get_template(cls.apiclient, cls.zone.id)
        cls._cleanup = []

        cls.logger = logging.getLogger("TestNonStrictAffinityGroups")
        cls.stream_handler = logging.StreamHandler()
        cls.logger.setLevel(logging.DEBUG)
        cls.logger.addHandler(cls.stream_handler)

        cls.skipTests = False
        hosts = Host.list(
            cls.apiclient,
            zoneid=cls.zone.id,
            state='Up',
            resourcestate='Enabled'
        )
        if not hosts or not isinstance(hosts, list) or len(hosts) < 2:
            cls.logger.debug("This test requires at least two (Up and Enabled) hosts in the zone")
            cls.skipTests = True
            return

        cls.domain = get_domain(cls.apiclient)

        # 1. Create small service offering
        cls.service_offering = ServiceOffering.create(
            cls.apiclient,
            cls.services["service_offerings"]["small"]
        )
        cls._cleanup.append(cls.service_offering)

        # 3. Create network offering for isolated networks
        cls.network_offering_isolated = NetworkOffering.create(
            cls.apiclient,
            cls.services["isolated_network_offering"]
        )
        cls.network_offering_isolated.update(cls.apiclient, state='Enabled')
        cls._cleanup.append(cls.network_offering_isolated)

        # 4. Create sub-domain
        cls.sub_domain = Domain.create(
            cls.apiclient,
            cls.services["acl"]["domain1"]
        )
        cls._cleanup.append(cls.sub_domain)

        # 5. Create regular user
        cls.regular_user = Account.create(
            cls.apiclient,
            cls.services["acl"]["accountD11A"],
            domainid=cls.sub_domain.id
        )
        cls._cleanup.append(cls.regular_user)

        # 5. Create api clients for regular user
        cls.regular_user_user = cls.regular_user.user[0]
        cls.regular_user_apiclient = cls.testClient.getUserApiClient(
            cls.regular_user_user.username, cls.sub_domain.name
        )

        # 7. Create network for regular user
        cls.services["network"]["name"] = "Test Network Isolated - Regular user"
        cls.user_network = Network.create(
            cls.regular_user_apiclient,
            cls.services["network"],
            networkofferingid=cls.network_offering_isolated.id,
            zoneid=cls.zone.id
        )

    @classmethod
    def tearDownClass(cls):
        super(TestNonStrictAffinityGroups, cls).tearDownClass()

    def setUp(self):
        if self.skipTests:
            self.skipTest("This test requires at least two (Up and Enabled) hosts in the zone")
        self.apiclient = self.testClient.getApiClient()
        self.cleanup = []

    def tearDown(self):
        super(TestNonStrictAffinityGroups, self).tearDown()

    @classmethod
    def get_vm_host_id(cls, vm_id):
        list_vms = VirtualMachine.list(
            cls.apiclient,
            id=vm_id
        )
        vm = list_vms[0]
        return vm.hostid

    @attr(tags=["advanced"], required_hardware="false")
    def test_01_non_strict_host_anti_affinity(self):
        """ Verify Non-Strict host anti-affinity """

        # 1. Create Non-Strict host anti-affinity
        # 2. Deploy vm-1 with the group
        # 3. Deploy vm-2 with the group. It will be started on different host if there are multiple hosts.
        # 4. Migrate vm-2 to same host as vm-1
        # 5. Stop vm-2, start vm-2. It will be started on same host as vm-1
        # 6. Stop vm-2, start vm-2 with forgetlasthost=true.  It will be started on different host as vm-1

        self.logger.debug("=== Running test_01_non_strict_host_anti_affinity ===")

        # 1. Create Non-Strict host anti-affinity
        affinity_group_params = {
            "name": "Test affinity group",
            "type": "non-strict host anti-affinity",
        }
        self.affinity_group = AffinityGroup.create(
            self.regular_user_apiclient,
            affinity_group_params
        )
        self.cleanup.append(self.affinity_group)

        # 2. Deploy vm-1 with the group
        self.services["virtual_machine"]["name"] = "virtual-machine-1"
        self.services["virtual_machine"]["displayname"] = "virtual-machine-1"
        self.virtual_machine_1 = VirtualMachine.create(
            self.regular_user_apiclient,
            self.services["virtual_machine"],
            serviceofferingid=self.service_offering.id,
            templateid=self.template.id,
            zoneid=self.zone.id,
            networkids=self.user_network.id,
            affinitygroupids=self.affinity_group.id
        )
        self.cleanup.append(self.virtual_machine_1)
        vm_1_host_id = self.get_vm_host_id(self.virtual_machine_1.id)

        # 3. Deploy vm-2 with the group. It will be started on different host if there are multiple hosts.
        self.services["virtual_machine"]["name"] = "virtual-machine-2"
        self.services["virtual_machine"]["displayname"] = "virtual-machine-2"
        self.virtual_machine_2 = VirtualMachine.create(
            self.regular_user_apiclient,
            self.services["virtual_machine"],
            serviceofferingid=self.service_offering.id,
            templateid=self.template.id,
            zoneid=self.zone.id,
            networkids=self.user_network.id,
            affinitygroupids=self.affinity_group.id
        )
        self.cleanup.append(self.virtual_machine_2)
        vm_2_host_id = self.get_vm_host_id(self.virtual_machine_2.id)

        self.assertNotEqual(vm_1_host_id,
                            vm_2_host_id,
                            msg="Both VMs of affinity group %s are on the same host" % self.affinity_group.name)

        # 4. Migrate vm-2 to same host as vm-1
        self.virtual_machine_2.migrate(
            self.apiclient,
            hostid=vm_1_host_id
        )

        # 5. Stop vm-2, start vm-2. It will be started on same host as vm-1
        cmd = stopVirtualMachine.stopVirtualMachineCmd()
        cmd.id = self.virtual_machine_2.id
        cmd.forced = True
        self.apiclient.stopVirtualMachine(cmd)

        cmd = startVirtualMachine.startVirtualMachineCmd()
        cmd.id = self.virtual_machine_2.id
        self.apiclient.startVirtualMachine(cmd)

        vm_2_host_id = self.get_vm_host_id(self.virtual_machine_2.id)

        self.assertEqual(vm_1_host_id,
                         vm_2_host_id,
                         msg="Both VMs of affinity group %s are on the different host" % self.affinity_group.name)

        # 6. Stop vm-2, start vm-2 with forgetlasthost=true.  It will be started on different host as vm-1
        cmd = stopVirtualMachine.stopVirtualMachineCmd()
        cmd.id = self.virtual_machine_2.id
        cmd.forced = True
        self.apiclient.stopVirtualMachine(cmd)

        cmd = startVirtualMachine.startVirtualMachineCmd()
        cmd.id = self.virtual_machine_2.id
        cmd.forgetlasthost = True
        self.apiclient.startVirtualMachine(cmd)

        vm_2_host_id = self.get_vm_host_id(self.virtual_machine_2.id)

        self.assertNotEqual(vm_1_host_id,
                            vm_2_host_id,
                            msg="Both VMs of affinity group %s are on the same host" % self.affinity_group.name)

    @attr(tags=["advanced"], required_hardware="false")
    def test_02_non_strict_host_affinity(self):
        """ Verify Non-Strict host affinity """

        # 1. Create Non-Strict host affinity
        # 2. Deploy vm-3 with the group
        # 3. Deploy vm-4 with the group. It will be started on same host.
        # 4. Migrate vm-4 to different host as vm-3
        # 5. Stop vm-4, start vm-4. It will be started on different host as vm-3
        # 6. Stop vm-4, start vm-4 with forgetlasthost=true.  It will be started on same host as vm-3

        self.logger.debug("=== Running test_02_non_strict_host_affinity ===")

        # 1. Create Non-Strict host affinity
        affinity_group_params = {
            "name": "Test affinity group",
            "type": "non-strict host affinity",
        }
        self.affinity_group = AffinityGroup.create(
            self.regular_user_apiclient,
            affinity_group_params
        )
        self.cleanup.append(self.affinity_group)

        # 2. Deploy vm-3 with the group
        self.services["virtual_machine"]["name"] = "virtual-machine-3"
        self.services["virtual_machine"]["displayname"] = "virtual-machine-3"
        self.virtual_machine_3 = VirtualMachine.create(
            self.regular_user_apiclient,
            self.services["virtual_machine"],
            serviceofferingid=self.service_offering.id,
            templateid=self.template.id,
            zoneid=self.zone.id,
            networkids=self.user_network.id,
            affinitygroupids=self.affinity_group.id
        )
        self.cleanup.append(self.virtual_machine_3)
        vm_3_host_id = self.get_vm_host_id(self.virtual_machine_3.id)

        # 3. Deploy vm-4 with the group. It will be started on same host.
        self.services["virtual_machine"]["name"] = "virtual-machine-4"
        self.services["virtual_machine"]["displayname"] = "virtual-machine-4"
        self.virtual_machine_4 = VirtualMachine.create(
            self.regular_user_apiclient,
            self.services["virtual_machine"],
            serviceofferingid=self.service_offering.id,
            templateid=self.template.id,
            zoneid=self.zone.id,
            networkids=self.user_network.id,
            affinitygroupids=self.affinity_group.id
        )
        self.cleanup.append(self.virtual_machine_4)
        vm_4_host_id = self.get_vm_host_id(self.virtual_machine_4.id)

        self.assertEqual(vm_3_host_id,
                         vm_4_host_id,
                         msg="Both VMs of affinity group %s are on the different host" % self.affinity_group.name)

        # 4. Migrate vm-4 to different host as vm-3
        self.virtual_machine_4.migrate(
            self.apiclient
        )

        # 5. Stop vm-4, start vm-4. It will be started on different host as vm-3
        cmd = stopVirtualMachine.stopVirtualMachineCmd()
        cmd.id = self.virtual_machine_4.id
        cmd.forced = True
        self.apiclient.stopVirtualMachine(cmd)

        cmd = startVirtualMachine.startVirtualMachineCmd()
        cmd.id = self.virtual_machine_4.id
        self.apiclient.startVirtualMachine(cmd)

        vm_4_host_id = self.get_vm_host_id(self.virtual_machine_4.id)

        self.assertNotEqual(vm_3_host_id,
                            vm_4_host_id,
                            msg="Both VMs of affinity group %s are on the same host" % self.affinity_group.name)

        # 6. Stop vm-4, start vm-4 with forgetlasthost=true.  It will be started on same host as vm-3
        cmd = stopVirtualMachine.stopVirtualMachineCmd()
        cmd.id = self.virtual_machine_4.id
        cmd.forced = True
        self.apiclient.stopVirtualMachine(cmd)

        cmd = startVirtualMachine.startVirtualMachineCmd()
        cmd.id = self.virtual_machine_4.id
        cmd.forgetlasthost = True
        self.apiclient.startVirtualMachine(cmd)

        vm_4_host_id = self.get_vm_host_id(self.virtual_machine_4.id)

        self.assertEqual(vm_3_host_id,
                         vm_4_host_id,
                         msg="Both VMs of affinity group %s are on the different host" % self.affinity_group.name)
