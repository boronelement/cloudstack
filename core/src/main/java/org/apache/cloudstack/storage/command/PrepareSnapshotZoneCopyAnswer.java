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

package org.apache.cloudstack.storage.command;

import org.apache.cloudstack.storage.to.SnapshotObjectTO;

import com.cloud.agent.api.Answer;

public class PrepareSnapshotZoneCopyAnswer extends Answer {
    private SnapshotObjectTO snapshot;

    public PrepareSnapshotZoneCopyAnswer() {
        super(null);
    }

    public PrepareSnapshotZoneCopyAnswer(PrepareSnapshotZoneCopyCommand cmd, SnapshotObjectTO snapshot) {
        super(cmd);
        setSnapshot(snapshot);
    }

    public PrepareSnapshotZoneCopyAnswer(PrepareSnapshotZoneCopyCommand cmd, String errMsg) {
        super(null, false, errMsg);
    }

    public SnapshotObjectTO getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(SnapshotObjectTO snapshot) {
        this.snapshot = snapshot;
    }
}
