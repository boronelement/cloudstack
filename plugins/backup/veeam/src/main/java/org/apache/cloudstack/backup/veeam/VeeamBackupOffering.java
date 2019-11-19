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

package org.apache.cloudstack.backup.veeam;

import org.apache.cloudstack.backup.BackupOffering;

public class VeeamBackupOffering implements BackupOffering {

    private String name;
    private String uid;

    public VeeamBackupOffering(String name, String uid) {
        this.name = name;
        this.uid = uid;
    }

    @Override
    public String getExternalId() {
        return uid;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "Veeam Backup Offering (Job)";
    }

    @Override
    public boolean isImported() {
        return false;
    }

    @Override
    public long getZoneId() {
        return -1;
    }

    @Override
    public String getUuid() {
        return uid;
    }

    @Override
    public long getId() {
        return -1;
    }
}
