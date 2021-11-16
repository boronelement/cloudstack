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
package org.apache.cloudstack.response;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.ManagementServerResponse;

public class ManagementServerMetricsResponse extends ManagementServerResponse {

    @SerializedName(ApiConstants.AVAILABLE_PROCESSORS)
    @Param(description = "the number of processors available to the JVM")
    private int availableProcessors;

    @SerializedName(ApiConstants.AGENT_COUNT)
    @Param(description = "the number of agents this Management Server is responsible for")
    private int agentCount;

    @SerializedName(ApiConstants.SESSIONS)
    @Param(description = "the number of client sessions active on this Management Server")
    private long sessions;

    @SerializedName(ApiConstants.HEAP_MEMORY_USED)
    @Param(description = "the amount of memory used by this Management Server")
    private long heapMemoryUsed;

    @SerializedName(ApiConstants.HEAP_MEMORY_TOTAL)
    @Param(description = "the amount of memory allocated to this Management Server")
    private long heapMemoryTotal;

    @SerializedName(ApiConstants.THREADS_BLOCKED_COUNT)
    @Param(description = "The number of blocked threads")
    private int threadsBlockedCount;

    @SerializedName(ApiConstants.THREADS_DEAMON_COUNT)
    @Param(description = "The number of deamon threads")
    private int threadsDeamonCount;

    @SerializedName(ApiConstants.THREADS_RUNNABLE_COUNT)
    @Param(description = "The number of runnable threads")
    private int threadsRunnableCount;

    @SerializedName(ApiConstants.THREADS_TERMINATED_COUNT)
    @Param(description = "The number of terminated threads")
    private int threadsTerminatedCount;

    @SerializedName(ApiConstants.THREADS_TOTAL_COUNT)
    @Param(description = "The number of threads")
    private int threadsTotalCount;

    @SerializedName(ApiConstants.THREADS_WAITING_COUNT)
    @Param(description = "The number of waiting threads")
    private int threadsWaitingCount;

    @SerializedName(ApiConstants.SYSTEM_MEMORY_TOTAL)
    @Param(description = "Total system memory")
    private long systemMemoryTotal;

    @SerializedName(ApiConstants.SYSTEM_MEMORY_FREE)
    @Param(description = "Free system memory")
    private long systemMemoryFree;

    @SerializedName(ApiConstants.SYSTEM_MEMORY_USED)
    @Param(description = "Amount of memory used")
    private long systemMemoryUsed;

    @SerializedName(ApiConstants.SYSTEM_MEMORY_VIRTUALSIZE)
    @Param(description = "Virtual size of the fully loaded process")
    private long systemMemoryVirtualSize;

    public void setAvailableProcessors(int availableProcessors) {
        this.availableProcessors = availableProcessors;
    }

    public void setAgentCount(int agentCount) {
        this.agentCount = agentCount;
    }

    public void setSessions(long sessions) {
        this.sessions = sessions;
    }

    public void setHeapMemoryUsed(long heapMemoryUsed) {
        this.heapMemoryUsed = heapMemoryUsed;
    }

    public void setHeapMemoryTotal(long heapMemoryTotal) {
        this.heapMemoryTotal = heapMemoryTotal;
    }

    public void setThreadsBlockedCount(int threadsBlockedCount) {
        this.threadsBlockedCount = threadsBlockedCount;
    }

    public void setThreadsDeamonCount(int threadsDeamonCount) {
        this.threadsDeamonCount = threadsDeamonCount;
    }

    public void setThreadsRunnableCount(int threadsRunnableCount) {
        this.threadsRunnableCount = threadsRunnableCount;
    }

    public void setThreadsTerminatedCount(int threadsTerminatedCount) {
        this.threadsTerminatedCount = threadsTerminatedCount;
    }

    public void setThreadsTotalCount(int threadsTotalCount) {
        this.threadsTotalCount = threadsTotalCount;
    }

    public void setThreadsWaitingCount(int threadsWaitingCount) {
        this.threadsWaitingCount = threadsWaitingCount;
    }

    public void setSystemMemoryTotal(long systemMemoryTotal) {
        this.systemMemoryTotal = systemMemoryTotal;
    }

    public void setSystemMemoryFree(long systemMemoryFree) {
        this.systemMemoryFree = systemMemoryFree;
    }

    public void setSystemMemoryUsed(long systemMemoryUsed) {
        this.systemMemoryUsed = systemMemoryUsed;
    }

    public void setSystemMemoryVirtualSize(long systemMemoryVirtualSize) {
        this.systemMemoryVirtualSize = systemMemoryVirtualSize;
    }
}
