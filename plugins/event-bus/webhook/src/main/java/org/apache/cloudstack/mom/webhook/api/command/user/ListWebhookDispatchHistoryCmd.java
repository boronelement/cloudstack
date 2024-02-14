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

package org.apache.cloudstack.mom.webhook.api.command.user;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.mom.webhook.WebhookApiService;
import org.apache.cloudstack.mom.webhook.WebhookDispatch;
import org.apache.cloudstack.mom.webhook.api.response.WebhookDispatchResponse;
import org.apache.cloudstack.mom.webhook.api.response.WebhookRuleResponse;

@APICommand(name = "listWebhookDispatchHistory",
        description = "Lists Webhook dispatch history",
        responseObject = WebhookRuleResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        entityType = {WebhookDispatch.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User},
        since = "4.20.0")
public class ListWebhookDispatchHistoryCmd extends BaseListCmd {

    @Inject
    WebhookApiService webhookApiService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = BaseCmd.CommandType.UUID,
            entityType = WebhookDispatchResponse.class,
            description = "The ID of the Webhook dispatch")
    private Long id;

    @Parameter(name = ApiConstants.WEBHOOK_RULE_ID, type = BaseCmd.CommandType.UUID,
            entityType = WebhookRuleResponse.class,
            description = "The ID of the Webhook rule")
    private Long webhookRuleId;

    @Parameter(name = ApiConstants.MANAGEMENT_SERVER_ID, type = BaseCmd.CommandType.UUID,
            entityType = ManagementServerResponse.class,
            description = "The ID of the management server",
            authorized = {RoleType.Admin})
    private Long managementServerId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    public Long getWebhookRuleId() {
        return webhookRuleId;
    }

    public Long getManagementServerId() {
        return managementServerId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() throws ServerApiException {
        ListResponse<WebhookDispatchResponse> response = webhookApiService.listWebhookDispatchHistory(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
