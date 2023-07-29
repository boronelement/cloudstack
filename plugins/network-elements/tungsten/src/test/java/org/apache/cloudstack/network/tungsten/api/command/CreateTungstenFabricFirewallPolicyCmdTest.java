// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.network.tungsten.api.command;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.network.tungsten.api.response.TungstenFabricFirewallPolicyResponse;
import org.apache.cloudstack.network.tungsten.service.TungstenService;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

public class CreateTungstenFabricFirewallPolicyCmdTest {

    @Mock
    TungstenService tungstenService;

    CreateTungstenFabricFirewallPolicyCmd createTungstenFabricFirewallPolicyCmd;

    AutoCloseable closeable;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        createTungstenFabricFirewallPolicyCmd = new CreateTungstenFabricFirewallPolicyCmd();
        createTungstenFabricFirewallPolicyCmd.tungstenService = tungstenService;
        ReflectionTestUtils.setField(createTungstenFabricFirewallPolicyCmd, "zoneId", 1L);
        ReflectionTestUtils.setField(createTungstenFabricFirewallPolicyCmd, "applicationPolicySetUuid", "test");
        ReflectionTestUtils.setField(createTungstenFabricFirewallPolicyCmd, "name", "test");
        ReflectionTestUtils.setField(createTungstenFabricFirewallPolicyCmd, "sequence", 1);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    public void executeTest() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException,
            ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {
        TungstenFabricFirewallPolicyResponse tungstenFabricFirewallPolicyResponse =
                Mockito.mock(TungstenFabricFirewallPolicyResponse.class);
        Mockito.when(tungstenService.createTungstenFirewallPolicy(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyInt())).thenReturn(tungstenFabricFirewallPolicyResponse);
        createTungstenFabricFirewallPolicyCmd.execute();
        Assert.assertEquals(tungstenFabricFirewallPolicyResponse,
                createTungstenFabricFirewallPolicyCmd.getResponseObject());
    }
}
