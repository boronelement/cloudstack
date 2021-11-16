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

package org.apache.cloudstack.metrics;

import java.lang.reflect.InvocationTargetException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import com.cloud.cluster.dao.ManagementServerHostDao;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ListClustersMetricsCmd;
import org.apache.cloudstack.api.ListHostsMetricsCmd;
import org.apache.cloudstack.api.ListInfrastructureCmd;
import org.apache.cloudstack.api.ListMgmtsMetricsCmd;
import org.apache.cloudstack.api.ListStoragePoolsMetricsCmd;
import org.apache.cloudstack.api.ListVMsMetricsCmd;
import org.apache.cloudstack.api.ListVMsUsageHistoryCmd;
import org.apache.cloudstack.api.ListVolumesMetricsCmd;
import org.apache.cloudstack.api.ListZonesMetricsCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ClusterResponse;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StatsResponse;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.response.ClusterMetricsResponse;
import org.apache.cloudstack.response.HostMetricsResponse;
import org.apache.cloudstack.response.InfrastructureResponse;
import org.apache.cloudstack.response.ManagementServerMetricsResponse;
import org.apache.cloudstack.response.StoragePoolMetricsResponse;
import org.apache.cloudstack.response.VmMetricsResponse;
import org.apache.cloudstack.response.VmMetricsStatsResponse;
import org.apache.cloudstack.response.VolumeMetricsResponse;
import org.apache.cloudstack.response.ZoneMetricsResponse;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.VmStatsEntryBase;
import com.cloud.alert.AlertManager;
import com.cloud.alert.dao.AlertDao;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.MutualExclusiveIdsManagerBase;
import com.cloud.api.query.dao.HostJoinDao;
import com.cloud.api.query.vo.HostJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.capacity.dao.CapacityDaoImpl;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeploymentClusterPlanner;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostStats;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.network.router.VirtualRouter;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.org.Managed;
import com.cloud.server.ManagementServerHostStats;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmStatsVO;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VmStatsDao;
import com.google.gson.Gson;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.log4j.Logger;

public class MetricsServiceImpl extends ComponentLifecycleBase implements MetricsService {
    private static final Logger LOGGER = Logger.getLogger(MetricsServiceImpl.class);

    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private HostPodDao podDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private HostJoinDao hostJoinDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private ImageStoreDao imageStoreDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private DomainRouterDao domainRouterDao;
    @Inject
    private CapacityDao capacityDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private ManagementServerHostDao managementServerHostDao;
    @Inject
    private AlertDao alertDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected VmStatsDao vmStatsDao;

    private static Gson gson = new Gson();

    protected MetricsServiceImpl() {
        super();
    }

    private Double findRatioValue(final String value) {
        if (value != null) {
            return Double.valueOf(value);
        }
        return 1.0;
    }

    private void updateHostMetrics(final HostMetrics hostMetrics, final HostJoinVO host) {
        hostMetrics.incrTotalHosts();
        hostMetrics.addCpuAllocated(host.getCpuReservedCapacity() + host.getCpuUsedCapacity());
        hostMetrics.addMemoryAllocated(host.getMemReservedCapacity() + host.getMemUsedCapacity());
        final HostStats hostStats = ApiDBUtils.getHostStatistics(host.getId());
        if (hostStats != null) {
            hostMetrics.addCpuUsedPercentage(hostStats.getCpuUtilization());
            hostMetrics.addMemoryUsed((long) hostStats.getUsedMemory());
            hostMetrics.setMaximumCpuUsage(hostStats.getCpuUtilization());
            hostMetrics.setMaximumMemoryUsage((long) hostStats.getUsedMemory());
        }
    }
    /**
     * Searches for VM stats based on the {@code ListVMsUsageHistoryCmd} parameters.
     *
     * @param cmd the {@link ListVMsUsageHistoryCmd} specifying what should be searched.
     * @return the list of VM metrics stats found.
     */
    @Override
    public ListResponse<VmMetricsStatsResponse> searchForVmMetricsStats(ListVMsUsageHistoryCmd cmd) {
        Pair<List<UserVmVO>, Integer> userVmList = searchForUserVmsInternal(cmd);
        Map<Long,List<VmStatsVO>> vmStatsList = searchForVmMetricsStatsInternal(cmd, userVmList.first());
        return createVmMetricsStatsResponse(userVmList, vmStatsList);
    }

