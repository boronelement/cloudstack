//
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
//

package com.cloud.agent.api;

import java.util.List;

import org.apache.cloudstack.storage.to.VolumeObjectTO;

public class MigrateWithStorageAnswer extends Answer {

    final List<VolumeObjectTO> volumeTos;
    boolean aborted = false;

    public MigrateWithStorageAnswer(MigrateWithStorageCommand cmd, boolean result, boolean aborted, Exception ex) {
        super(cmd, result, ex.toString());
        this.aborted = aborted;
        volumeTos = null;
    }

    public MigrateWithStorageAnswer(MigrateWithStorageCommand cmd, List<VolumeObjectTO> volumeTos, boolean aborted, String details) {
        super(cmd, !aborted, details);
        this.volumeTos = volumeTos;
        this.aborted = aborted;
    }

    public List<VolumeObjectTO> getVolumeTos() {
        return volumeTos;
    }

    public boolean isAborted() {
        return aborted;
    }
}
