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

package com.cloud.kubernetes.cluster.actionworkers;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Level;

import com.cloud.dc.DataCenter;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.utils.KubernetesClusterUtil;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.base.Strings;

public class KubernetesClusterScaleWorker extends KubernetesClusterResourceModifierActionWorker {

    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected UserVmManager userVmManager;

    private ServiceOffering serviceOffering;
    private Long clusterSize;
    private KubernetesCluster.State originalState;
    private Network network;

    public KubernetesClusterScaleWorker(final KubernetesCluster kubernetesCluster,
                                        final ServiceOffering serviceOffering,
                                        final Long clusterSize,
                                        final KubernetesClusterManagerImpl clusterManager) {
        super(kubernetesCluster, clusterManager);
        this.serviceOffering = serviceOffering;
        this.clusterSize = clusterSize;
        this.originalState = kubernetesCluster.getState();
    }

    protected void init() {
        super.init();
        this.network = networkDao.findById(kubernetesCluster.getNetworkId());
    }

    private void logTransitStateToFailedIfNeededAndThrow(final Level logLevel, final String message, final Exception e) throws CloudRuntimeException {
        if (kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
            logTransitStateAndThrow(logLevel, message, kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed, e);
        } else {
            logAndThrow(logLevel, message, e);
        }
    }

    private void logTransitStateToFailedIfNeededAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
        logTransitStateToFailedIfNeededAndThrow(logLevel, message, null);
    }

    private FirewallRule removeSshFirewallRule(IpAddress publicIp) {
        FirewallRule rule = null;
        List<FirewallRuleVO> firewallRules = firewallRulesDao.listByIpAndPurposeAndNotRevoked(publicIp.getId(), FirewallRule.Purpose.Firewall);
        for (FirewallRuleVO firewallRule : firewallRules) {
            if (firewallRule.getSourcePortStart() == CLUSTER_NODES_DEFAULT_START_SSH_PORT) {
                rule = firewallRule;
                firewallService.revokeIngressFwRule(firewallRule.getId(), true);
                break;
            }
        }
        return rule;
    }

    private void removePortForwardingRules(IpAddress publicIp, Network network, Account account, List<Long> removedVMIds) throws ResourceUnavailableException {
        if (!CollectionUtils.isEmpty(removedVMIds)) {
            for (Long vmId : removedVMIds) {
                List<PortForwardingRuleVO> pfRules = portForwardingRulesDao.listByNetwork(network.getId());
                for (PortForwardingRuleVO pfRule : pfRules) {
                    if (pfRule.getVirtualMachineId() == vmId) {
                        portForwardingRulesDao.remove(pfRule.getId());
                        break;
                    }
                }
            }
            rulesService.applyPortForwardingRules(publicIp.getId(), account);
        }
    }

    /**
     * Scale network rules for an existing Kubernetes cluster while scaling it
     * Open up firewall for SSH access from port NODES_DEFAULT_START_SSH_PORT to NODES_DEFAULT_START_SSH_PORT+n.
     * Also remove port forwarding rules for removed virtual machines and create port-forwarding rule
     * to forward public IP traffic to all node VMs' private IP.
     * @param clusterVMIds
     * @param removedVMIds
     * @throws ManagementServerException
     */
    private void scaleKubernetesClusterNetworkRules(List<Long> clusterVMIds, List<Long> removedVMIds) throws ManagementServerException {
        if (!Network.GuestType.Isolated.equals(network.getGuestType())) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Network ID: %s for Kubernetes cluster ID: %s is not an isolated network, therefore, no need for network rules", network.getUuid(), kubernetesCluster.getUuid()));
            }
            return;
        }
        IpAddress publicIp = getSourceNatIp(network);
        if (publicIp == null) {
            throw new ManagementServerException(String.format("No source NAT IP addresses found for network ID: %s, Kubernetes cluster ID: %s", network.getUuid(), kubernetesCluster.getUuid()));
        }

        // Remove existing SSH firewall rules
        FirewallRule firewallRule = removeSshFirewallRule(publicIp);
        if (firewallRule == null) {
            throw new ManagementServerException("Firewall rule for node SSH access can't be provisioned!");
        }
        int existingFirewallRuleSourcePortEnd = firewallRule.getSourcePortEnd();

        // Provision new SSH firewall rules
        try {
            provisionFirewallRules(publicIp, owner, CLUSTER_NODES_DEFAULT_START_SSH_PORT, CLUSTER_NODES_DEFAULT_START_SSH_PORT + (int)kubernetesCluster.getTotalNodeCount() - 1);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(String.format("Provisioned  firewall rule to open up port %d to %d on %s in Kubernetes cluster ID: %s",
                        CLUSTER_NODES_DEFAULT_START_SSH_PORT, CLUSTER_NODES_DEFAULT_START_SSH_PORT + (int) kubernetesCluster.getTotalNodeCount() - 1, publicIp.getAddress().addr(), kubernetesCluster.getUuid()));
            }
        } catch (NoSuchFieldException | IllegalAccessException | ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to activate SSH firewall rules for the Kubernetes cluster ID: %s", kubernetesCluster.getUuid()), e);
        }

        try {
            removePortForwardingRules(publicIp, network, owner, removedVMIds);
        } catch (ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to remove SSH port forwarding rules for removed VMs for the Kubernetes cluster ID: %s", kubernetesCluster.getUuid()), e);
        }

        try {
            provisionSshPortForwardingRules(publicIp, network, owner, clusterVMIds, existingFirewallRuleSourcePortEnd + 1);
        } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to activate SSH port forwarding rules for the Kubernetes cluster ID: %s", kubernetesCluster.getUuid()), e);
        }
    }

    private KubernetesClusterVO updateKubernetesClusterEntry(final long cores, final long memory,
                                                             final Long size, final Long serviceOfferingId) {
        return Transaction.execute((TransactionCallback<KubernetesClusterVO>) status -> {
            KubernetesClusterVO updatedCluster = kubernetesClusterDao.createForUpdate(kubernetesCluster.getId());
            updatedCluster.setCores(cores);
            updatedCluster.setMemory(memory);
            if (size != null) {
                updatedCluster.setNodeCount(size);
            }
            if (serviceOfferingId != null) {
                updatedCluster.setServiceOfferingId(serviceOfferingId);
            }
            kubernetesClusterDao.persist(updatedCluster);
            return updatedCluster;
        });
    }

    private KubernetesClusterVO updateKubernetesClusterEntry(final Long newSize, final ServiceOffering newServiceOffering) throws CloudRuntimeException {
        final ServiceOffering serviceOffering = newServiceOffering == null ?
                serviceOfferingDao.findById(kubernetesCluster.getServiceOfferingId()) : newServiceOffering;
        final Long serviceOfferingId = newServiceOffering == null ? null : serviceOffering.getId();
        final long size = newSize == null ? kubernetesCluster.getNodeCount() : newSize;
        final long cores = serviceOffering.getCpu() * size;
        final long memory = serviceOffering.getRamSize() * size;
        KubernetesClusterVO kubernetesClusterVO = updateKubernetesClusterEntry(cores, memory, size, serviceOfferingId);
        if (kubernetesClusterVO == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, unable to update Kubernetes cluster",
                    kubernetesCluster.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
        }
        return kubernetesClusterVO;
    }

    private boolean removeKubernetesClusterNode(String ipAddress, int port, UserVm userVm, int retries, int waitDuration) {
        File pkFile = getManagementServerSshPublicKeyFile();
        int retryCounter = 0;
        String hostName = userVm.getHostName();
        if (!Strings.isNullOrEmpty(hostName)) {
            hostName = hostName.toLowerCase();
        }
        while (retryCounter < retries) {
            retryCounter++;
            try {
                Pair<Boolean, String> result = SshHelper.sshExecute(ipAddress, port, CLUSTER_NODE_VM_USER,
                        pkFile, null, String.format("sudo kubectl drain %s --ignore-daemonsets --delete-local-data", hostName),
                        10000, 10000, 60000);
                if (!result.first()) {
                    LOGGER.warn(String.format("Draining node: %s on VM ID: %s in Kubernetes cluster ID: %s unsuccessful", hostName, userVm.getUuid(), kubernetesCluster.getUuid()));
                } else {
                    result = SshHelper.sshExecute(ipAddress, port, CLUSTER_NODE_VM_USER,
                            pkFile, null, String.format("sudo kubectl delete node %s", hostName),
                            10000, 10000, 30000);
                    if (result.first()) {
                        return true;
                    } else {
                        LOGGER.warn(String.format("Deleting node: %s on VM ID: %s in Kubernetes cluster ID: %s unsuccessful", hostName, userVm.getUuid(), kubernetesCluster.getUuid()));
                    }
                }
                break;
            } catch (Exception e) {
                String msg = String.format("Failed to remove Kubernetes cluster ID: %s node: %s on VM ID: %s", kubernetesCluster.getUuid(), hostName, userVm.getUuid());
                LOGGER.warn(msg, e);
            }
            try {
                Thread.sleep(waitDuration);
            } catch (InterruptedException ie) {
                LOGGER.error(String.format("Error while waiting for Kubernetes cluster ID: %s node: %s on VM ID: %s removal", kubernetesCluster.getUuid(), hostName, userVm.getUuid()), ie);
            }
            retryCounter++;
        }
        return false;
    }

    private void validateKubernetesClusterScaleOfferingParameters() throws CloudRuntimeException {
        if (KubernetesCluster.State.Created.equals(originalState)) {
            return;
        }
        final long originalNodeCount = kubernetesCluster.getTotalNodeCount();
        List<KubernetesClusterVmMapVO> vmList = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId());
        if (vmList == null || vmList.isEmpty() || vmList.size() < originalNodeCount) {
            logTransitStateToFailedIfNeededAndThrow(Level.WARN, String.format("Scaling Kubernetes cluster ID: %s failed, it is in unstable state as not enough existing VM instances found!", kubernetesCluster.getUuid()));
        } else {
            for (KubernetesClusterVmMapVO vmMapVO : vmList) {
                VMInstanceVO vmInstance = vmInstanceDao.findById(vmMapVO.getVmId());
                if (vmInstance != null && vmInstance.getState().equals(VirtualMachine.State.Running) &&
                        vmInstance.getHypervisorType() != Hypervisor.HypervisorType.XenServer &&
                        vmInstance.getHypervisorType() != Hypervisor.HypervisorType.VMware &&
                        vmInstance.getHypervisorType() != Hypervisor.HypervisorType.Simulator) {
                    logTransitStateToFailedIfNeededAndThrow(Level.WARN, String.format("Scaling Kubernetes cluster ID: %s failed, scaling Kubernetes cluster with running VMs on hypervisor %s is not supported!", kubernetesCluster.getUuid(), vmInstance.getHypervisorType()));
                }
            }
        }
    }

    private void validateKubernetesClusterScaleSizeParameters() throws CloudRuntimeException {
        final long originalClusterSize = kubernetesCluster.getNodeCount();
        if (network == null) {
            logTransitStateToFailedIfNeededAndThrow(Level.WARN, String.format("Scaling failed for Kubernetes cluster ID: %s, cluster network not found", kubernetesCluster.getUuid()));
        }
        // Check capacity and transition state
        final long newVmRequiredCount = clusterSize - originalClusterSize;
        final ServiceOffering clusterServiceOffering = serviceOfferingDao.findById(kubernetesCluster.getServiceOfferingId());
        if (clusterServiceOffering == null) {
            logTransitStateToFailedIfNeededAndThrow(Level.WARN, String.format("Scaling failed for Kubernetes cluster ID: %s, cluster service offering not found", kubernetesCluster.getUuid()));
        }
        if (newVmRequiredCount > 0) {
            final DataCenter zone = dataCenterDao.findById(kubernetesCluster.getZoneId());
            try {
                if (originalState.equals(KubernetesCluster.State.Running)) {
                    plan(newVmRequiredCount, zone, clusterServiceOffering);
                } else {
                    plan(kubernetesCluster.getTotalNodeCount() + newVmRequiredCount, zone, clusterServiceOffering);
                }
            } catch (InsufficientCapacityException e) {
                logTransitStateToFailedIfNeededAndThrow(Level.WARN, String.format("Scaling failed for Kubernetes cluster ID: %s in zone ID: %s, insufficient capacity", kubernetesCluster.getUuid(), zone.getUuid()));
            }
        }
        List<KubernetesClusterVmMapVO> vmList = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId());
        if (CollectionUtils.isEmpty(vmList) || vmList.size() < kubernetesCluster.getTotalNodeCount()) {
            logTransitStateToFailedIfNeededAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, it is in unstable state as not enough existing VM instances found", kubernetesCluster.getUuid()));
        }
    }

    private void scaleKubernetesClusterOffering() throws CloudRuntimeException {
        validateKubernetesClusterScaleOfferingParameters();
        if (!kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.ScaleUpRequested);
        }
        if (KubernetesCluster.State.Created.equals(originalState)) {
            kubernetesCluster = updateKubernetesClusterEntry(null, serviceOffering);
            return;
        }
        final long size = kubernetesCluster.getTotalNodeCount();
        List<KubernetesClusterVmMapVO> vmList = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId());
        final long tobeScaledVMCount =  Math.min(vmList.size(), size);
        for (long i = 0; i < tobeScaledVMCount; i++) {
            KubernetesClusterVmMapVO vmMapVO = vmList.get((int) i);
            UserVmVO userVM = userVmDao.findById(vmMapVO.getVmId());
            boolean result = false;
            try {
                result = userVmManager.upgradeVirtualMachine(userVM.getId(), serviceOffering.getId(), new HashMap<String, String>());
            } catch (ResourceUnavailableException | ManagementServerException | ConcurrentOperationException | VirtualMachineMigrationException e) {
                logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, unable to scale cluster VM ID: %s", kubernetesCluster.getUuid(), userVM.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed, e);
            }
            if (!result) {
                logTransitStateAndThrow(Level.WARN, String.format("Scaling Kubernetes cluster ID: %s failed, unable to scale cluster VM ID: %s", kubernetesCluster.getUuid(), userVM.getUuid()),kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
            }
        }
        kubernetesCluster = updateKubernetesClusterEntry(null, serviceOffering);
    }

    private void scaleDownKubernetesClusterSize(final List<KubernetesClusterVmMapVO> originalVmList) throws CloudRuntimeException {
        if (!kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.ScaleDownRequested);
        }
        int i = originalVmList.size() - 1;
        List<Long> removedVmIds = new ArrayList<>();
        while (i > kubernetesCluster.getMasterNodeCount()) {
            KubernetesClusterVmMapVO vmMapVO = originalVmList.get(i);
            UserVm userVM = userVmDao.findById(vmMapVO.getVmId());
            if (!removeKubernetesClusterNode(publicIpAddress, sshPort, userVM, 3, 30000)) {
                logTransitStateAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, failed to remove Kubernetes node: %s running on VM ID: %s", kubernetesCluster.getUuid(), userVM.getHostName(), userVM.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
            }
            // For removing port-forwarding network rules
            removedVmIds.add(userVM.getId());
            try {
                UserVm vm = userVmService.destroyVm(userVM.getId(), true);
                if (!VirtualMachine.State.Expunging.equals(vm.getState())) {
                    logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, VM '%s' is now in state '%s'."
                            , kubernetesCluster.getUuid()
                            , vm.getInstanceName()
                            , vm.getState().toString()),
                            kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
                }
                vm = userVmService.expungeVm(userVM.getId());
                if (!VirtualMachine.State.Expunging.equals(vm.getState())) {
                    logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, VM '%s' is now in state '%s'."
                            , kubernetesCluster.getUuid()
                            , vm.getInstanceName()
                            , vm.getState().toString()),
                            kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
                }
            } catch (ResourceUnavailableException e) {
                logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, unable to remove VM ID: %s"
                        , kubernetesCluster.getUuid() , userVM.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed, e);
            }
            kubernetesClusterVmMapDao.expunge(vmMapVO.getId());
            i--;
        }
        // Scale network rules to update firewall rule
        try {
            scaleKubernetesClusterNetworkRules(null, removedVmIds);
        } catch (ManagementServerException e) {
            logTransitStateAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, unable to update network rules", kubernetesCluster.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed, e);
        }
    }

    private void scaleUpKubernetesClusterSize(final List<KubernetesClusterVmMapVO> originalVmList, final long newVmCount) throws CloudRuntimeException {
        if (!kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.ScaleUpRequested);
        }
        List<UserVm> clusterVMs = new ArrayList<>();
        List<Long> clusterVMIds = new ArrayList<>();
        try {
            clusterVMs = provisionKubernetesClusterNodeVms((int) newVmCount + originalVmList.size(), originalVmList.size(), publicIpAddress);
        } catch (ManagementServerException | ResourceUnavailableException | InsufficientCapacityException e) {
            logTransitStateToFailedIfNeededAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, unable to provision node VM in the cluster", kubernetesCluster.getUuid()), e);
        }
        for (UserVm vm : clusterVMs) {
            clusterVMIds.add(vm.getId());
        }
        try {
            scaleKubernetesClusterNetworkRules(clusterVMIds, null);
        } catch (ManagementServerException e) {
            logTransitStateToFailedIfNeededAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, unable to update network rules", kubernetesCluster.getUuid()), e);
        }
        attachIsoKubernetesVMs(clusterVMs);
        KubernetesClusterVO kubernetesClusterVO = kubernetesClusterDao.findById(kubernetesCluster.getId());
        kubernetesClusterVO.setNodeCount(clusterSize);
        boolean readyNodesCountValid = KubernetesClusterUtil.validateKubernetesClusterReadyNodesCount(kubernetesClusterVO, publicIpAddress, sshPort,
                CLUSTER_NODE_VM_USER, sshKeyFile, 30, 30000);
        detachIsoKubernetesVMs(clusterVMs);
        if (!readyNodesCountValid) { // Scaling failed
            logTransitStateToFailedIfNeededAndThrow(Level.ERROR, String.format("Scaling unsuccessful for Kubernetes cluster ID: %s as it does not have desired number of nodes in ready state", kubernetesCluster.getUuid()));
        }
    }

    private void scaleKubernetesClusterSize() throws CloudRuntimeException {
        validateKubernetesClusterScaleSizeParameters();
        final long originalClusterSize = kubernetesCluster.getNodeCount();
        final long newVmRequiredCount = clusterSize - originalClusterSize;
        if (KubernetesCluster.State.Created.equals(originalState)) {
            if (!kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
                stateTransitTo(kubernetesCluster.getId(), newVmRequiredCount > 0 ? KubernetesCluster.Event.ScaleUpRequested : KubernetesCluster.Event.ScaleDownRequested);
            }
            kubernetesCluster = updateKubernetesClusterEntry(null, serviceOffering);
            return;
        }
        Pair<String, Integer> publicIpSshPort = getKubernetesClusterServerIpSshPort(null);
        publicIpAddress = publicIpSshPort.first();
        sshPort = publicIpSshPort.second();
        if (Strings.isNullOrEmpty(publicIpAddress)) {
            logTransitStateToFailedIfNeededAndThrow(Level.ERROR, String.format("Scaling failed for Kubernetes cluster ID: %s, unable to retrieve associated public IP", kubernetesCluster.getUuid()));
        }
        List<KubernetesClusterVmMapVO> vmList = getKubernetesClusterVMMaps();
        if (newVmRequiredCount < 0) { // downscale
            scaleDownKubernetesClusterSize(vmList);
        } else { // upscale, same node count handled above
            scaleUpKubernetesClusterSize(vmList, newVmRequiredCount);
        }
        kubernetesCluster = updateKubernetesClusterEntry(clusterSize, null);
    }

    public boolean scaleCluster() throws CloudRuntimeException {
        init();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Scaling Kubernetes cluster ID: %s", kubernetesCluster.getUuid()));
        }
        final long originalClusterSize = kubernetesCluster.getNodeCount();
        final ServiceOffering existingServiceOffering = serviceOfferingDao.findById(kubernetesCluster.getServiceOfferingId());
        if (existingServiceOffering == null) {
            logAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster ID: %s failed, service offering for the Kubernetes cluster not found!", kubernetesCluster.getUuid()));
        }
        final boolean serviceOfferingScalingNeeded = serviceOffering != null && serviceOffering.getId() != existingServiceOffering.getId();
        final boolean clusterSizeScalingNeeded = clusterSize != null && clusterSize != originalClusterSize;
        final long newVMRequired = clusterSize == null ? 0 : clusterSize - originalClusterSize;
        if (serviceOfferingScalingNeeded && clusterSizeScalingNeeded) {
            if (newVMRequired > 0) {
                scaleKubernetesClusterOffering();
                scaleKubernetesClusterSize();
            } else {
                scaleKubernetesClusterSize();
                scaleKubernetesClusterOffering();
            }
        } else if (serviceOfferingScalingNeeded) {
            scaleKubernetesClusterOffering();
        } else if (clusterSizeScalingNeeded) {
            scaleKubernetesClusterSize();
        }
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationSucceeded);
        return true;
    }
}
