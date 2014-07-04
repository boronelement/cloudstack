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

package com.cloud.network.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.log4j.Logger;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.rules.LoadBalancingRules;
import com.cloud.network.rules.VirtualNetworkApplianceFactory;
import com.cloud.user.Account;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;

public class BasicNetworkTopology implements NetworkTopology {

    private static final Logger s_logger = Logger.getLogger(BasicNetworkTopology.class);

    @Inject
    VirtualNetworkApplianceFactory applianceFactory;

    @Inject
    DataCenterDao _dcDao;

    @Inject
    HostDao _hostDao;

    @Override
    public List<DomainRouterVO> findOrDeployVirtualRouterInGuestNetwork(
            final Network guestNetwork, final DeployDestination dest, final Account owner,
            final boolean isRedundant, final Map<Param, Object> params)
                    throws ConcurrentOperationException, InsufficientCapacityException,
                    ResourceUnavailableException {
        return null;
    }

    @Override
    public StringBuilder createGuestBootLoadArgs(final NicProfile guestNic,
            final String defaultDns1, final String defaultDns2, final DomainRouterVO router) {
        return null;
    }

    @Override
    public String retrieveGuestDhcpRange(final NicProfile guestNic,
            final Network guestNetwork, final DataCenter dc) {
        return null;
    }

    @Override
    public NicProfile retrieveControlNic(final VirtualMachineProfile profile) {
        return null;
    }

    @Override
    public boolean configDhcpForSubnet(final Network network, final NicProfile nic,
            final VirtualMachineProfile profile, final DeployDestination dest,
            final List<DomainRouterVO> routers) throws ResourceUnavailableException {
        return false;
    }

    @Override
    public boolean applyDhcpEntry(final Network network, final NicProfile nic,
            final VirtualMachineProfile profile, final DeployDestination dest,
            final List<DomainRouterVO> routers) throws ResourceUnavailableException {
        return false;
    }

    @Override
    public boolean applyUserData(final Network network, final NicProfile nic,
            final VirtualMachineProfile profile, final DeployDestination dest,
            final List<DomainRouterVO> routers) throws ResourceUnavailableException {
        return false;
    }

    @Override
    public boolean applyRules(final Network network, final List<VirtualRouter> routers, final String typeString,
            final boolean isPodLevelException, final Long podId, final boolean failWhenDisconnect) throws ResourceUnavailableException {

        return false;
    }

    @Override
    public boolean applyLoadBalancingRules(final Network network, final List<LoadBalancingRule> rules, final List<? extends VirtualRouter> routers)
            throws ResourceUnavailableException {

        final String typeString = "loadbalancing rules";
        final boolean isPodLevelException = false;
        final boolean failWhenDisconnect = false;
        final Long podId = null;

        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Unable to apply " + typeString + ", virtual router doesn't exist in the network " + network.getId());
            throw new ResourceUnavailableException("Unable to apply " + typeString, DataCenter.class, network.getDataCenterId());
        }

        LoadBalancingRules loadBalancingRules = applianceFactory.createLoadBalancingRules(network, rules);
        AdvancedNetworkVisitor visitor = new AdvancedNetworkVisitor();

        //REMOVE THIS SHIT AND INJECT USING A FACTORY FOR THE VISITORS
        visitor.setApplianceManager(loadBalancingRules.getApplianceManager());

        final DataCenter dc = _dcDao.findById(network.getDataCenterId());
        final boolean isZoneBasic = (dc.getNetworkType() == NetworkType.Basic);

        // isPodLevelException and podId is only used for basic zone
        assert !((!isZoneBasic && isPodLevelException) || (isZoneBasic && isPodLevelException && podId == null));

        final List<VirtualRouter> connectedRouters = new ArrayList<VirtualRouter>();
        final List<VirtualRouter> disconnectedRouters = new ArrayList<VirtualRouter>();
        boolean result = true;
        final String msg = "Unable to apply " + typeString + " on disconnected router ";
        for (final VirtualRouter router : routers) {
            if (router.getState() == State.Running) {
                s_logger.debug("Applying " + typeString + " in network " + network);

                if (router.isStopPending()) {
                    if (_hostDao.findById(router.getHostId()).getState() == Status.Up) {
                        throw new ResourceUnavailableException("Unable to process due to the stop pending router " + router.getInstanceName() +
                                " haven't been stopped after it's host coming back!", DataCenter.class, router.getDataCenterId());
                    }
                    s_logger.debug("Router " + router.getInstanceName() + " is stop pending, so not sending apply " + typeString + " commands to the backend");
                    continue;
                }

                try {
                    loadBalancingRules.accept(visitor, router);

                    connectedRouters.add(router);
                } catch (final AgentUnavailableException e) {
                    s_logger.warn(msg + router.getInstanceName(), e);
                    disconnectedRouters.add(router);
                }

                //If rules fail to apply on one domR and not due to disconnection, no need to proceed with the rest
                if (!result) {
                    if (isZoneBasic && isPodLevelException) {
                        throw new ResourceUnavailableException("Unable to apply " + typeString + " on router ", Pod.class, podId);
                    }
                    throw new ResourceUnavailableException("Unable to apply " + typeString + " on router ", DataCenter.class, router.getDataCenterId());
                }

            } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
                s_logger.debug("Router " + router.getInstanceName() + " is in " + router.getState() + ", so not sending apply " + typeString + " commands to the backend");
            } else {
                s_logger.warn("Unable to apply " + typeString + ", virtual router is not in the right state " + router.getState());
                if (isZoneBasic && isPodLevelException) {
                    throw new ResourceUnavailableException("Unable to apply " + typeString + ", virtual router is not in the right state", Pod.class, podId);
                }
                throw new ResourceUnavailableException("Unable to apply " + typeString + ", virtual router is not in the right state", DataCenter.class,
                        router.getDataCenterId());
            }
        }

        if (!connectedRouters.isEmpty()) {
            if (!isZoneBasic && !disconnectedRouters.isEmpty() && disconnectedRouters.get(0).getIsRedundantRouter()) {
                // These disconnected redundant virtual routers are out of sync now, stop them for synchronization
                //handleSingleWorkingRedundantRouter(connectedRouters, disconnectedRouters, msg);
            }
        } else if (!disconnectedRouters.isEmpty()) {
            for (final VirtualRouter router : disconnectedRouters) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug(msg + router.getInstanceName() + "(" + router.getId() + ")");
                }
            }
            if (isZoneBasic && isPodLevelException) {
                throw new ResourceUnavailableException(msg, Pod.class, podId);
            }
            throw new ResourceUnavailableException(msg, DataCenter.class, disconnectedRouters.get(0).getDataCenterId());
        }

        result = true;
        if (failWhenDisconnect) {
            result = !connectedRouters.isEmpty();
        }
        return result;
    }
}