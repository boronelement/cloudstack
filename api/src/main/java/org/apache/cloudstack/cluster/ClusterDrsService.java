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

package org.apache.cloudstack.cluster;

import com.cloud.utils.component.Manager;
import com.cloud.utils.concurrency.Scheduler;
import org.apache.cloudstack.api.command.admin.cluster.ExecuteClusterDrsPlanCmd;
import org.apache.cloudstack.api.command.admin.cluster.GenerateClusterDrsPlanCmd;
import org.apache.cloudstack.api.command.admin.cluster.ListClusterDrsPlanCmd;
import org.apache.cloudstack.api.response.ClusterDrsPlanResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

public interface ClusterDrsService extends Manager, Configurable, Scheduler {

    ConfigKey<Integer> ClusterDrsEventsExpireInterval = new ConfigKey<>(Integer.class, "drs.events.expire.interval", ConfigKey.CATEGORY_ADVANCED, "30", "The interval in days after which the DRS events will be cleaned up.", false, ConfigKey.Scope.Global, null);

    ConfigKey<Boolean> ClusterDrsEnabled = new ConfigKey<>(Boolean.class, "drs.automatic.enable", ConfigKey.CATEGORY_ADVANCED, "false", "Enable/disable automatic DRS on a cluster.", true, ConfigKey.Scope.Cluster, null);

    ConfigKey<Integer> ClusterDrsInterval = new ConfigKey<>(Integer.class, "drs.automatic.interval", ConfigKey.CATEGORY_ADVANCED, "60", "The interval in minutes after which a periodic background thread will schedule DRS for a cluster.", true, ConfigKey.Scope.Cluster, null);

    ConfigKey<Double> ClusterDrsIterations = new ConfigKey<>(Double.class, "drs.iterations", ConfigKey.CATEGORY_ADVANCED, "0.2", "The maximum number of iterations in a DRS job defined as a percentage (as a value between 0 and 1) of total number of workloads. The minimum number of iterations per round will be 1 and maximum will be equal to the total number of workloads in a cluster.", true, ConfigKey.Scope.Cluster, null);

    // TODO: Check if we can populate available algorithms as part of the description
    ConfigKey<String> ClusterDrsAlgorithm = new ConfigKey<>(String.class, "drs.algorithm", ConfigKey.CATEGORY_ADVANCED, "condensed", "DRS algorithm to execute on the cluster.", true, ConfigKey.Scope.Cluster, null);

    ConfigKey<Integer> ClusterDrsLevel = new ConfigKey<>(Integer.class, "drs.level", ConfigKey.CATEGORY_ADVANCED, "5", "The level of DRS to perform on cluster.", true, ConfigKey.Scope.Cluster, null, null, null, null, null, ConfigKey.Kind.Select, "1,2,3,4,5,6,7,8,9,10");

    ConfigKey<String> ClusterDrsMetric = new ConfigKey<>(String.class, "drs.imbalance.metric", ConfigKey.CATEGORY_ADVANCED, "cpu", "The cluster imbalance metric to use when checking the drs.imbalance.threshold. Possible values are memory, cpu, either (cpu OR memory crosses the threshold), both (cpu AND memory crosses the threshold).", true, ConfigKey.Scope.Cluster, null, null, null, null, null, ConfigKey.Kind.Select, "memory,cpu,either,both");

    /**
     * Executes the DRS (Distributed Resource Scheduler) command.
     *
     * @param cmd the GenerateClusterDrsPlanCmd object containing the command parameters
     * @return a SuccessResponse object indicating the success of the operation
     */
    ClusterDrsPlanResponse generateDrsPlan(GenerateClusterDrsPlanCmd cmd);

    boolean executeDrsPlan(ExecuteClusterDrsPlanCmd executeClusterDrsPlanCmd);

    ListResponse<ClusterDrsPlanResponse> listDrsPlan(ListClusterDrsPlanCmd listClusterDrsPlanCmd);
}
