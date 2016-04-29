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

package org.apache.cloudstack.api.command.admin.acl;

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiArgValidator;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.RolePermissionResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = UpdateRolePermissionCmd.APINAME, description = "Updates a role permission order", responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        since = "4.9.0",
        authorized = {RoleType.Admin})
public class UpdateRolePermissionCmd extends BaseCmd {
    public static final String APINAME = "updateRolePermission";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, required = true, entityType = RolePermissionResponse.class,
            description = "ID of the role permission", validations = {ApiArgValidator.PositiveNumber})
    private Long ruleId;

    @Parameter(name = ApiConstants.PARENT, type = CommandType.STRING, required = true, entityType = RolePermissionResponse.class,
            description = "The parent role permission uuid, use 0 to move this rule at the top of the list", validations = {ApiArgValidator.NotNullOrEmpty})
    private String parentRuleUuid;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getRuleId() {
        return ruleId;
    }

    public RolePermission getParentRolePermission() {
        // A null or 0 previous id means this rule is moved to the top of the list
        if (parentRuleUuid.equals("0")) {
            return null;
        }
        final RolePermission parentRolePermission = roleService.findRolePermissionByUuid(parentRuleUuid);
        if (parentRolePermission == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Invalid parent role permission id provided");
        }
        return parentRolePermission;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + BaseCmd.RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        final RolePermission rolePermission = roleService.findRolePermission(getRuleId());
        if (rolePermission == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Invalid role permission id provided");
        }
        CallContext.current().setEventDetails("Role permission id: " + rolePermission.getId() + ", parent role permission uuid:" + parentRuleUuid);
        boolean result = roleService.updateRolePermission(rolePermission, getParentRolePermission());
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(result);
        setResponseObject(response);
    }
}