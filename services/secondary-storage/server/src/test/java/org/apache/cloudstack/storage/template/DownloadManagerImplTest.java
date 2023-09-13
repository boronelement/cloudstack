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
package org.apache.cloudstack.storage.template;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DownloadManagerImplTest {

    @InjectMocks
    DownloadManagerImpl downloadManager = new DownloadManagerImpl();

    @Test
    public void testGetSnapshotInstallNameFromDownloadUrl() {
        String filename = UUID.randomUUID().toString();
        String url = "http://abc.com/xyz/somepath/" + filename;
        String name = downloadManager.getSnapshotInstallNameFromDownloadUrl(url);
        Assert.assertEquals(filename, name);
        filename = UUID.randomUUID().toString().replace("-", "");
        filename = filename + "/" + filename + ".ovf";
        url = "http://abc.com/xyz/" + filename;
        name = downloadManager.getSnapshotInstallNameFromDownloadUrl(url);
        Assert.assertEquals(filename, name);
    }
}
