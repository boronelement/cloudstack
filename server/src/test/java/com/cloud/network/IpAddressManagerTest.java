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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.rules.StaticNatImpl;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.AccountVO;
import com.cloud.utils.net.Ip;

public class IpAddressManagerTest {

    @Mock
    IPAddressDao _ipAddrDao;

    @Mock
    NetworkDao _networkDao;

    @Mock
    NetworkOfferingDao _networkOfferingDao;

    @Mock
    NetworkModel _networkModel;

    @Spy
    @InjectMocks
    IpAddressManagerImpl _ipManager;

    @InjectMocks
    NetworkModelImpl networkModel = Mockito.spy(new NetworkModelImpl());

    IPAddressVO ipAddressVO;

    NetworkVO networkAllocated;

    NetworkVO networkImplemented;

    NetworkVO networkNat;

    AccountVO account;

    @Before
    public void setup() throws ResourceUnavailableException {
        MockitoAnnotations.initMocks(this);

        ipAddressVO = new IPAddressVO(new Ip("192.0.0.1"), 1L, 1L, 1L,false);
        ipAddressVO.setAllocatedToAccountId(1L);

        IPAddressVO sourceNat = new IPAddressVO(new Ip("192.0.0.2"), 1L, 1L, 1L,true);

        networkAllocated = Mockito.mock(NetworkVO.class);
        when(networkAllocated.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(networkAllocated.getNetworkOfferingId()).thenReturn(8L);
        when(networkAllocated.getState()).thenReturn(Network.State.Allocated);
        when(networkAllocated.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkAllocated.getVpcId()).thenReturn(null);

        networkImplemented = Mockito.mock(NetworkVO.class);
        when(networkImplemented.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(networkImplemented.getNetworkOfferingId()).thenReturn(8L);
        when(networkImplemented.getState()).thenReturn(Network.State.Implemented);
        when(networkImplemented.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkImplemented.getVpcId()).thenReturn(null);

        networkNat = Mockito.mock(NetworkVO.class);
        when(networkNat.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(networkNat.getNetworkOfferingId()).thenReturn(8L);
        when(networkNat.getState()).thenReturn(Network.State.Implemented);
        when(networkNat.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(networkNat.getId()).thenReturn(3L);
        when(networkNat.getVpcId()).thenReturn(null);

        account = new AccountVO("admin", 1L, null, (short) 1, 1L, "c65a73d5-ebbd-11e7-8f45-107b44277808");
        account.setId(1L);

        NetworkOfferingVO networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        networkOfferingVO.setSharedSourceNat(false);

        Mockito.when(_networkDao.findById(1L)).thenReturn(networkAllocated);
        Mockito.when(_networkDao.findById(2L)).thenReturn(networkImplemented);
        Mockito.when(_networkDao.findById(3L)).thenReturn(networkNat);
        Mockito.when(_networkOfferingDao.findById(Mockito.anyLong())).thenReturn(networkOfferingVO);
        doReturn(null).when(_ipManager).getExistingSourceNatInNetwork(1L, 1L);
        doReturn(sourceNat).when(_ipManager).getExistingSourceNatInNetwork(1L, 3L);
        when(_networkModel.areServicesSupportedInNetwork(0L, Network.Service.SourceNat)).thenReturn(true);
    }

    @Test
    public void testGetStaticNatSourceIps() {
        String publicIpAddress = "192.168.1.3";
        IPAddressVO vo = mock(IPAddressVO.class);
        when(vo.getAddress()).thenReturn(new Ip(publicIpAddress));
        when(vo.getId()).thenReturn(1l);

        when(_ipAddrDao.findById(anyLong())).thenReturn(vo);
        StaticNat snat = new StaticNatImpl(1, 1, 1, 1, publicIpAddress, false);

        List<IPAddressVO> ips = _ipManager.getStaticNatSourceIps(Collections.singletonList(snat));
        Assert.assertNotNull(ips);
        Assert.assertEquals(1, ips.size());

        IPAddressVO returnedVO = ips.get(0);
        Assert.assertEquals(vo, returnedVO);
    }

    @Test
    public void isIpEqualsGatewayOrNetworkOfferingsEmptyTestRequestedIpEqualsIp6Gateway() {
        Network network = setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(0l, "gateway", "ip6Gateway", null, new ArrayList<Service>());

        boolean result = _ipManager.isIpEqualsGatewayOrNetworkOfferingsEmpty(network, "ip6Gateway");

        Mockito.verify(networkModel, Mockito.times(0)).listNetworkOfferingServices(Mockito.anyLong());
        Assert.assertTrue(result);
    }

    @Test
    public void isIpEqualsGatewayOrNetworkOfferingsEmptyTestRequestedIpEqualsGateway() {
        Network network = setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(0l, "gateway", "ip6Gateway", null, new ArrayList<Service>());

        boolean result = _ipManager.isIpEqualsGatewayOrNetworkOfferingsEmpty(network, "gateway");

        Mockito.verify(networkModel, Mockito.times(0)).listNetworkOfferingServices(Mockito.anyLong());
        Assert.assertTrue(result);
    }

    @Test
    public void isIpEqualsGatewayOrNetworkOfferingsEmptyTestExpectFalseServicesNotEmpty() {
        List<Service> services = new ArrayList<Service>();
        Service serviceGateway = new Service("Gateway");
        services.add(serviceGateway);
        Network network = setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(0l, "gateway", "ip6Gateway", null, services);

        boolean result = _ipManager.isIpEqualsGatewayOrNetworkOfferingsEmpty(network, "requestedIp");

        Mockito.verify(networkModel).listNetworkOfferingServices(Mockito.anyLong());
        Assert.assertFalse(result);
    }

    @Test
    public void isIpEqualsGatewayOrNetworkOfferingsEmptyTestExpectFalseServicesCidrNotNull() {
        Network network = setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(0l, "gateway", "ip6Gateway", "cidr", new ArrayList<Service>());

        boolean result = _ipManager.isIpEqualsGatewayOrNetworkOfferingsEmpty(network, "requestedIp");

        Mockito.verify(networkModel).listNetworkOfferingServices(Mockito.anyLong());
        Assert.assertFalse(result);
    }

    @Test
    public void assertSourceNatImplementedNetwork() {

        boolean isSourceNat = _ipManager.isSourceNatAvailableForNetwork(1L, account, ipAddressVO, networkImplemented);

        assertTrue("Source NAT should be true", isSourceNat);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void assertSourceNatAllocatedNetwork() {

        _ipManager.isSourceNatAvailableForNetwork(2L, account, ipAddressVO, networkAllocated);
    }

    @Test
    public void assertExistingSourceNatAllocatedNetwork() {

        boolean isSourceNat = _ipManager.isSourceNatAvailableForNetwork(3L, account, ipAddressVO, networkNat);

        assertFalse("Source NAT should be false", isSourceNat);
    }

    @Test
    public void isIpEqualsGatewayOrNetworkOfferingsEmptyTestNetworkOfferingsEmptyAndCidrNull() {
        Network network = setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(0l, "gateway", "ip6Gateway", null, new ArrayList<Service>());

        boolean result = _ipManager.isIpEqualsGatewayOrNetworkOfferingsEmpty(network, "requestedIp");

        Mockito.verify(networkModel).listNetworkOfferingServices(Mockito.anyLong());
        Assert.assertTrue(result);
    }

    private Network setTestIsIpEqualsGatewayOrNetworkOfferingsEmpty(long networkOfferingId, String gateway, String ip6Gateway, String cidr, List<Service> services) {
        Network network = mock(Network.class);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(networkOfferingId);
        Mockito.when(network.getGateway()).thenReturn(gateway);
        Mockito.when(network.getIp6Gateway()).thenReturn(ip6Gateway);
        Mockito.when(network.getCidr()).thenReturn(cidr);
        Mockito.doReturn(services).when(networkModel).listNetworkOfferingServices(Mockito.anyLong());
        return network;
    }

}