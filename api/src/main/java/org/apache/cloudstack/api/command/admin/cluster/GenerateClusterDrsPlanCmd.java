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

package org.apache.cloudstack.api.command.admin.cluster;

import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ClusterDrsPlanResponse;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.cluster.ClusterDrsService;

import javax.inject.Inject;

import static org.apache.cloudstack.cluster.ClusterDrsService.ClusterDrsIterations;

@APICommand(name = "generateClusterDrsPlan", description = "Generate DRS plan for a cluster",
            responseObject = ClusterDrsPlanResponse.class, since = "4.19.0", requestHasSensitiveInfo = false,
            responseHasSensitiveInfo = false)
public class GenerateClusterDrsPlanCmd extends BaseCmd {

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = ClusterResponse.class, required = true,
               description = "the ID of the Cluster")
    private Long id;

    @Parameter(name = "iterations", type = CommandType.FLOAT,
               description = "The maximum number of VM migrations to perform for DRS. This is defined as a percentage" +
                       " (as a value between 0 and 1) of total number of workloads. Defaults to value of cluster's " +
                       "drs.iterations setting")
    private Float iterations;

    @Parameter(name = "saveplan", type = CommandType.BOOLEAN, entityType = ClusterResponse.class, description = "save" +
            " plan in the database")
    private Boolean savePlan;

    @Inject
    private ClusterDrsService clusterDrsService;

    public Float getIterations() {
        if (iterations == null) {
            return ClusterDrsIterations.valueIn(getId());
        }
        return iterations;
    }

    public Long getId() {
        return id;
    }

    @Override
    public void execute() {
        final ClusterDrsPlanResponse response = clusterDrsService.generateDrsPlan(this);
        if (!getSavePlan()) {
            response.setId(null);
        }
        response.setResponseName(getCommandName());
        response.setObjectName(getCommandName());
        this.setResponseObject(response);
    }

    public boolean getSavePlan() {
        return savePlan != null && savePlan;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public Long getApiResourceId() {
        return getId();
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Cluster;
    }
}
