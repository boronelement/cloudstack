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
// Automatically generated by addcopyright.py at 01/29/2013
package org.apache.cloudstack.api;

import com.cloud.baremetal.networkservice.BaremetalPxeManager;
import com.cloud.baremetal.networkservice.BaremetalPxeResponse;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.log4j.Logger;

import javax.inject.Inject;
import java.util.List;

@APICommand(name = "listBaremetalPxeServers", description = "list baremetal pxe server", responseObject = BaremetalPxeResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class ListBaremetalPxeServersCmd extends BaseListCmd {
    private static final Logger s_logger = Logger.getLogger(ListBaremetalPxeServersCmd.class);

    @Inject
    BaremetalPxeManager _pxeMgr;
    // ///////////////////////////////////////////////////
    // ////////////// API parameters /////////////////////
    // ///////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.LONG, description = "Pxe server device ID")
    private Long id;

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID,
            type = CommandType.UUID,
            entityType = PhysicalNetworkResponse.class,
            required = true,
            description = "the Physical Network ID")
    private Long physicalNetworkId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPhysicalNetworkId() {
        return physicalNetworkId;
    }

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException,
        ResourceAllocationException, NetworkRuleConflictException {
        try {
            ListResponse<BaremetalPxeResponse> response = new ListResponse<BaremetalPxeResponse>();
            List<BaremetalPxeResponse> pxeResponses = _pxeMgr.listPxeServers(this);
            response.setResponses(pxeResponses);
            response.setResponseName(getCommandName());
            response.setObjectName("baremetalpxeservers");
            this.setResponseObject(response);
        } catch (Exception e) {
            s_logger.debug("Exception happened while executing ListPingPxeServersCmd", e);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    }
