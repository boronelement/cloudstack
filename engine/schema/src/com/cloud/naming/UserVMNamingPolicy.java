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
package com.cloud.naming;

import com.cloud.uservm.UserVm;
import com.cloud.vm.UserVmVO;

public interface UserVMNamingPolicy extends ResourceNamingPolicy<UserVm, UserVmVO>{

    public String getHostName(Long resourceId, Long accountId, String uuid);

    public String getInstanceName(Long resourceId, Long accountId, String uuidName);

    public String getServiceVmName(Long resourceId, Long accountId);
}
