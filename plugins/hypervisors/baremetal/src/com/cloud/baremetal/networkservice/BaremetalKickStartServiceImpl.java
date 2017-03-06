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
//
// Automatically generated by addcopyright.py at 01/29/2013
package com.cloud.baremetal.networkservice;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand;
import com.cloud.agent.api.baremetal.IpmISetBootDevCommand.BootDev;
import com.cloud.agent.api.routing.VmDataCommand;
import com.cloud.baremetal.database.BaremetalPxeDao;
import com.cloud.baremetal.database.BaremetalPxeVO;
import com.cloud.baremetal.networkservice.BaremetalPxeManager.BaremetalPxeType;
import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.guru.ControlNetworkGuru;
import com.cloud.network.router.VirtualRouter;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ServerResource;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.api.AddBaremetalKickStartPxeCmd;
import org.apache.cloudstack.api.AddBaremetalPxeCmd;
import org.apache.cloudstack.api.ListBaremetalPxeServersCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.log4j.Logger;

import javax.ejb.Local;
import javax.inject.Inject;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Local(value = BaremetalPxeService.class)
public class BaremetalKickStartServiceImpl extends BareMetalPxeServiceBase implements BaremetalPxeService {
    private static final Logger s_logger = Logger.getLogger(BaremetalKickStartServiceImpl.class);
    @Inject
    protected ResourceManager _resourceMgr;
    @Inject
    protected PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    protected PhysicalNetworkServiceProviderDao _physicalNetworkServiceProviderDao;
    @Inject
    protected BaremetalPxeDao _pxeDao;
    @Inject
    VMTemplateDao _tmpDao;
    @Inject
    DomainRouterDao _routerDao;
    @Inject
    NicDao _nicDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    EntityManager _entityMgr;
    @Inject
    NetworkOfferingDetailsDao _ntwkOffDetailsDao;
    @Inject
    UserVmDao _vmDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    NetworkModel _ntwkModel;
    @Inject
    NetworkOfferingDao _ntwkOffDao;

    private DomainRouterVO getVirtualRouter(Network network) {
        List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER);

        if (routers.isEmpty()) {
            throw new CloudRuntimeException(String.format("cannot find any running virtual router on network[id:%s, uuid:%s]", network.getId(), network.getUuid()));
        }

        NetworkOfferingVO ntwkOffering = _ntwkOffDao.findById(network.getNetworkOfferingId());
        if ( ntwkOffering != null && ntwkOffering.getRedundantRouter() ) {
            for (DomainRouterVO vr : routers) {
                if (vr.getRedundantState() == VirtualRouter.RedundantState.MASTER) {
                    if (!Hypervisor.HypervisorType.VMware.equals(vr.getHypervisorType())) {
                        throw new CloudRuntimeException(String.format("baremetal only support vmware virtual router, but get %s", vr.getHypervisorType()));
                    }
                    return vr;
                }
            }
        } else {
            DomainRouterVO vr = routers.get(0);
            if (!Hypervisor.HypervisorType.VMware.equals(vr.getHypervisorType())) {
                throw new CloudRuntimeException(String.format("baremetal only support vmware virtual router, but get %s", vr.getHypervisorType()));
            }
            return vr;
        }

