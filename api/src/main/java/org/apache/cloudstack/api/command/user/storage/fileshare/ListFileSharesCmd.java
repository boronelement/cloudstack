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
package org.apache.cloudstack.api.command.user.storage.fileshare;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListRetrieveOnlyResourceCountCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.FileShareResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.storage.fileshare.FileShare;
import org.apache.cloudstack.storage.fileshare.FileShareService;

import javax.inject.Inject;

@APICommand(name = "listFileShares",
        responseObject= FileShareResponse.class,
        description = "List File Shares",
        responseView = ResponseObject.ResponseView.Restricted,
        entityType = FileShare.class,
        requestHasSensitiveInfo = false,
        since = "4.20.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListFileSharesCmd extends BaseListRetrieveOnlyResourceCountCmd implements UserCmd {

    @Inject
    FileShareService fileShareService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = FileShareResponse.class, description = "the ID of the file share")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "the name of the file share")
    private String name;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, description = "the ID of the availability zone")
    private Long zoneId;

    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class, description = "the ID of the network")
    private Long networkId;

    @Parameter(name = ApiConstants.DISK_OFFERING_ID, type = CommandType.UUID, entityType = DiskOfferingResponse.class, description = "the disk offering of the file share")
    private Long diskOfferingId;

    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, description = "the service offering of the file share")
    private Long serviceOfferingId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public Long getDiskOfferingId() {
        return diskOfferingId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public void execute() {
        ListResponse<FileShareResponse> response = fileShareService.searchForFileShares(getResponseView(), this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
