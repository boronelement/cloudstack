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

public class DeleteBcfStaticNatCommand extends BcfCommand {
    private final String _tenantId;
    private final String _publicIp;
    private final String _floatingIpId;

    public DeleteBcfStaticNatCommand(String tenantId, String publicIp){
        this._tenantId = tenantId;
        this._publicIp = publicIp;
        this._floatingIpId = publicIp.replace(".", "-");
    }

    public String get_tenantId() {
        return _tenantId;
    }

    public String get_publicIp() {
        return _publicIp;
    }

    public String get_floatingIpId() {
        return _floatingIpId;
    }
}
