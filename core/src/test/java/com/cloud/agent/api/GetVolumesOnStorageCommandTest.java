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
package com.cloud.agent.api;

import com.cloud.agent.api.to.StorageFilerTO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class GetVolumesOnStorageCommandTest {

    final StorageFilerTO pool = Mockito.mock(StorageFilerTO.class);

    final String localPath = "localPath";
    final String volumePath = "volumePath";
    final String keyword = "keyword";

    @Test
    public void testGetVolumesOnStorageCommand() {
        GetVolumesOnStorageCommand command = new GetVolumesOnStorageCommand(pool, volumePath, keyword);

        Assert.assertEquals(pool, command.getPool());
        Assert.assertEquals(volumePath, command.getVolumePath());
        Assert.assertEquals(keyword, command.getKeyword());
        Assert.assertFalse(command.executeInSequence());
    }
}
