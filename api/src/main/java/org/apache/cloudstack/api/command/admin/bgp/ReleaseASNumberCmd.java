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
package org.apache.cloudstack.api.command.admin.bgp;

import com.cloud.dc.BGPService;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

import javax.inject.Inject;

@APICommand(name = "releaseASNumber", description = "Releases an AS Number back to the pool",
        responseObject = SuccessResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ReleaseASNumberCmd extends BaseCmd {

    @Parameter(name = ApiConstants.ZONE_ID, type = BaseCmd.CommandType.UUID, entityType = ZoneResponse.class,
            description = "the zone ID", required = true)
    private Long zoneId;

    @Parameter(name= ApiConstants.AS_NUMBER, type=CommandType.LONG, description="the AS Number to be released",
            required = true)
    private Long asNumber;

    @Inject
    private BGPService bgpService;

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {
        try {
            Pair<Boolean, String> resultPair = bgpService.releaseASNumber(zoneId, asNumber);
            Boolean result = resultPair.first();
            if (!result) {
                String details = resultPair.second();
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Cannot release AS Number %s: %s", asNumber, details));
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            response.setDisplayText(String.format("AS Number %s is released successfully", asNumber));
            setResponseObject(response);
        } catch (Exception e) {
            String msg = String.format("Error releasing AS Number %s: %s", asNumber, e.getMessage());
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, msg);
        }
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
