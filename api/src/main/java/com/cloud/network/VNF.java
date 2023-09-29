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
package com.cloud.network;

import org.apache.commons.lang3.StringUtils;

public interface VNF {

    enum AccessMethod {
        SSH_WITH_PASSWORD("ssh-password"),
        SSH_WITH_KEY("ssh-key"),
        HTTP("http"),
        HTTPS("https"),
        CONSOLE("console");

        String _method;

        AccessMethod(String method) {
            _method = method;
        }

        @Override
        public String toString() {
            return _method;
        }

        public static AccessMethod fromValue(String method) {
            if (StringUtils.isBlank(method)) {
                return null;
            } else {
                for (AccessMethod accessMethod : AccessMethod.values()) {
                    if (accessMethod.toString().equalsIgnoreCase(method)) {
                        return accessMethod;
                    }
                }
            }
            return null;
        }
    }

    enum AccessDetail {
        ACCESS_METHODS,
        USERNAME,
        PASSWORD,
        SSH_USER,
        SSH_PASSWORD,
        SSH_PORT,
        WEB_USER,
        WEB_PASSWORD,
        HTTP_PATH,
        HTTP_PORT,
        HTTPS_PATH,
        HTTPS_PORT
    }

    enum VnfDetail {
        ICON,
        VERSION,
        VENDOR,
        MAINTAINER
    }

    class VnfNic {
        int deviceId;
        String name;
        boolean required;
        String description;

        public VnfNic(int deviceId, String nicName, boolean required, String nicDescription) {
            this.deviceId = deviceId;
            this.name = nicName;
            this.required = required;
            this.description = nicDescription;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public String getName() {
            return name;
        }

        public boolean isRequired() {
            return required;
        }

        public String getDescription() {
            return description;
        }
    }
}
