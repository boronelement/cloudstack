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
package org.apache.cloudstack.service;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.NsxAnswer;
import org.apache.cloudstack.agent.api.CreateNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.DeleteNsxSegmentCommand;
import org.apache.cloudstack.agent.api.DeleteNsxTier1GatewayCommand;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.utils.NsxControllerUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class NsxServiceImpl implements NsxService {
    @Inject
    NsxControllerUtils nsxControllerUtils;
    @Inject
    VpcDao vpcDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private NetworkDao networkDao;

    private static final Logger LOGGER = Logger.getLogger(NsxServiceImpl.class);

    public boolean createVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName, boolean sourceNatEnabled) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true, sourceNatEnabled);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxTier1GatewayCommand, zoneId);
        return result.getResult();
    }

    public boolean createNetwork(Long zoneId, long accountId, long domainId, Long networkId, String networkName) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, networkId, networkName, false, false);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxTier1GatewayCommand, zoneId);
        return result.getResult();
    }

    public boolean deleteVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName) {
        DeleteNsxTier1GatewayCommand deleteNsxTier1GatewayCommand =
                new DeleteNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxTier1GatewayCommand, zoneId);
        return result.getResult();
    }

    public boolean deleteNetwork(long zoneId, long accountId, long domainId, NetworkVO network) {
        String vpcName = null;
        if (Objects.nonNull(network.getVpcId())) {
            VpcVO vpc = vpcDao.findById(network.getVpcId());
            vpcName = Objects.nonNull(vpc) ? vpc.getName() : null;
        }
        DeleteNsxSegmentCommand deleteNsxSegmentCommand = new DeleteNsxSegmentCommand(domainId, accountId, zoneId,
                network.getVpcId(), vpcName, network.getId(), network.getName());
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxSegmentCommand, network.getDataCenterId());
        if (!result.getResult()) {
            String msg = String.format("Could not remove the NSX segment for network %s", network.getName());
            LOGGER.error(msg);
            throw new CloudRuntimeException(msg);
        }

        if (Objects.isNull(network.getVpcId())) {
            DeleteNsxTier1GatewayCommand deleteNsxTier1GatewayCommand = new DeleteNsxTier1GatewayCommand(domainId, accountId, zoneId, network.getId(), network.getName(), false);
            result = nsxControllerUtils.sendNsxCommand(deleteNsxTier1GatewayCommand, zoneId);
        }
        return result.getResult();
    }

    @Override
    public NetworkVO createNsxPublicNetwork(long zoneId) {
        LOGGER.debug(String.format("Creating a NSX Public Network for zone %s", zoneId));
        DataCenterVO zone = dataCenterDao.findById(zoneId);
        if (zone == null) {
            String err = String.format("Cannot find a zone with ID %s", zoneId);
            LOGGER.error(err);
            throw new CloudRuntimeException(err);
        }
        if (zone.getType() != DataCenter.Type.Core || zone.getNetworkType() != DataCenter.NetworkType.Advanced) {
            String err = String.format("Cannot create an NSX public network on the zone %s as it is not an Advanced Core Zone", zone.getName());
            LOGGER.error(err);
            throw new CloudRuntimeException(err);
        }
        NetworkOfferingVO nsxPublicNetworkOffering = networkOfferingDao.findByUniqueName(NetworkOffering.SystemNsxPublicNetworkOffering);
        if (nsxPublicNetworkOffering == null) {
            String err = String.format("Cannot find the NSX public network offering with unique name %s", NetworkOffering.SystemNsxPublicNetworkOffering);
            LOGGER.error(err);
            throw new CloudRuntimeException(err);
        }
        List<NetworkVO> publicNetworks = networkDao.listByZoneAndTrafficType(zoneId, Networks.TrafficType.Public).stream()
                .filter(x -> x.getNetworkOfferingId() == nsxPublicNetworkOffering.getId())
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(publicNetworks)) {
            String err = String.format("An NSX Public Network already exists on the zone %s", zone.getName());
            LOGGER.error(err);
            throw new CloudRuntimeException(err);
        }
        Account account = CallContext.current().getCallingAccount();
        long domainId = account.getDomainId();
        long id = networkDao.getNextInSequence(Long.class, "id");
        String networkName = String.format("NSX-Public-Network-%s", zone.getName());

        NetworkVO network = new NetworkVO(id, nsxPublicNetworkOffering.getTrafficType(), Networks.Mode.Static,
                Networks.BroadcastDomainType.NSX, nsxPublicNetworkOffering.getId(), domainId, account.getId(),
                200L, networkName, networkName, null, null, zoneId, null, null,
                true, null, false);
        network.setGuruName("NsxPublicNetworkGuru");
        return networkDao.persist(network, false, new HashMap<>());
    }
}
