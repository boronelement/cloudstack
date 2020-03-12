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
package org.apache.cloudstack.simple.drs;

import com.cloud.utils.component.PluggableService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import java.util.List;

public interface SimpleDRSManager extends PluggableService, Configurable {

    ConfigKey<String> SimpleDRSProvider = new ConfigKey<>("Advanced", String.class,
            "simple.drs.provider",
            "host",
            "The simple DRS provider plugin that is used for rebalancing cluster workload on resources", true);

    ConfigKey<String> SimpleDRSRebalancingAlgorithm = new ConfigKey<>("Advanced", String.class,
            "simple.drs.rebalancing.algorithm",
            "balanced",
            "The simple DRS rebalancing algorithm plugin that is used. Possible values: balanced, condensed", true);

    List<String> listProviderNames();
}
