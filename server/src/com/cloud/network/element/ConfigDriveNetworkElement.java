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
package com.cloud.network.element;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.log4j.Logger;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.AttachIsoAnswer;
import com.cloud.agent.api.AttachIsoCommand;
import com.cloud.agent.api.HandleConfigDriveIsoCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.dao.NetworkDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmDetailVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;

public class ConfigDriveNetworkElement extends AdapterBase implements NetworkElement, UserDataServiceProvider,
        StateListener<VirtualMachine.State, VirtualMachine.Event, VirtualMachine>, NetworkMigrationResponder, Configurable {
    private static final Logger s_logger = Logger.getLogger(ConfigDriveNetworkElement.class);

    private static final Map<Service, Map<Capability, String>> capabilities = setCapabilities();

    @Inject
    NetworkDao _networkConfigDao;
    @Inject
    NetworkModel _networkMgr;
    @Inject
    UserVmManager _userVmMgr;
    @Inject
    UserVmDao _userVmDao;
    @Inject
    UserVmDetailsDao _userVmDetailsDao;
    @Inject
    DomainRouterDao _routerDao;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    AgentManager _agentManager;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    GuestOSDao _guestOSDao;
    @Inject
    HostDao _hostDao;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    EndPointSelector _ep;
    @Inject
    VolumeOrchestrationService _volumeMgr;

    private final static String CONFIGDRIVEFILENAME = "configdrive.iso";
    private final static String CONFIGDRIVEDIR = "ConfigDrive";
    private final static Integer CONFIGDRIVEDISKSEQ = 4;

    private boolean canHandle(TrafficType trafficType) {
        return trafficType.equals(TrafficType.Guest);
    }

    @Override
    public boolean start() {
        VirtualMachine.State.getStateMachine().registerListener(this);
        return super.start();
    }

    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context) throws ResourceUnavailableException, ConcurrentOperationException,
            InsufficientCapacityException {
        return canHandle(offering.getTrafficType());
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile vmProfile, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException,
            InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile vm, ReservationContext context) {
        if (!nic.isDefaultNic()) {
            return true;
        }

        DataStore dataStore = getDataStore(network, vm);

        String isoFile =  "/" + CONFIGDRIVEDIR + "/" + vm.getInstanceName()+ "/" + CONFIGDRIVEFILENAME;
        HandleConfigDriveIsoCommand deleteCommand = new HandleConfigDriveIsoCommand(vm.getVmData(),
                vm.getConfigDriveLabel(), dataStore.getTO(), isoFile, false, false);
        s_logger.info("Delete the ISO on the secondary store; " + dataStore);
        EndPoint endpoint = _ep.select(dataStore);
        if (endpoint == null) {
            s_logger.error(String.format("ConfigDrive store: %s not available", dataStore.getName()));
            return false;
        }
        Answer answer = endpoint.sendMessage(deleteCommand);
        return answer.getResult();
    }

    private DataStore getDataStore(Network network, VirtualMachineProfile vm) {
        DataStore dataStore;
        if(UsePrimaryStorage.value() && Hypervisor.HypervisorType.KVM.equals(vm.getHypervisorType())) {
            // get the primary storage foor the vm?????
            dataStore = getPrimaryDatastoreForVM(vm);
        } else {
            // Remove form secondary storage
            dataStore = _dataStoreMgr.getImageStore(network.getDataCenterId());
        }
        return dataStore;
    }

    private DataStore getPrimaryDatastoreForVM(VirtualMachineProfile vm) {
        List<DiskTO> list = vm.getDisks();
        if (list.isEmpty()) {
            throw new CloudRuntimeException("no storage known for vm " + vm.getInstanceName() + " (uuid == " + vm.getUuid()+")");
        }
        // put it next to the root disk, assyou+meing that is disk number zero ;)
        DataStore store = _dataStoreMgr.getPrimaryDataStore(list.get(0).getData().getDataStore().getUuid());
        return store;
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public boolean destroy(Network config, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public Provider getProvider() {
        return Provider.ConfigDrive;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    private static Map<Service, Map<Capability, String>> setCapabilities() {
        Map<Service, Map<Capability, String>> capabilities = new HashMap<>();
        capabilities.put(Service.UserData, null);
        return capabilities;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return false;
    }

    private String getSshKey(VirtualMachineProfile profile) {
        UserVmDetailVO vmDetailSshKey = _userVmDetailsDao.findDetail(profile.getId(), "SSH.PublicKey");
        return (vmDetailSshKey!=null ? vmDetailSshKey.getValue() : null);
    }

    @Override
    public boolean addPasswordAndUserdata(Network network, NicProfile nic, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        String sshPublicKey = getSshKey(profile);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("adding password and userdata to configdrive for vm " + profile.getInstanceName());
        }
        return (canHandle(network.getTrafficType())
                && updateConfigDrive(profile, sshPublicKey, nic))
                && updateConfigDriveIso(network, profile, dest.getHost(), false);
    }

    @Override
    public boolean savePassword(Network network, NicProfile nic, VirtualMachineProfile profile) throws ResourceUnavailableException {
        String sshPublicKey = getSshKey(profile);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("saving password to configdrive for vm " + profile.getInstanceName());
        }
        if (!(canHandle(network.getTrafficType()) && updateConfigDrive(profile, sshPublicKey, nic))) return false;
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("update configdrive iso with password for vm " + profile.getInstanceName());
        }
        return updateConfigDriveIso(network, profile, true);
    }

    @Override
    public boolean saveSSHKey(Network network, NicProfile nic, VirtualMachineProfile vm, String sshPublicKey) throws ResourceUnavailableException {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("saving ssh key  to configdrive for vm " + vm.getInstanceName());
        }
        if (!(canHandle(network.getTrafficType()) && updateConfigDrive(vm, sshPublicKey, nic))) return false;
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("updating configdrive iso with ssh key for vm " + vm.getInstanceName());
        }
        return updateConfigDriveIso(network, vm, true);
    }

    @Override
    public boolean saveUserData(Network network, NicProfile nic, VirtualMachineProfile profile) throws ResourceUnavailableException {
        String sshPublicKey = getSshKey(profile);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("saving userdata to configdrive for vm " + profile.getInstanceName());
        }
        if (!(canHandle(network.getTrafficType()) && updateConfigDrive(profile, sshPublicKey, nic))) return false;
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("updating configdrive iso with userdata for vm " + profile.getInstanceName());
        }
        return updateConfigDriveIso(network, profile, true);
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }

    @Override
    public boolean preStateTransitionEvent(VirtualMachine.State oldState, VirtualMachine.Event event, VirtualMachine.State newState, VirtualMachine vo, boolean status, Object opaque) {
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition, VirtualMachine vo, boolean status, Object opaque) {
        if (transition.getToState().equals(VirtualMachine.State.Expunging) && transition.getEvent().equals(VirtualMachine.Event.ExpungeOperation)) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("handling expunge state for configdrive of vm " + vo.getInstanceName());
            }
            Nic nic = _networkModel.getDefaultNic(vo.getId());
            try {
                if (nic != null) {
                    final Network network = _networkMgr.getNetwork(nic.getNetworkId());
                    final UserDataServiceProvider userDataUpdateProvider = _networkModel.getUserDataUpdateProvider(network);
                    final Provider provider = userDataUpdateProvider.getProvider();
                    if (provider.equals(Provider.ConfigDrive)) {
                        // Delete config drive ISO on destroy
                        DataStore dataStore = _dataStoreMgr.getImageStore(vo.getDataCenterId());
                        String isoFile = "/" + CONFIGDRIVEDIR + "/" + vo.getInstanceName() + "/" + CONFIGDRIVEFILENAME;
                        HandleConfigDriveIsoCommand deleteCommand = new HandleConfigDriveIsoCommand(null,
                                null, dataStore.getTO(), isoFile, false, false);
                        EndPoint endpoint = _ep.select(dataStore);
                        if (endpoint == null) {
                            s_logger.error(String.format("Secondary store: %s not available", dataStore.getName()));
                            return false;
                        }
                        Answer answer = endpoint.sendMessage(deleteCommand);
                        if (!answer.getResult()) {
                            s_logger.error(String.format("Update ISO failed, details: %s", answer.getDetails()));
                            return false;
                        }
                    }
                }
            } catch (UnsupportedServiceException usse) {
                s_logger.debug("not supported configdrive removal detected",usse);
            }
        }
        return true;
    }

    @Override
    public boolean prepareMigration(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("preparing configdrive for migration for vm " + vm.getInstanceName());
        }
        if (nic.isDefaultNic() && _networkModel.getUserDataUpdateProvider(network).getProvider().equals(Provider.ConfigDrive)) {
            s_logger.trace(String.format("[prepareMigration] for vm: %s", vm.getInstanceName()));
            DataStore dataStore = getDataStore(network,vm);
            configureConfigDriveDisk(vm, dataStore);
            return false;
        }
        else return  true;
    }

    @Override
    public void rollbackMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {

    }

    @Override
    public void commitMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {

    }

    private boolean updateConfigDriveIso(Network network, VirtualMachineProfile profile, boolean update) throws ResourceUnavailableException {
        return updateConfigDriveIso(network, profile, null, update);
    }

    private boolean updateConfigDriveIso(Network network, VirtualMachineProfile profile, Host host, boolean update) throws ResourceUnavailableException {
        Integer deviceKey = null;
        Long hostId;
        if (host == null) {
            hostId = (profile.getVirtualMachine().getHostId() == null ? profile.getVirtualMachine().getLastHostId(): profile.getVirtualMachine().getHostId());
        } else {
            hostId = host.getId();
        }

        DataStore dataStore = getDataStore(network,profile);
        // Detach the existing ISO file if the machine is running
        if (update && profile.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            s_logger.debug("Detach config drive ISO for  vm " + profile.getInstanceName() + " in host " + _hostDao.findById(hostId));
            deviceKey = detachIso(dataStore, profile.getInstanceName(), hostId);
        }

        // Create/Update the iso on the data store
        if (s_logger.isDebugEnabled()) {
            s_logger.debug(String.format("%s config drive ISO for  vm %s in host %s",
                    (update ? "update" : "create"),
                    profile.getInstanceName(),
                    _hostDao.findById(hostId).getName()));
        }
        EndPoint endpoint = _ep.select(dataStore);
        if (endpoint == null) {
            throw new ResourceUnavailableException(String.format("%s failed, secondary store not available", (update ? "Update" : "Create")), dataStore.getClass(),
                                                   dataStore.getId());
        }
        String isoPath = CONFIGDRIVEDIR + "/" + profile.getInstanceName() + "/"  + CONFIGDRIVEFILENAME;
        HandleConfigDriveIsoCommand configDriveIsoCommand = new HandleConfigDriveIsoCommand(profile.getVmData(),
                profile.getConfigDriveLabel(), dataStore.getTO(), isoPath, true, update);
        Answer createIsoAnswer = endpoint.sendMessage(configDriveIsoCommand);
        if (!createIsoAnswer.getResult()) {
            throw new ResourceUnavailableException(String.format("%s ISO failed, details: %s",
                    (update?"Update":"Create"), createIsoAnswer.getDetails()), ConfigDriveNetworkElement.class, 0L);
        }
        configureConfigDriveDisk(profile, dataStore);

        // Re-attach the ISO if the machine is running
        if (update && profile.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            s_logger.debug("Re-attach config drive ISO for  vm " + profile.getInstanceName() + " in host " + _hostDao.findById(hostId));
            attachIso(dataStore, profile.getInstanceName(), hostId, deviceKey);
        }
        return true;

    }

    private void configureConfigDriveDisk(VirtualMachineProfile profile, DataStore dataStore) {
        boolean isoAvailable = false;
        String isoPath = CONFIGDRIVEDIR + "/" + profile.getInstanceName() + "/"  + CONFIGDRIVEFILENAME;
        for (DiskTO dataTo : profile.getDisks()) {
            if (dataTo.getPath().equals(isoPath)) {
                isoAvailable = true;
                break;
            }
        }
        if (!isoAvailable) {
            TemplateObjectTO dataTO = new TemplateObjectTO();
            dataTO.setDataStore(dataStore.getTO());
            dataTO.setUuid(profile.getUuid());
            dataTO.setPath(isoPath);
            dataTO.setFormat(Storage.ImageFormat.ISO);

            profile.addDisk(new DiskTO(dataTO, CONFIGDRIVEDISKSEQ.longValue(), isoPath, Volume.Type.ISO));
        }
    }

    private boolean updateConfigDrive(VirtualMachineProfile profile, String publicKey, NicProfile nic) {
        UserVmVO vm = _userVmDao.findById(profile.getId());
        if (vm.getType() != VirtualMachine.Type.User) {
            return false;
        }
        // add/update userdata and/or password info into vm profile
        Nic defaultNic = _networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            final String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
            boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

            List<String[]> vmData = _networkModel.generateVmData(vm.getUserData(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getId(),
                    vm.getUuid(), nic.getIPv4Address(), publicKey, (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows);
            profile.setVmData(vmData);
            profile.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
        }
        return true;
    }

    private Integer detachIso (DataStore dataStore, String instanceName, Long hostId) throws ResourceUnavailableException {
        String isoPath = CONFIGDRIVEDIR + "/" + instanceName + "/"  + CONFIGDRIVEFILENAME;
        AttachIsoCommand isoCommand = new AttachIsoCommand(instanceName, dataStore.getUri() + "/" + isoPath, false, CONFIGDRIVEDISKSEQ, true);
        isoCommand.setStoreUrl(dataStore.getUri());
        Answer attachIsoAnswer = null;

        try {
            attachIsoAnswer = _agentManager.send(hostId, isoCommand);
        } catch (OperationTimedoutException e) {
            throw new ResourceUnavailableException("Detach ISO failed: " + e.getMessage(), ConfigDriveNetworkElement.class, 0L);
        }

        if (!attachIsoAnswer.getResult()) {
            throw new ResourceUnavailableException("Detach ISO failed: " + attachIsoAnswer.getDetails(), ConfigDriveNetworkElement.class, 0L);
        }

        if (attachIsoAnswer instanceof  AttachIsoAnswer) {
            return ((AttachIsoAnswer)attachIsoAnswer).getDeviceKey();
        } else {
            return CONFIGDRIVEDISKSEQ;
        }
    }

    private void attachIso (DataStore dataStore, String instanceName, Long hostId, Integer deviceKey) throws ResourceUnavailableException {
        String isoPath = CONFIGDRIVEDIR + "/" + instanceName + "/"  + CONFIGDRIVEFILENAME;
        AttachIsoCommand isoCommand = new AttachIsoCommand(instanceName, dataStore.getUri() + "/" + isoPath, true);
        isoCommand.setStoreUrl(dataStore.getUri());
        isoCommand.setDeviceKey(deviceKey);
        Answer attachIsoAnswer = null;
        try {
            attachIsoAnswer = _agentManager.send(hostId, isoCommand);
        } catch (OperationTimedoutException e) {
            throw new ResourceUnavailableException("Attach ISO failed: " + e.getMessage() ,ConfigDriveNetworkElement.class,0L);
        }
        if (!attachIsoAnswer.getResult()) {
            throw new ResourceUnavailableException("Attach ISO failed: " + attachIsoAnswer.getDetails(),ConfigDriveNetworkElement.class,0L);
        }
    }

    public static final ConfigKey<Boolean> UsePrimaryStorage = new ConfigKey<Boolean>(Boolean.class, "configdrive.use.primary", "Advanced", "false",
            "Whether to use primary storage instead of secondary to create config drive ISOs", false, ConfigKey.Scope.Global, null);

    @Override public String getConfigComponentName() {
        return ConfigDriveNetworkElement.class.getSimpleName();
    }

    @Override public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {UsePrimaryStorage};
    }
}
