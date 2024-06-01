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

import com.cloud.exception.ResourceAllocationException;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.FileShareResponse;
import org.apache.cloudstack.storage.fileshare.FileShare;

@APICommand(name = "createFileShare", responseObject= FileShareResponse.class, description = "Creates a new file share of specified size and disk offering and attached to the given guest network",
        responseView = ResponseObject.ResponseView.Restricted, entityType = FileShare.class, requestHasSensitiveInfo = false, since = "4.20.0")
public class CreateFileShareCmd extends BaseAsyncCreateCmd implements UserCmd {

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            required = true,
            description = "the name of the file share.")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION,
            type = CommandType.STRING,
            description = "the description for the file share.")
    private String description;

    @Parameter(name = ApiConstants.SIZE,
            type = CommandType.LONG,
            required = true,
            description = "the size of the file share in bytes")
    private Long size;

    @Parameter(name = ApiConstants.DISK_OFFERING_ID,
            type = CommandType.UUID,
            entityType = DiskOfferingResponse.class,
            description = "the disk offering to use for the underlying storage.")
    private Long diskOfferingId;

    @Parameter(name = ApiConstants.MOUNT_OPTIONS,
            type = CommandType.STRING,
            description = "the comma separated list of mount options to use for mounting this file share.")
    private String mountOptions;

    @Parameter(name = ApiConstants.FORMAT,
            type = CommandType.STRING,
            description = "the filesystem format which will be installed on the file share.")
    private String fsFormat;

    @Parameter(name = ApiConstants.PROVIDER,
            type = CommandType.STRING,
            description = "the provider to be used for the file share.")
    private String fileShareProviderName;

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return null;
    }

    @Override
    public String getEventDescription() {
        return null;
    }

    public void create() throws ResourceAllocationException {

    }

    @Override
    public void execute() {

    }
}
