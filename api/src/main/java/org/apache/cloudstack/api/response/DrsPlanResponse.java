/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.api.response;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.ResponseObject;

public class DrsPlanResponse extends BaseResponse {
//    @SerializedName("destinationhostid")
//    @Param(description = "POST url to upload the file to")
//    String destHostId;
//
//    @SerializedName("sourcehostid")
//    @Param(description = "POST url to upload the file to")
//    String srcHostId;
//
//    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
//    @Param(description = "POST url to upload the file to")
//    String vmId;

    @SerializedName(ApiConstants.VM)
    @Param(description = "POST url to upload the file to")
    ResponseObject vm;

    @SerializedName("sourcehost")
    @Param(description = "POST url to upload the file to")
    ResponseObject srcHost;

    @SerializedName("destinationhost")
    @Param(description = "POST url to upload the file to")
    ResponseObject destHost;


    public DrsPlanResponse(ResponseObject vm, ResponseObject srcHost, ResponseObject destHost) {
        this.vm = vm;
        this.srcHost = srcHost;
        this.destHost = destHost;
    }
}