    /**
     * Searches VMs based on {@code ListVMsUsageHistoryCmd} parameters.
     *
     * @param cmd the {@link ListVMsUsageHistoryCmd} specifying the parameters.
     * @return the list of VMs.
     */
    protected Pair<List<UserVmVO>, Integer> searchForUserVmsInternal(ListVMsUsageHistoryCmd cmd) {
        Filter searchFilter = new Filter(UserVmVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        List<Long> ids = getIdsListFromCmd(cmd.getId(), cmd.getIds());
        String name = cmd.getName();
        String keyword = cmd.getKeyword();

        SearchBuilder<UserVmVO> sb =  userVmDao.createSearchBuilder();
        sb.and("idIN", sb.entity().getId(), SearchCriteria.Op.IN);
        sb.and("displayName", sb.entity().getDisplayName(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);

        SearchCriteria<UserVmVO> sc = sb.create();
        if (CollectionUtils.isNotEmpty(ids)) {
            sc.setParameters("idIN", ids.toArray());
        }
        if (StringUtils.isNotBlank(name)) {
            sc.setParameters("displayName", "%" + name + "%");
        }
        if (StringUtils.isNotBlank(keyword)) {
            SearchCriteria<UserVmVO> ssc = userVmDao.createSearchCriteria();
            ssc.addOr("displayName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", SearchCriteria.Op.EQ, keyword);
            sc.addAnd("displayName", SearchCriteria.Op.SC, ssc);
        }

        return userVmDao.searchAndCount(sc, searchFilter);
    }

    /**
     * Searches stats for a list of VMs, based on date filtering parameters.
     *
     * @param cmd the {@link ListVMsUsageHistoryCmd} specifying the filtering parameters.
     * @param userVmList the list of VMs for which stats should be searched.
     * @return the key-value map in which keys are VM IDs and values are lists of VM stats.
     */
    protected Map<Long,List<VmStatsVO>> searchForVmMetricsStatsInternal(ListVMsUsageHistoryCmd cmd, List<UserVmVO> userVmList) {
        Map<Long,List<VmStatsVO>> vmStatsVOList = new HashMap<Long,List<VmStatsVO>>();
        Date startDate = cmd.getStartDate();
        Date endDate = cmd.getEndDate();

        validateDateParams(startDate, endDate);

        for (UserVmVO userVmVO : userVmList) {
            Long vmId = userVmVO.getId();
            vmStatsVOList.put(vmId, findVmStatsAccordingToDateParams(vmId, startDate, endDate));
        }

        return vmStatsVOList;
    }

    /**
     * Checks if {@code startDate} is after {@code endDate} (when both are not null)
     * and throws an {@link InvalidParameterValueException} if so.
     *
     * @param startDate the start date to be validated.
     * @param endDate the end date to be validated.
     */
    protected void validateDateParams(Date startDate, Date endDate) {
        if ((startDate != null && endDate != null) && (startDate.after(endDate))){
            throw new InvalidParameterValueException("startDate cannot be after endDate.");
        }
    }

    /**
     * Finds stats for a specific VM based on date parameters.
     *
     * @param vmId the specific VM.
     * @param startDate the start date to filtering.
     * @param endDate the end date to filtering.
     * @return the list of stats for the specified VM.
     */
    protected List<VmStatsVO> findVmStatsAccordingToDateParams(Long vmId, Date startDate, Date endDate){
        if (startDate != null && endDate != null) {
            return vmStatsDao.findByVmIdAndTimestampBetween(vmId, startDate, endDate);
        }
        if (startDate != null) {
            return vmStatsDao.findByVmIdAndTimestampGreaterThanEqual(vmId, startDate);
        }
        if (endDate != null) {
            return vmStatsDao.findByVmIdAndTimestampLessThanEqual(vmId, endDate);
        }
        return vmStatsDao.findByVmId(vmId);
    }

    /**
     * Creates a {@code ListResponse<VmMetricsStatsResponse>}. For each VM, this joins essential VM info
     * with its respective list of stats.
     *
     * @param userVmList the list of VMs.
     * @param vmStatsList the respective list of stats.
     * @return the list of responses that was created.
     */
    protected ListResponse<VmMetricsStatsResponse> createVmMetricsStatsResponse(Pair<List<UserVmVO>, Integer> userVmList,
            Map<Long,List<VmStatsVO>> vmStatsList) {
        List<VmMetricsStatsResponse> responses = new ArrayList<VmMetricsStatsResponse>();
        for (UserVmVO userVmVO : userVmList.first()) {
            VmMetricsStatsResponse vmMetricsStatsResponse = new VmMetricsStatsResponse();
            vmMetricsStatsResponse.setObjectName("virtualmachine");
            vmMetricsStatsResponse.setId(userVmVO.getUuid());
            vmMetricsStatsResponse.setName(userVmVO.getName());
            vmMetricsStatsResponse.setDisplayName(userVmVO.getDisplayName());

            vmMetricsStatsResponse.setStats(createStatsResponse(vmStatsList.get(userVmVO.getId())));
            responses.add(vmMetricsStatsResponse);
        }

        ListResponse<VmMetricsStatsResponse> response = new ListResponse<VmMetricsStatsResponse>();
        response.setResponses(responses);
        return response;
    }

    /**
     * Creates a {@code Set<StatsResponse>} from a given {@code List<VmStatsVO>}.
     *
     * @param vmStatsList the list of VM stats.
     * @return the set of responses that was created.
     */
    protected List<StatsResponse> createStatsResponse(List<VmStatsVO> vmStatsList) {
        List<StatsResponse> statsResponseList = new ArrayList<StatsResponse>();
        DecimalFormat decimalFormat = new DecimalFormat("#.##");
        for (VmStatsVO vmStats : vmStatsList) {
            StatsResponse response = new StatsResponse();
            response.setTimestamp(vmStats.getTimestamp());

            VmStatsEntryBase statsEntry = gson.fromJson(vmStats.getVmStatsData(), VmStatsEntryBase.class);
            response.setCpuUsed(decimalFormat.format(statsEntry.getCPUUtilization()) + "%");
            response.setNetworkKbsRead((long)statsEntry.getNetworkReadKBs());
            response.setNetworkKbsWrite((long)statsEntry.getNetworkWriteKBs());
            response.setDiskKbsRead((long)statsEntry.getDiskReadKBs());
            response.setDiskKbsWrite((long)statsEntry.getDiskWriteKBs());
            response.setDiskIORead((long)statsEntry.getDiskReadIOs());
            response.setDiskIOWrite((long)statsEntry.getDiskWriteIOs());
            long totalMemory = (long)statsEntry.getMemoryKBs();
            long freeMemory = (long)statsEntry.getIntFreeMemoryKBs();
            long correctedFreeMemory = freeMemory >= totalMemory ? 0 : freeMemory;
            response.setMemoryKBs(totalMemory);
            response.setMemoryIntFreeKBs(correctedFreeMemory);
            response.setMemoryTargetKBs((long)statsEntry.getTargetMemoryKBs());

            statsResponseList.add(response);
        }
        return statsResponseList;
    }


    @Override
    public InfrastructureResponse listInfrastructure() {
        final InfrastructureResponse response = new InfrastructureResponse();
        response.setZones(dataCenterDao.countAll());
        response.setPods(podDao.countAll());
        response.setClusters(clusterDao.countAll());
        response.setHosts(hostDao.countAllByType(Host.Type.Routing));
        response.setStoragePools(storagePoolDao.countAll());
        response.setImageStores(imageStoreDao.countAllImageStores());
        response.setSystemvms(vmInstanceDao.listByTypes(VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm).size());
        response.setRouters(domainRouterDao.countAllByRole(VirtualRouter.Role.VIRTUAL_ROUTER));
        response.setInternalLbs(domainRouterDao.countAllByRole(VirtualRouter.Role.INTERNAL_LB_VM));
        response.setAlerts(alertDao.countAll());
        int cpuSockets = 0;
        for (final Host host : hostDao.listByType(Host.Type.Routing)) {
            if (host.getCpuSockets() != null) {
                cpuSockets += host.getCpuSockets();
            }
        }
        response.setCpuSockets(cpuSockets);
        response.setManagementServers(managementServerHostDao.listAll().size());
        return response;
    }

    @Override
    public List<VolumeMetricsResponse> listVolumeMetrics(List<VolumeResponse> volumeResponses) {
        final List<VolumeMetricsResponse> metricsResponses = new ArrayList<>();
        for (final VolumeResponse volumeResponse: volumeResponses) {
            VolumeMetricsResponse metricsResponse = new VolumeMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, volumeResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate volume metrics response");
            }

            metricsResponse.setHasAnnotation(volumeResponse.hasAnnotation());
            metricsResponse.setDiskSizeGB(volumeResponse.getSize());
            metricsResponse.setDiskIopsTotal(volumeResponse.getDiskIORead(), volumeResponse.getDiskIOWrite());
            Account account = CallContext.current().getCallingAccount();
            if (accountMgr.isAdmin(account.getAccountId())) {
                metricsResponse.setStorageType(volumeResponse.getStorageType(), volumeResponse.getVolumeType());
            }
            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    @Override
    public List<VmMetricsResponse> listVmMetrics(List<UserVmResponse> vmResponses) {
        final List<VmMetricsResponse> metricsResponses = new ArrayList<>();
        for (final UserVmResponse vmResponse: vmResponses) {
            VmMetricsResponse metricsResponse = new VmMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, vmResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate vm metrics response");
            }

            metricsResponse.setHasAnnotation(vmResponse.hasAnnotation());
            metricsResponse.setIpAddress(vmResponse.getNics());
            metricsResponse.setCpuTotal(vmResponse.getCpuNumber(), vmResponse.getCpuSpeed());
            metricsResponse.setMemTotal(vmResponse.getMemory());
            metricsResponse.setNetworkRead(vmResponse.getNetworkKbsRead());
            metricsResponse.setNetworkWrite(vmResponse.getNetworkKbsWrite());
            metricsResponse.setDiskRead(vmResponse.getDiskKbsRead());
            metricsResponse.setDiskWrite(vmResponse.getDiskKbsWrite());
            metricsResponse.setDiskIopsTotal(vmResponse.getDiskIORead(), vmResponse.getDiskIOWrite());
            metricsResponse.setLastUpdated(vmResponse.getLastUpdated());
            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    @Override
    public List<StoragePoolMetricsResponse> listStoragePoolMetrics(List<StoragePoolResponse> poolResponses) {
        final List<StoragePoolMetricsResponse> metricsResponses = new ArrayList<>();
        for (final StoragePoolResponse poolResponse: poolResponses) {
            StoragePoolMetricsResponse metricsResponse = new StoragePoolMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, poolResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate storagepool metrics response");
            }

            Long poolClusterId = null;
            final Cluster cluster = clusterDao.findByUuid(poolResponse.getClusterId());
            if (cluster != null) {
                poolClusterId = cluster.getId();
            }
            final Double storageThreshold = AlertManager.StorageCapacityThreshold.valueIn(poolClusterId);
            final Double storageDisableThreshold = CapacityManager.StorageCapacityDisableThreshold.valueIn(poolClusterId);

            metricsResponse.setHasAnnotation(poolResponse.hasAnnotation());
            metricsResponse.setDiskSizeUsedGB(poolResponse.getDiskSizeUsed());
            metricsResponse.setDiskSizeTotalGB(poolResponse.getDiskSizeTotal(), poolResponse.getOverProvisionFactor());
            metricsResponse.setDiskSizeAllocatedGB(poolResponse.getDiskSizeAllocated());
            metricsResponse.setDiskSizeUnallocatedGB(poolResponse.getDiskSizeTotal(), poolResponse.getDiskSizeAllocated(), poolResponse.getOverProvisionFactor());
            metricsResponse.setStorageUsedThreshold(poolResponse.getDiskSizeTotal(), poolResponse.getDiskSizeUsed(), poolResponse.getOverProvisionFactor(), storageThreshold);
            metricsResponse.setStorageUsedDisableThreshold(poolResponse.getDiskSizeTotal(), poolResponse.getDiskSizeUsed(), poolResponse.getOverProvisionFactor(), storageDisableThreshold);
            metricsResponse.setStorageAllocatedThreshold(poolResponse.getDiskSizeTotal(), poolResponse.getDiskSizeAllocated(), poolResponse.getOverProvisionFactor(), storageThreshold);
            metricsResponse.setStorageAllocatedDisableThreshold(poolResponse.getDiskSizeTotal(), poolResponse.getDiskSizeUsed(), poolResponse.getOverProvisionFactor(), storageDisableThreshold);
            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    @Override
    public List<HostMetricsResponse> listHostMetrics(List<HostResponse> hostResponses) {
        final List<HostMetricsResponse> metricsResponses = new ArrayList<>();
        for (final HostResponse hostResponse: hostResponses) {
            HostMetricsResponse metricsResponse = new HostMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, hostResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate host metrics response");
            }

            final Host host = hostDao.findByUuid(hostResponse.getId());
            if (host == null) {
                continue;
            }
            final Long hostId = host.getId();
            final Long clusterId = host.getClusterId();

            // Thresholds
            final Double cpuThreshold = AlertManager.CPUCapacityThreshold.valueIn(clusterId);
            final Double memoryThreshold = AlertManager.MemoryCapacityThreshold.valueIn(clusterId);
            final Float cpuDisableThreshold = DeploymentClusterPlanner.ClusterCPUCapacityDisableThreshold.valueIn(clusterId);
            final Float memoryDisableThreshold = DeploymentClusterPlanner.ClusterMemoryCapacityDisableThreshold.valueIn(clusterId);

            Long upInstances = 0L;
            Long totalInstances = 0L;
            for (final VMInstanceVO instance: vmInstanceDao.listByHostId(hostId)) {
                if (instance == null) {
                    continue;
                }
                if (instance.getType() == VirtualMachine.Type.User) {
                    totalInstances++;
                    if (instance.getState() == VirtualMachine.State.Running) {
                        upInstances++;
                    }
                }
            }
            metricsResponse.setPowerState(hostResponse.getOutOfBandManagementResponse().getPowerState());
            metricsResponse.setInstances(upInstances, totalInstances);
            metricsResponse.setCpuTotal(hostResponse.getCpuNumber(), hostResponse.getCpuSpeed());
            metricsResponse.setCpuUsed(hostResponse.getCpuUsed(), hostResponse.getCpuNumber(), hostResponse.getCpuSpeed());
            metricsResponse.setCpuAllocated(hostResponse.getCpuAllocated(), hostResponse.getCpuNumber(), hostResponse.getCpuSpeed());
            metricsResponse.setLoadAverage(hostResponse.getAverageLoad());
            metricsResponse.setMemTotal(hostResponse.getMemoryTotal());
            metricsResponse.setMemAllocated(hostResponse.getMemoryAllocated());
            metricsResponse.setMemUsed(hostResponse.getMemoryUsed());
            metricsResponse.setNetworkRead(hostResponse.getNetworkKbsRead());
            metricsResponse.setNetworkWrite(hostResponse.getNetworkKbsWrite());
            // CPU thresholds
            metricsResponse.setCpuUsageThreshold(hostResponse.getCpuUsed(), cpuThreshold);
            metricsResponse.setCpuUsageDisableThreshold(hostResponse.getCpuUsed(), cpuDisableThreshold);
            metricsResponse.setCpuAllocatedThreshold(hostResponse.getCpuAllocated(), cpuThreshold);
            metricsResponse.setCpuAllocatedDisableThreshold(hostResponse.getCpuAllocated(), cpuDisableThreshold);
            // Memory thresholds
            metricsResponse.setMemoryUsageThreshold(hostResponse.getMemoryUsed(), hostResponse.getMemoryTotal(), memoryThreshold);
            metricsResponse.setMemoryUsageDisableThreshold(hostResponse.getMemoryUsed(), hostResponse.getMemoryTotal(), memoryDisableThreshold);
            metricsResponse.setMemoryAllocatedThreshold(hostResponse.getMemoryAllocated(), hostResponse.getMemoryTotal(), memoryThreshold);
            metricsResponse.setMemoryAllocatedDisableThreshold(hostResponse.getMemoryAllocated(), hostResponse.getMemoryTotal(), memoryDisableThreshold);
            metricsResponses.add(metricsResponse);
            metricsResponse.setHasAnnotation(hostResponse.hasAnnotation());
        }
        return metricsResponses;
    }

    private CapacityDaoImpl.SummedCapacity getCapacity(final int capacityType, final Long zoneId, final Long clusterId) {
        final List<CapacityDaoImpl.SummedCapacity> capacities = capacityDao.findCapacityBy(capacityType, zoneId, null, clusterId);
        if (capacities == null || capacities.size() < 1) {
            return null;
        }
        return capacities.get(0);
    }

    @Override
    public List<ClusterMetricsResponse> listClusterMetrics(Pair<List<ClusterResponse>, Integer> clusterResponses) {
        final List<ClusterMetricsResponse> metricsResponses = new ArrayList<>();
        for (final ClusterResponse clusterResponse: clusterResponses.first()) {
            ClusterMetricsResponse metricsResponse = new ClusterMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, clusterResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate cluster metrics response");
            }

            final Cluster cluster = clusterDao.findByUuid(clusterResponse.getId());
            if (cluster == null) {
                continue;
            }
            final Long clusterId = cluster.getId();

            // Thresholds
            final Double cpuThreshold = AlertManager.CPUCapacityThreshold.valueIn(clusterId);
            final Double memoryThreshold = AlertManager.MemoryCapacityThreshold.valueIn(clusterId);
            final Float cpuDisableThreshold = DeploymentClusterPlanner.ClusterCPUCapacityDisableThreshold.valueIn(clusterId);
            final Float memoryDisableThreshold = DeploymentClusterPlanner.ClusterMemoryCapacityDisableThreshold.valueIn(clusterId);

            // CPU and memory capacities
            final CapacityDaoImpl.SummedCapacity cpuCapacity = getCapacity((int) Capacity.CAPACITY_TYPE_CPU, null, clusterId);
            final CapacityDaoImpl.SummedCapacity memoryCapacity = getCapacity((int) Capacity.CAPACITY_TYPE_MEMORY, null, clusterId);
            final org.apache.cloudstack.metrics.MetricsServiceImpl.HostMetrics hostMetrics = new org.apache.cloudstack.metrics.MetricsServiceImpl.HostMetrics(cpuCapacity, memoryCapacity);

            for (final Host host: hostDao.findByClusterId(clusterId)) {
                if (host == null || host.getType() != Host.Type.Routing) {
                    continue;
                }
                if (host.getStatus() == Status.Up) {
                    hostMetrics.incrUpResources();
                }
                hostMetrics.incrTotalResources();
                updateHostMetrics(hostMetrics, hostJoinDao.findById(host.getId()));
            }

            metricsResponse.setState(clusterResponse.getAllocationState(), clusterResponse.getManagedState());
            metricsResponse.setResources(hostMetrics.getUpResources(), hostMetrics.getTotalResources());
            // CPU
            metricsResponse.setCpuTotal(hostMetrics.getTotalCpu());
            metricsResponse.setCpuAllocated(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu());
            if (hostMetrics.getCpuUsedPercentage() > 0L) {
                metricsResponse.setCpuUsed(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts());
                metricsResponse.setCpuMaxDeviation(hostMetrics.getMaximumCpuUsage(), hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts());
            }
            // Memory
            metricsResponse.setMemTotal(hostMetrics.getTotalMemory());
            metricsResponse.setMemAllocated(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory());
            if (hostMetrics.getMemoryUsed() > 0L) {
                metricsResponse.setMemUsed(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory());
                metricsResponse.setMemMaxDeviation(hostMetrics.getMaximumMemoryUsage(), hostMetrics.getMemoryUsed(), hostMetrics.getTotalHosts());
            }
            // CPU thresholds
            metricsResponse.setCpuUsageThreshold(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts(), cpuThreshold);
            metricsResponse.setCpuUsageDisableThreshold(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts(), cpuDisableThreshold);
            metricsResponse.setCpuAllocatedThreshold(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu(), cpuThreshold);
            metricsResponse.setCpuAllocatedDisableThreshold(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu(), cpuDisableThreshold);
            // Memory thresholds
            metricsResponse.setMemoryUsageThreshold(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory(), memoryThreshold);
            metricsResponse.setMemoryUsageDisableThreshold(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory(), memoryDisableThreshold);
            metricsResponse.setMemoryAllocatedThreshold(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory(), memoryThreshold);
            metricsResponse.setMemoryAllocatedDisableThreshold(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory(), memoryDisableThreshold);

            metricsResponse.setHasAnnotation(clusterResponse.hasAnnotation());
            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    @Override
    public List<ManagementServerMetricsResponse> listManagementServerMetrics(List<ManagementServerResponse> managementServerResponses) {
        final List<ManagementServerMetricsResponse> metricsResponses = new ArrayList<>();
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("getting metrics for %d MS hosts", managementServerResponses.size()));
        }
        for (final ManagementServerResponse managementServerResponse: managementServerResponses) {
            if(LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("processing metrics for MS hosts %s", managementServerResponse.getId()));
            }
            ManagementServerMetricsResponse metricsResponse = new ManagementServerMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, managementServerResponse);
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace(String.format("bean copy result %s", new ReflectionToStringBuilder(metricsResponse, ToStringStyle.SIMPLE_STYLE).toString()));
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate zone metrics response");
            }

            updateManagementServerMetrics(metricsResponse, managementServerResponse);

            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    /**
     * get the transient/in memory data
     * @param metricsResponse
     * @param managementServerResponse
     */
    private void updateManagementServerMetrics(ManagementServerMetricsResponse metricsResponse, ManagementServerResponse managementServerResponse) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("getting stats for %s", managementServerResponse.getId()));
        }
        ManagementServerHostStats status = ApiDBUtils.getManagementServerHostStatistics(managementServerResponse.getId());
        if (status == null ) {
            LOGGER.info(String.format("no status object found for %s - %s", managementServerResponse.getName(), managementServerResponse.getId()));
        } else {
            metricsResponse.setAvailableProcessors(status.getAvailableProcessors());
            metricsResponse.setAgentCount(status.getAgentCount());
            metricsResponse.setSessions(status.getSessions());
            metricsResponse.setHeapMemoryUsed(status.getHeapMemoryUsed());
            metricsResponse.setHeapMemoryTotal(status.getHeapMemoryTotal());
            metricsResponse.setThreadsBlockedCount(status.getThreadsBlockedCount());
            metricsResponse.setThreadsDeamonCount(status.getThreadsDeamonCount());
            metricsResponse.setThreadsRunnableCount(status.getThreadsRunnableCount());
            metricsResponse.setThreadsTerminatedCount(status.getThreadsTerminatedCount());
            metricsResponse.setThreadsTotalCount(status.getThreadsTotalCount());
            metricsResponse.setThreadsWaitingCount(status.getThreadsWaitingCount());
            metricsResponse.setSystemMemoryTotal(status.getSystemMemoryTotal());
            metricsResponse.setSystemMemoryFree(status.getSystemMemoryFree());
            metricsResponse.setSystemMemoryUsed(status.getSystemMemoryUsed());
            metricsResponse.setSystemMemoryVirtualSize(status.getSystemMemoryVirtualSize());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.info(String.format("status object found for %s - %s", managementServerResponse.getName(), new ReflectionToStringBuilder(status)));
            }
        }
    }

    @Override
    public List<ZoneMetricsResponse> listZoneMetrics(List<ZoneResponse> zoneResponses) {
        final List<ZoneMetricsResponse> metricsResponses = new ArrayList<>();
        for (final ZoneResponse zoneResponse: zoneResponses) {
            ZoneMetricsResponse metricsResponse = new ZoneMetricsResponse();

            try {
                BeanUtils.copyProperties(metricsResponse, zoneResponse);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to generate zone metrics response");
            }

            final DataCenter zone = dataCenterDao.findByUuid(zoneResponse.getId());
            if (zone == null) {
                continue;
            }
            final Long zoneId = zone.getId();

            // Thresholds
            final Double cpuThreshold = AlertManager.CPUCapacityThreshold.value();
            final Double memoryThreshold = AlertManager.MemoryCapacityThreshold.value();
            final Float cpuDisableThreshold = DeploymentClusterPlanner.ClusterCPUCapacityDisableThreshold.value();
            final Float memoryDisableThreshold = DeploymentClusterPlanner.ClusterMemoryCapacityDisableThreshold.value();

            // CPU and memory capacities
            final CapacityDaoImpl.SummedCapacity cpuCapacity = getCapacity((int) Capacity.CAPACITY_TYPE_CPU, zoneId, null);
            final CapacityDaoImpl.SummedCapacity memoryCapacity = getCapacity((int) Capacity.CAPACITY_TYPE_MEMORY, zoneId, null);
            final org.apache.cloudstack.metrics.MetricsServiceImpl.HostMetrics hostMetrics = new org.apache.cloudstack.metrics.MetricsServiceImpl.HostMetrics(cpuCapacity, memoryCapacity);

            for (final Cluster cluster : clusterDao.listClustersByDcId(zoneId)) {
                if (cluster == null) {
                    continue;
                }
                hostMetrics.incrTotalResources();
                if (cluster.getAllocationState() == Grouping.AllocationState.Enabled
                        && cluster.getManagedState() == Managed.ManagedState.Managed) {
                    hostMetrics.incrUpResources();
                }

                for (final Host host: hostDao.findByClusterId(cluster.getId())) {
                    if (host == null || host.getType() != Host.Type.Routing) {
                        continue;
                    }
                    updateHostMetrics(hostMetrics, hostJoinDao.findById(host.getId()));
                }
            }

            metricsResponse.setHasAnnotation(zoneResponse.hasAnnotation());
            metricsResponse.setState(zoneResponse.getAllocationState());
            metricsResponse.setResource(hostMetrics.getUpResources(), hostMetrics.getTotalResources());
            // CPU
            metricsResponse.setCpuTotal(hostMetrics.getTotalCpu());
            metricsResponse.setCpuAllocated(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu());
            if (hostMetrics.getCpuUsedPercentage() > 0L) {
                metricsResponse.setCpuUsed(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts());
                metricsResponse.setCpuMaxDeviation(hostMetrics.getMaximumCpuUsage(), hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts());
            }
            // Memory
            metricsResponse.setMemTotal(hostMetrics.getTotalMemory());
            metricsResponse.setMemAllocated(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory());
            if (hostMetrics.getMemoryUsed() > 0L) {
                metricsResponse.setMemUsed(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory());
                metricsResponse.setMemMaxDeviation(hostMetrics.getMaximumMemoryUsage(), hostMetrics.getMemoryUsed(), hostMetrics.getTotalHosts());
            }
            // CPU thresholds
            metricsResponse.setCpuUsageThreshold(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts(), cpuThreshold);
            metricsResponse.setCpuUsageDisableThreshold(hostMetrics.getCpuUsedPercentage(), hostMetrics.getTotalHosts(), cpuDisableThreshold);
            metricsResponse.setCpuAllocatedThreshold(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu(), cpuThreshold);
            metricsResponse.setCpuAllocatedDisableThreshold(hostMetrics.getCpuAllocated(), hostMetrics.getTotalCpu(), cpuDisableThreshold);
            // Memory thresholds
            metricsResponse.setMemoryUsageThreshold(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory(), memoryThreshold);
            metricsResponse.setMemoryUsageDisableThreshold(hostMetrics.getMemoryUsed(), hostMetrics.getTotalMemory(), memoryDisableThreshold);
            metricsResponse.setMemoryAllocatedThreshold(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory(), memoryThreshold);
            metricsResponse.setMemoryAllocatedDisableThreshold(hostMetrics.getMemoryAllocated(), hostMetrics.getTotalMemory(), memoryDisableThreshold);

            metricsResponses.add(metricsResponse);
        }
        return metricsResponses;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        cmdList.add(ListInfrastructureCmd.class);
        cmdList.add(ListVolumesMetricsCmd.class);
        cmdList.add(ListVMsMetricsCmd.class);
        cmdList.add(ListStoragePoolsMetricsCmd.class);
        cmdList.add(ListHostsMetricsCmd.class);
        cmdList.add(ListMgmtsMetricsCmd.class);
        cmdList.add(ListClustersMetricsCmd.class);
        cmdList.add(ListZonesMetricsCmd.class);
        cmdList.add(ListVMsUsageHistoryCmd.class);
        return cmdList;
    }

    private class HostMetrics {
        // CPU metrics
        private Long totalCpu = 0L;
        private Long cpuAllocated = 0L;
        private Double cpuUsedPercentage = 0.0;
        private Double maximumCpuUsage = 0.0;
        // Memory metrics
        private Long totalMemory = 0L;
        private Long memoryUsed = 0L;
        private Long memoryAllocated = 0L;
        private Long maximumMemoryUsage = 0L;
        // Counters
        private Long totalHosts = 0L;
        private Long totalResources = 0L;
        private Long upResources = 0L;

        public HostMetrics(final CapacityDaoImpl.SummedCapacity totalCpu, final CapacityDaoImpl.SummedCapacity totalMemory) {
            if (totalCpu != null) {
                this.totalCpu = totalCpu.getTotalCapacity();
            }
            if (totalMemory != null) {
                this.totalMemory = totalMemory.getTotalCapacity();
            }
        }

        public void addCpuAllocated(Long cpuAllocated) {
            this.cpuAllocated += cpuAllocated;
        }

        public void addCpuUsedPercentage(Double cpuUsedPercentage) {
            this.cpuUsedPercentage += cpuUsedPercentage;
        }

        public void setMaximumCpuUsage(Double maximumCpuUsage) {
            if (this.maximumCpuUsage == null || (maximumCpuUsage != null && maximumCpuUsage > this.maximumCpuUsage)) {
                this.maximumCpuUsage = maximumCpuUsage;
            }
        }

        public void addMemoryUsed(Long memoryUsed) {
            this.memoryUsed += memoryUsed;
        }

        public void addMemoryAllocated(Long memoryAllocated) {
            this.memoryAllocated += memoryAllocated;
        }

        public void setMaximumMemoryUsage(Long maximumMemoryUsage) {
            if (this.maximumMemoryUsage == null || (maximumMemoryUsage != null && maximumMemoryUsage > this.maximumMemoryUsage)) {
                this.maximumMemoryUsage = maximumMemoryUsage;
            }
        }

        public void incrTotalHosts() {
            this.totalHosts++;
        }

        public void incrTotalResources() {
            this.totalResources++;
        }

        public void incrUpResources() {
            this.upResources++;
        }

        public Long getTotalCpu() {
            return totalCpu;
        }

        public Long getCpuAllocated() {
            return cpuAllocated;
        }

        public Double getCpuUsedPercentage() {
            return cpuUsedPercentage;
        }

        public Double getMaximumCpuUsage() {
            return maximumCpuUsage;
        }

        public Long getTotalMemory() {
            return totalMemory;
        }

        public Long getMemoryUsed() {
            return memoryUsed;
        }

        public Long getMemoryAllocated() {
            return memoryAllocated;
        }

        public Long getMaximumMemoryUsage() {
            return maximumMemoryUsage;
        }

        public Long getTotalHosts() {
            return totalHosts;
        }

        public Long getTotalResources() {
            return totalResources;
        }

        public Long getUpResources() {
            return upResources;
        }
    }

}