        return null;
    }

    private List<String> parseKickstartUrl(VirtualMachineProfile profile) {
        String tpl = profile.getTemplate().getUrl();
        assert tpl != null : "How can a null template get here!!!";
        String[] tpls = tpl.split(";");
        CloudRuntimeException err =
                new CloudRuntimeException(
                        String.format(
                                "template url[%s] is not correctly encoded. it must be in format of ks=http_link_to_kickstartfile;kernel=nfs_path_to_pxe_kernel;initrd=nfs_path_to_pxe_initrd",
                                tpl));
        if (tpls.length != 3) {
            throw err;
        }

        String ks = null;
        String kernel = null;
        String initrd = null;

        for (String t : tpls) {
            String[] kv = t.split("=");
            if (kv.length != 2) {
                throw err;
            }
            if (kv[0].equals("ks")) {
                ks = kv[1];
            } else if (kv[0].equals("kernel")) {
                kernel = kv[1];
            } else if (kv[0].equals("initrd")) {
                initrd = kv[1];
            } else {
                throw err;
            }
        }

        return Arrays.asList(ks, kernel, initrd);
    }

    public File getSystemVMKeyFile() {
        URL url = this.getClass().getClassLoader().getResource("scripts/vm/systemvm/id_rsa.cloud");
        File keyFile = null;
        if (url != null) {
            keyFile = new File(url.getPath());
        }
        if (keyFile == null || !keyFile.exists()) {
            keyFile = new File("/usr/share/cloudstack-common/scripts/vm/systemvm/id_rsa.cloud");
        }
        if (!keyFile.exists()) {
            throw new CloudRuntimeException(String.format("cannot find id_rsa.cloud"));
        }
        if (!keyFile.exists()) {
            s_logger.error("Unable to locate id_rsa.cloud in your setup at " + keyFile.toString());
        }
        return keyFile;
    }

    private boolean preparePxeInBasicZone(VirtualMachineProfile profile, NicProfile nic, DeployDestination dest,
                                          ReservationContext context, List<BaremetalPxeVO> bareMetalPxeVOs)
            throws AgentUnavailableException, OperationTimedoutException {
        BaremetalPxeVO pxeVo = bareMetalPxeVOs.get(0); // Only one PXE of type KICK_START allowed in zone, check addPxeServer
        VMTemplateVO template = _tmpDao.findById(profile.getTemplateId());
        List<String> tuple =  parseKickstartUrl(profile);

        String ks = tuple.get(0);
        String kernel = tuple.get(1);
        String initrd = tuple.get(2);

        PrepareKickstartPxeServerCommand cmd = new PrepareKickstartPxeServerCommand();
        cmd.setKsFile(ks);
        cmd.setInitrd(initrd);
        cmd.setKernel(kernel);
        cmd.setMac(nic.getMacAddress());
        cmd.setTemplateUuid(template.getUuid());
        Answer aws = _agentMgr.send(pxeVo.getHostId(), cmd);
        if (!aws.getResult()) {
            s_logger.warn("Unable to set host: " + dest.getHost().getId() + " to PXE boot because " + aws.getDetails());
            return false;
        }

        return true;
    }

    private boolean preparePxeInAdvancedZone(VirtualMachineProfile profile, NicProfile nic, Network network, DeployDestination dest, ReservationContext context) throws Exception {
        DomainRouterVO vr = getVirtualRouter(network);
        if (vr == null) {
            throw new CloudRuntimeException(String.format("cannot find any running virtual router on network[id:%s, uuid:%s]", network.getId(), network.getUuid()));
        }
        List<NicVO> nics = _nicDao.listByVmId(vr.getId());
        NicVO mgmtNic = null;
        for (NicVO nicvo : nics) {
            if (ControlNetworkGuru.class.getSimpleName().equals(nicvo.getReserver())) {
                mgmtNic = nicvo;
                break;
            }
        }

        if (mgmtNic == null) {
            throw new CloudRuntimeException(String.format("cannot find management nic on virtual router[id:%s]", vr.getId()));
        }

        //get network offering details
        NetworkOffering off = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
        String interalStorageServerIp = _ntwkOffDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.BaremetalInternalStorageServerIP);

        if (interalStorageServerIp == null) {
            // If storage server IP is not mentioned in network offering read it from global configuration parameter
            interalStorageServerIp = _configDao.getValue(Config.BaremetalInternalStorageServer.key());
            if (interalStorageServerIp == null) {
                throw new CloudRuntimeException(String.format("please specify 'baremetal.internal.storage.server.ip', which is the http server/nfs server storing kickstart files and ISO files, in global setting"));
            }
        }

        List<String> tuple =  parseKickstartUrl(profile);
        String cmd =  String.format("/opt/cloud/bin/prepare_pxe.sh %s %s %s %s %s %s", tuple.get(1), tuple.get(2), profile.getTemplate().getUuid(),
                String.format("01-%s", nic.getMacAddress().replaceAll(":", "-")).toLowerCase(), tuple.get(0), nic.getMacAddress().toLowerCase());
        s_logger.debug(String.format("prepare pxe on virtual router[ip:%s], cmd: %s", mgmtNic.getIPv4Address(), cmd));
        Pair<Boolean, String> ret = SshHelper.sshExecute(mgmtNic.getIPv4Address(), 3922, "root", getSystemVMKeyFile(), null, cmd);
        if (!ret.first()) {
            throw new CloudRuntimeException(String.format("failed preparing PXE in virtual router[id:%s], because %s", vr.getId(), ret.second()));
        }

        //String internalServerIp = "10.223.110.231";
        cmd = String.format("/opt/cloud/bin/baremetal_snat.sh %s %s %s", mgmtNic.getIPv4Address(), interalStorageServerIp, mgmtNic.getIPv4Gateway());
        s_logger.debug(String.format("prepare SNAT on virtual router[ip:%s], cmd: %s", mgmtNic.getIPv4Address(), cmd));
        ret = SshHelper.sshExecute(mgmtNic.getIPv4Address(), 3922, "root", getSystemVMKeyFile(), null, cmd);
        if (!ret.first()) {
            throw new CloudRuntimeException(String.format("failed preparing PXE in virtual router[id:%s], because %s", vr.getId(), ret.second()));
        }

        return true;
    }

    @Override
    public boolean prepare(VirtualMachineProfile profile, NicProfile nic, Network network, DeployDestination dest, ReservationContext context, List<BaremetalPxeVO> bareMetalPxeVOs) {
        try {
            if (DataCenter.NetworkType.Basic.equals(dest.getDataCenter().getNetworkType())) {
                if (!preparePxeInBasicZone(profile, nic, dest, context, bareMetalPxeVOs)) {
                    return false;
                }
            } else {
                if (!preparePxeInAdvancedZone(profile, nic, network, dest, context)) {
                    return false;
                }
            }

            IpmISetBootDevCommand bootCmd = new IpmISetBootDevCommand(BootDev.pxe);
            Answer aws = _agentMgr.send(profile.getVirtualMachine().getHostId(), bootCmd);
            if (!aws.getResult()) {
                s_logger.warn("Unable to set host: " + dest.getHost().getId() + " to PXE boot because " + aws.getDetails());
            }

            return aws.getResult();
        } catch (Exception e) {
            s_logger.warn("Cannot prepare PXE server", e);
            return false;
        }
    }

    @Override
    public boolean prepareCreateTemplate(Long pxeServerId, UserVm vm, String templateUrl) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    @DB
    public BaremetalPxeVO addPxeServer(AddBaremetalPxeCmd cmd) {
        AddBaremetalKickStartPxeCmd kcmd = (AddBaremetalKickStartPxeCmd)cmd;
        PhysicalNetworkVO pNetwork = null;
        long zoneId;

        if (cmd.getPhysicalNetworkId() == null || cmd.getUrl() == null || cmd.getUsername() == null || cmd.getPassword() == null) {
            throw new IllegalArgumentException("At least one of the required parameters(physical network id, url, username, password) is null");
        }

        pNetwork = _physicalNetworkDao.findById(cmd.getPhysicalNetworkId());
        if (pNetwork == null) {
            throw new IllegalArgumentException("Could not find phyical network with ID: " + cmd.getPhysicalNetworkId());
        }
        zoneId = pNetwork.getDataCenterId();

        PhysicalNetworkServiceProviderVO ntwkSvcProvider =
                _physicalNetworkServiceProviderDao.findByServiceProvider(pNetwork.getId(), Network.Provider.BAREMETAL_PXE_SERVICE_PROVIDER.getName());
        if (ntwkSvcProvider == null) {
            throw new CloudRuntimeException("Network Service Provider: " + Network.Provider.BAREMETAL_PXE_SERVICE_PROVIDER.getName() +
                    " is not enabled in the physical network: " + cmd.getPhysicalNetworkId() + "to add this device");
        } else if (ntwkSvcProvider.getState() == PhysicalNetworkServiceProvider.State.Shutdown) {
            throw new CloudRuntimeException("Network Service Provider: " + ntwkSvcProvider.getProviderName() + " is in shutdown state in the physical network: " +
                    cmd.getPhysicalNetworkId() + "to add this device");
        }

        List<HostVO> pxes = _resourceMgr.listAllHostsInOneZoneByType(Host.Type.BaremetalPxe, zoneId);
        if (!pxes.isEmpty()) {
            throw new IllegalArgumentException("Already had a PXE server zone: " + zoneId);
        }

        String tftpDir = kcmd.getTftpDir();
        if (tftpDir == null) {
            throw new IllegalArgumentException("No TFTP directory specified");
        }

        URI uri;
        try {
            uri = new URI(cmd.getUrl());
        } catch (Exception e) {
            s_logger.debug(e);
            throw new IllegalArgumentException(e.getMessage());
        }
        String ipAddress = uri.getHost();
        if (ipAddress == null) {
            ipAddress = cmd.getUrl();
        }

        String guid = getPxeServerGuid(Long.toString(zoneId), BaremetalPxeType.KICK_START.toString(), ipAddress);

        ServerResource resource = null;
        Map params = new HashMap<String, String>();
        params.put(BaremetalPxeService.PXE_PARAM_ZONE, Long.toString(zoneId));
        params.put(BaremetalPxeService.PXE_PARAM_IP, ipAddress);
        params.put(BaremetalPxeService.PXE_PARAM_USERNAME, cmd.getUsername());
        params.put(BaremetalPxeService.PXE_PARAM_PASSWORD, cmd.getPassword());
        params.put(BaremetalPxeService.PXE_PARAM_TFTP_DIR, tftpDir);
        params.put(BaremetalPxeService.PXE_PARAM_GUID, guid);
        resource = new BaremetalKickStartPxeResource();
        try {
            resource.configure("KickStart PXE resource", params);
        } catch (Exception e) {
            throw new CloudRuntimeException(e.getMessage(), e);
        }

        Host pxeServer = _resourceMgr.addHost(zoneId, resource, Host.Type.BaremetalPxe, params);
        if (pxeServer == null) {
            throw new CloudRuntimeException("Cannot add PXE server as a host");
        }

        BaremetalPxeVO vo = new BaremetalPxeVO();
        vo.setHostId(pxeServer.getId());
        vo.setNetworkServiceProviderId(ntwkSvcProvider.getId());
        vo.setPhysicalNetworkId(kcmd.getPhysicalNetworkId());
        vo.setDeviceType(BaremetalPxeType.KICK_START.toString());
        _pxeDao.persist(vo);
        return vo;
    }

    @Override
    public BaremetalPxeResponse getApiResponse(BaremetalPxeVO vo) {
        BaremetalPxeResponse response = new BaremetalPxeResponse();
        response.setId(vo.getUuid());
        HostVO host = _hostDao.findById(vo.getHostId());
        response.setUrl(host.getPrivateIpAddress());
        PhysicalNetworkServiceProviderVO providerVO = _physicalNetworkServiceProviderDao.findById(vo.getNetworkServiceProviderId());
        response.setPhysicalNetworkId(providerVO.getUuid());
        PhysicalNetworkVO nwVO = _physicalNetworkDao.findById(vo.getPhysicalNetworkId());
        response.setPhysicalNetworkId(nwVO.getUuid());
        response.setObjectName("baremetalpxeserver");
        return response;
    }

    @Override
    public List<BaremetalPxeResponse> listPxeServers(ListBaremetalPxeServersCmd cmd) {
        List<BaremetalPxeResponse> responses = new ArrayList<BaremetalPxeResponse>();
        if (cmd.getId() != null) {
            BaremetalPxeVO vo = _pxeDao.findById(cmd.getId());
            responses.add(getApiResponse(vo));
            return responses;
        }

        QueryBuilder<BaremetalPxeVO> sc = QueryBuilder.create(BaremetalPxeVO.class);
        sc.and(sc.entity().getPhysicalNetworkId(), Op.EQ, cmd.getPhysicalNetworkId());
        List<BaremetalPxeVO> vos = sc.list();
        for (BaremetalPxeVO vo : vos) {
            responses.add(getApiResponse(vo));
        }
        return responses;
    }

    @Override
    public String getPxeServiceType() {
        return BaremetalPxeManager.BaremetalPxeType.KICK_START.toString();
    }

    @Override
    public boolean addUserData(NicProfile nic, VirtualMachineProfile profile, List<BaremetalPxeVO> bareMetalPxeVOs) {
        UserVmVO vm = _vmDao.findById(profile.getVirtualMachine().getId());
        _vmDao.loadDetails(vm);

        String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
        String zoneName = _dcDao.findById(vm.getDataCenterId()).getName();
        NicVO nvo = _nicDao.findById(nic.getId());
        VmDataCommand cmd = new VmDataCommand(nvo.getIPv4Address(), vm.getInstanceName(), _ntwkModel.getExecuteInSeqNtwkElmtCmd());
        // if you add new metadata files, also edit systemvm/patches/debian/config/var/www/html/latest/.htaccess
        cmd.addVmData("userdata", "user-data", vm.getUserData());
        cmd.addVmData("metadata", "service-offering", StringUtils.unicodeEscape(serviceOffering));
        cmd.addVmData("metadata", "availability-zone", StringUtils.unicodeEscape(zoneName));
        cmd.addVmData("metadata", "local-ipv4", nic.getIPv4Address());
        cmd.addVmData("metadata", "local-hostname", StringUtils.unicodeEscape(vm.getInstanceName()));
        cmd.addVmData("metadata", "public-ipv4", nic.getIPv4Address());
        cmd.addVmData("metadata", "public-hostname", StringUtils.unicodeEscape(vm.getInstanceName()));
        cmd.addVmData("metadata", "instance-id", String.valueOf(vm.getUuid()));
        cmd.addVmData("metadata", "vm-id", String.valueOf(vm.getUuid()));
        cmd.addVmData("metadata", "public-keys", vm.getDetail("SSH.PublicKey"));
        String cloudIdentifier = _configDao.getValue("cloud.identifier");
        if (cloudIdentifier == null) {
            cloudIdentifier = "";
        } else {
            cloudIdentifier = "CloudStack-{" + cloudIdentifier + "}";
        }
        cmd.addVmData("metadata", "cloud-identifier", cloudIdentifier);

        BaremetalPxeVO pxeVo = bareMetalPxeVOs.get(0);
        try {
            Answer ans = _agentMgr.send(pxeVo.getHostId(), cmd);
            if (!ans.getResult()) {
                s_logger.debug(String.format("Add userdata to vm:%s failed because %s", vm.getInstanceName(), ans.getDetails()));
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            s_logger.debug(String.format("Add userdata to vm:%s failed", vm.getInstanceName()), e);
            return false;
        }
    }

}
