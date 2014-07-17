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

package com.cloud.network.router;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.alert.AlertService.AlertType;
import org.apache.cloudstack.api.command.admin.router.RebootRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.log4j.Logger;
import org.cloud.network.router.deployment.RouterDeploymentDefinitionBuilder;

import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.BumpUpPriorityCommand;
import com.cloud.agent.api.CheckRouterAnswer;
import com.cloud.agent.api.CheckRouterCommand;
import com.cloud.agent.api.CheckS2SVpnConnectionsAnswer;
import com.cloud.agent.api.CheckS2SVpnConnectionsCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetDomRVersionAnswer;
import com.cloud.agent.api.GetDomRVersionCmd;
import com.cloud.agent.api.GetRouterAlertsAnswer;
import com.cloud.agent.api.ModifySshKeysCommand;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.agent.api.NetworkUsageCommand;
import com.cloud.agent.api.PvlanSetupCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.check.CheckSshCommand;
import com.cloud.agent.api.routing.AggregationControlCommand;
import com.cloud.agent.api.routing.AggregationControlCommand.Action;
import com.cloud.agent.api.routing.CreateIpAliasCommand;
import com.cloud.agent.api.routing.DeleteIpAliasCommand;
import com.cloud.agent.api.routing.DhcpEntryCommand;
import com.cloud.agent.api.routing.DnsMasqConfigCommand;
import com.cloud.agent.api.routing.GetRouterAlertsCommand;
import com.cloud.agent.api.routing.IpAliasTO;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.LoadBalancerConfigCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.RemoteAccessVpnCfgCommand;
import com.cloud.agent.api.routing.SavePasswordCommand;
import com.cloud.agent.api.routing.SetFirewallRulesCommand;
import com.cloud.agent.api.routing.SetMonitorServiceCommand;
import com.cloud.agent.api.routing.SetPortForwardingRulesCommand;
import com.cloud.agent.api.routing.SetPortForwardingRulesVpcCommand;
import com.cloud.agent.api.routing.SetStaticNatRulesCommand;
import com.cloud.agent.api.routing.VmDataCommand;
import com.cloud.agent.api.routing.VpnUsersCfgCommand;
import com.cloud.agent.api.to.DhcpTO;
import com.cloud.agent.api.to.FirewallRuleTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.agent.api.to.MonitorServiceTO;
import com.cloud.agent.api.to.PortForwardingRuleTO;
import com.cloud.agent.api.to.StaticNatRuleTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiAsyncJobDispatcher;
import com.cloud.api.ApiGsonHelper;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.ZoneConfig;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.MonitoringService;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteCustomerGateway;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.SshKeysDistriMonitor;
import com.cloud.network.VirtualNetworkApplianceService;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.VpnUser;
import com.cloud.network.VpnUserVO;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.MonitoringServiceDao;
import com.cloud.network.dao.MonitoringServiceVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OpRouterMonitorServiceDao;
import com.cloud.network.dao.OpRouterMonitorServiceVO;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.Site2SiteCustomerGatewayDao;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.dao.VirtualRouterProviderDao;
import com.cloud.network.dao.VpnUserDao;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.lb.LoadBalancingRule.LbHealthCheckPolicy;
import com.cloud.network.lb.LoadBalancingRule.LbSslCert;
import com.cloud.network.lb.LoadBalancingRule.LbStickinessPolicy;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.router.VirtualRouter.RedundantState;
import com.cloud.network.router.VirtualRouter.Role;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.rules.StaticNatImpl;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ConfigurationServer;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.UserStatsLogVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.UserStatsLogDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.PasswordGenerator;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.MacAddress;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineGuru;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.UserVmDetailsDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * VirtualNetworkApplianceManagerImpl manages the different types of virtual network appliances available in the Cloud Stack.
 */
@Local(value = { VirtualNetworkApplianceManager.class, VirtualNetworkApplianceService.class })
public class VirtualNetworkApplianceManagerImpl extends ManagerBase implements VirtualNetworkApplianceManager, VirtualNetworkApplianceService,
VirtualMachineGuru, Listener, Configurable, StateListener<State, VirtualMachine.Event, VirtualMachine> {
    private static final Logger s_logger = Logger.getLogger(VirtualNetworkApplianceManagerImpl.class);

    @Inject
    EntityManager _entityMgr;
    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    FirewallRulesDao _rulesDao = null;
    @Inject
    LoadBalancerDao _loadBalancerDao = null;
    @Inject
    LoadBalancerVMMapDao _loadBalancerVMMapDao = null;
    @Inject
    IPAddressDao _ipAddressDao = null;
    @Inject
    VMTemplateDao _templateDao = null;
    @Inject
    DomainRouterDao _routerDao = null;
    @Inject
    UserDao _userDao = null;
    @Inject
    UserStatisticsDao _userStatsDao = null;
    @Inject
    HostDao _hostDao = null;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    HostPodDao _podDao = null;
    @Inject
    UserStatsLogDao _userStatsLogDao = null;
    @Inject
    AgentManager _agentMgr;
    @Inject
    AlertManager _alertMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    ConfigurationServer _configServer;
    @Inject
    ServiceOfferingDao _serviceOfferingDao = null;
    @Inject
    UserVmDao _userVmDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    GuestOSDao _guestOSDao = null;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    NetworkModel _networkModel;
    @Inject
    VirtualMachineManager _itMgr;
    @Inject
    VpnUserDao _vpnUsersDao;
    @Inject
    RulesManager _rulesMgr;
    @Inject
    NetworkDao _networkDao;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    PortForwardingRulesDao _pfRulesDao;
    @Inject
    RemoteAccessVpnDao _vpnDao;
    @Inject
    NicDao _nicDao;
    @Inject
    NicIpAliasDao _nicIpAliasDao;
    @Inject
    VolumeDao _volumeDao = null;
    @Inject
    UserVmDetailsDao _vmDetailsDao;
    @Inject
    ClusterDao _clusterDao;
    @Inject
    ResourceManager _resourceMgr;
    @Inject
    PhysicalNetworkServiceProviderDao _physicalProviderDao;
    @Inject
    VirtualRouterProviderDao _vrProviderDao;
    @Inject
    ManagementServerHostDao _msHostDao;
    @Inject
    Site2SiteCustomerGatewayDao _s2sCustomerGatewayDao;
    @Inject
    Site2SiteVpnGatewayDao _s2sVpnGatewayDao;
    @Inject
    Site2SiteVpnConnectionDao _s2sVpnConnectionDao;
    @Inject
    Site2SiteVpnManager _s2sVpnMgr;
    @Inject
    UserIpv6AddressDao _ipv6Dao;
    @Inject
    NetworkService _networkSvc;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    ConfigDepot _configDepot;
    @Inject
    MonitoringServiceDao _monitorServiceDao;
    @Inject
    AsyncJobManager _asyncMgr;
    @Inject
    protected ApiAsyncJobDispatcher _asyncDispatcher;
    @Inject
    OpRouterMonitorServiceDao _opRouterMonitorServiceDao;

    @Inject
    protected NetworkGeneralHelper nwHelper;
    @Inject
    protected RouterDeploymentDefinitionBuilder  routerDeploymentManagerBuilder;

    int _routerRamSize;
    int _routerCpuMHz;
    int _retry = 2;
    String _instance;
    String _mgmtCidr;

    int _routerStatsInterval = 300;
    int _routerCheckInterval = 30;
    int _rvrStatusUpdatePoolSize = 10;
    private String _dnsBasicZoneUpdates = "all";
    private final Set<String> _guestOSNeedGatewayOnNonDefaultNetwork = new HashSet<String>();

    private boolean _disableRpFilter = false;
    int _routerExtraPublicNics = 2;
    private int _usageAggregationRange = 1440;
    private String _usageTimeZone = "GMT";
    private final long mgmtSrvrId = MacAddress.getMacAddress().toLong();
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5;    // 5 seconds
    private static final int USAGE_AGGREGATION_RANGE_MIN = 10; // 10 minutes, same as com.cloud.usage.UsageManagerImpl.USAGE_AGGREGATION_RANGE_MIN
    private boolean _dailyOrHourly = false;

    ScheduledExecutorService _executor;
    ScheduledExecutorService _checkExecutor;
    ScheduledExecutorService _networkStatsUpdateExecutor;
    ExecutorService _rvrStatusUpdateExecutor;

    BlockingQueue<Long> _vrUpdateQueue = null;

    @Override
    public boolean sendSshKeysToHost(final Long hostId, final String pubKey, final String prvKey) {
        final ModifySshKeysCommand cmd = new ModifySshKeysCommand(pubKey, prvKey);
        final Answer answer = _agentMgr.easySend(hostId, cmd);

        if (answer != null) {
            return true;
        } else {
            return false;
        }
    }


    @Override
    public VirtualRouter destroyRouter(final long routerId, final Account caller,
            final Long callerUserId) throws ResourceUnavailableException,
            ConcurrentOperationException {
        return nwHelper.destroyRouter(routerId, caller, callerUserId);
    }

    @Override
    @DB
    public VirtualRouter upgradeRouter(final UpgradeRouterCmd cmd) {
        final Long routerId = cmd.getId();
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        final Account caller = CallContext.current().getCallingAccount();

        final DomainRouterVO router = _routerDao.findById(routerId);
        if (router == null) {
            throw new InvalidParameterValueException("Unable to find router with id " + routerId);
        }

        _accountMgr.checkAccess(caller, null, true, router);

        if (router.getServiceOfferingId() == serviceOfferingId) {
            s_logger.debug("Router: " + routerId + "already has service offering: " + serviceOfferingId);
            return _routerDao.findById(routerId);
        }

        final ServiceOffering newServiceOffering = _entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (newServiceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + serviceOfferingId);
        }

        // check if it is a system service offering, if yes return with error as it cannot be used for user vms
        if (!newServiceOffering.getSystemUse()) {
            throw new InvalidParameterValueException("Cannot upgrade router vm to a non system service offering " + serviceOfferingId);
        }

        // Check that the router is stopped
        if (!router.getState().equals(State.Stopped)) {
            s_logger.warn("Unable to upgrade router " + router.toString() + " in state " + router.getState());
            throw new InvalidParameterValueException("Unable to upgrade router " + router.toString() + " in state " + router.getState() +
                    "; make sure the router is stopped and not in an error state before upgrading.");
        }

        final ServiceOfferingVO currentServiceOffering = _serviceOfferingDao.findById(router.getServiceOfferingId());

        // Check that the service offering being upgraded to has the same storage pool preference as the VM's current service
        // offering
        if (currentServiceOffering.getUseLocalStorage() != newServiceOffering.getUseLocalStorage()) {
            throw new InvalidParameterValueException("Can't upgrade, due to new local storage status : " + newServiceOffering.getUseLocalStorage() +
                    " is different from " + "curruent local storage status: " + currentServiceOffering.getUseLocalStorage());
        }

        router.setServiceOfferingId(serviceOfferingId);
        if (_routerDao.update(routerId, router)) {
            return _routerDao.findById(routerId);
        } else {
            throw new CloudRuntimeException("Unable to upgrade router " + routerId);
        }

    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ROUTER_STOP, eventDescription = "stopping router Vm", async = true)
    public VirtualRouter stopRouter(final long routerId, final boolean forced) throws ResourceUnavailableException, ConcurrentOperationException {
        final CallContext context = CallContext.current();
        final Account account = context.getCallingAccount();

        // verify parameters
        DomainRouterVO router = _routerDao.findById(routerId);
        if (router == null) {
            throw new InvalidParameterValueException("Unable to find router by id " + routerId + ".");
        }

        _accountMgr.checkAccess(account, null, true, router);

        final UserVO user = _userDao.findById(CallContext.current().getCallingUserId());

        final VirtualRouter virtualRouter = stop(router, forced, user, account);
        if (virtualRouter == null) {
            throw new CloudRuntimeException("Failed to stop router with id " + routerId);
        }

        // Clear stop pending flag after stopped successfully
        if (router.isStopPending()) {
            s_logger.info("Clear the stop pending flag of router " + router.getHostName() + " after stop router successfully");
            router.setStopPending(false);
            _routerDao.persist(router);
            virtualRouter.setStopPending(false);
        }
        return virtualRouter;
    }

    @DB
    public void processStopOrRebootAnswer(final DomainRouterVO router, final Answer answer) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                //FIXME!!! - UserStats command should grab bytesSent/Received for all guest interfaces of the VR
                final List<Long> routerGuestNtwkIds = _routerDao.getRouterNetworks(router.getId());
                for (final Long guestNtwkId : routerGuestNtwkIds) {
                    final UserStatisticsVO userStats =
                            _userStatsDao.lock(router.getAccountId(), router.getDataCenterId(), guestNtwkId, null, router.getId(), router.getType().toString());
                    if (userStats != null) {
                        final long currentBytesRcvd = userStats.getCurrentBytesReceived();
                        userStats.setCurrentBytesReceived(0);
                        userStats.setNetBytesReceived(userStats.getNetBytesReceived() + currentBytesRcvd);

                        final long currentBytesSent = userStats.getCurrentBytesSent();
                        userStats.setCurrentBytesSent(0);
                        userStats.setNetBytesSent(userStats.getNetBytesSent() + currentBytesSent);
                        _userStatsDao.update(userStats.getId(), userStats);
                        s_logger.debug("Successfully updated user statistics as a part of domR " + router + " reboot/stop");
                    } else {
                        s_logger.warn("User stats were not created for account " + router.getAccountId() + " and dc " + router.getDataCenterId());
                    }
                }
            }
        });
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ROUTER_REBOOT, eventDescription = "rebooting router Vm", async = true)
    public VirtualRouter rebootRouter(final long routerId, final boolean reprogramNetwork) throws ConcurrentOperationException, ResourceUnavailableException,
    InsufficientCapacityException {
        final Account caller = CallContext.current().getCallingAccount();

        // verify parameters
        final DomainRouterVO router = _routerDao.findById(routerId);
        if (router == null) {
            throw new InvalidParameterValueException("Unable to find domain router with id " + routerId + ".");
        }

        _accountMgr.checkAccess(caller, null, true, router);

        // Can reboot domain router only in Running state
        if (router == null || router.getState() != State.Running) {
            s_logger.warn("Unable to reboot, virtual router is not in the right state " + router.getState());
            throw new ResourceUnavailableException("Unable to reboot domR, it is not in right state " + router.getState(), DataCenter.class, router.getDataCenterId());
        }

        final UserVO user = _userDao.findById(CallContext.current().getCallingUserId());
        s_logger.debug("Stopping and starting router " + router + " as a part of router reboot");

        if (stop(router, false, user, caller) != null) {
            return startRouter(routerId, reprogramNetwork);
        } else {
            throw new CloudRuntimeException("Failed to reboot router " + router);
        }
    }

    static final ConfigKey<Boolean> UseExternalDnsServers = new ConfigKey<Boolean>(Boolean.class, "use.external.dns", "Advanced", "false",
            "Bypass internal dns, use external dns1 and dns2", true, ConfigKey.Scope.Zone, null);

    static final ConfigKey<Boolean> routerVersionCheckEnabled = new ConfigKey<Boolean>("Advanced", Boolean.class, "router.version.check", "true",
            "If true, router minimum required version is checked before sending command", false);


    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("RouterMonitor"));
        _checkExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("RouterStatusMonitor"));
        _networkStatsUpdateExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("NetworkStatsUpdater"));

        VirtualMachine.State.getStateMachine().registerListener(this);

        final Map<String, String> configs = _configDao.getConfiguration("AgentManager", params);

        _routerRamSize = NumbersUtil.parseInt(configs.get("router.ram.size"), DEFAULT_ROUTER_VM_RAMSIZE);
        _routerCpuMHz = NumbersUtil.parseInt(configs.get("router.cpu.mhz"), DEFAULT_ROUTER_CPU_MHZ);

        _routerExtraPublicNics = NumbersUtil.parseInt(_configDao.getValue(Config.RouterExtraPublicNics.key()), 2);

        final String guestOSString = configs.get("network.dhcp.nondefaultnetwork.setgateway.guestos");
        if (guestOSString != null) {
            final String[] guestOSList = guestOSString.split(",");
            for (final String os : guestOSList) {
                _guestOSNeedGatewayOnNonDefaultNetwork.add(os);
            }
        }

        String value = configs.get("start.retry");
        _retry = NumbersUtil.parseInt(value, 2);

        value = configs.get("router.stats.interval");
        _routerStatsInterval = NumbersUtil.parseInt(value, 300);

        value = configs.get("router.check.interval");
        _routerCheckInterval = NumbersUtil.parseInt(value, 30);

        value = configs.get("router.check.poolsize");
        _rvrStatusUpdatePoolSize = NumbersUtil.parseInt(value, 10);

        /*
         * We assume that one thread can handle 20 requests in 1 minute in normal situation, so here we give the queue size up to 50 minutes.
         * It's mostly for buffer, since each time CheckRouterTask running, it would add all the redundant networks in the queue immediately
         */
        _vrUpdateQueue = new LinkedBlockingQueue<Long>(_rvrStatusUpdatePoolSize * 1000);

        _rvrStatusUpdateExecutor = Executors.newFixedThreadPool(_rvrStatusUpdatePoolSize, new NamedThreadFactory("RedundantRouterStatusMonitor"));

        _instance = configs.get("instance.name");
        if (_instance == null) {
            _instance = "DEFAULT";
        }

        final String rpValue = configs.get("network.disable.rpfilter");
        if (rpValue != null && rpValue.equalsIgnoreCase("true")) {
            _disableRpFilter = true;
        }

        _dnsBasicZoneUpdates = String.valueOf(_configDao.getValue(Config.DnsBasicZoneUpdates.key()));

        s_logger.info("Router configurations: " + "ramsize=" + _routerRamSize);

        _agentMgr.registerForHostEvents(new SshKeysDistriMonitor(_agentMgr, _hostDao, _configDao), true, false, false);

        final boolean useLocalStorage = Boolean.parseBoolean(configs.get(Config.SystemVMUseLocalStorage.key()));

        ServiceOfferingVO offering = new ServiceOfferingVO("System Offering For Software Router", 1, _routerRamSize, _routerCpuMHz, null,
                null, true, null, ProvisioningType.THIN, useLocalStorage, true, null, true, VirtualMachine.Type.DomainRouter, true);
        offering.setUniqueName(ServiceOffering.routerDefaultOffUniqueName);
        offering = _serviceOfferingDao.persistSystemServiceOffering(offering);
        routerDeploymentManagerBuilder.setOffering(offering);

        // this can sometimes happen, if DB is manually or programmatically manipulated
        if (offering == null) {
            final String msg = "Data integrity problem : System Offering For Software router VM has been removed?";
            s_logger.error(msg);
            throw new ConfigurationException(msg);
        }

        VirtualNwStatus.account = _accountMgr.getSystemAccount();

        final String aggregationRange = configs.get("usage.stats.job.aggregation.range");
        _usageAggregationRange = NumbersUtil.parseInt(aggregationRange, 1440);
        _usageTimeZone = configs.get("usage.aggregation.timezone");
        if (_usageTimeZone == null) {
            _usageTimeZone = "GMT";
        }

        _agentMgr.registerForHostEvents(this, true, false, false);

        s_logger.info("DomainRouterManager is configured.");

        return true;
    }

    @Override
    public boolean start() {
        if (_routerStatsInterval > 0) {
            _executor.scheduleAtFixedRate(new NetworkUsageTask(), _routerStatsInterval, _routerStatsInterval, TimeUnit.SECONDS);
        } else {
            s_logger.debug("router.stats.interval - " + _routerStatsInterval + " so not scheduling the router stats thread");
        }

        //Schedule Network stats update task
        final TimeZone usageTimezone = TimeZone.getTimeZone(_usageTimeZone);
        final Calendar cal = Calendar.getInstance(usageTimezone);
        cal.setTime(new Date());
        long endDate = 0;
        final int HOURLY_TIME = 60;
        final int DAILY_TIME = 60 * 24;
        if (_usageAggregationRange == DAILY_TIME) {
            cal.roll(Calendar.DAY_OF_YEAR, false);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.roll(Calendar.DAY_OF_YEAR, true);
            cal.add(Calendar.MILLISECOND, -1);
            endDate = cal.getTime().getTime();
            _dailyOrHourly = true;
        } else if (_usageAggregationRange == HOURLY_TIME) {
            cal.roll(Calendar.HOUR_OF_DAY, false);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.roll(Calendar.HOUR_OF_DAY, true);
            cal.add(Calendar.MILLISECOND, -1);
            endDate = cal.getTime().getTime();
            _dailyOrHourly = true;
        } else {
            endDate = cal.getTime().getTime();
            _dailyOrHourly = false;
        }

        if (_usageAggregationRange < USAGE_AGGREGATION_RANGE_MIN) {
            s_logger.warn("Usage stats job aggregation range is to small, using the minimum value of " + USAGE_AGGREGATION_RANGE_MIN);
            _usageAggregationRange = USAGE_AGGREGATION_RANGE_MIN;
        }

        _networkStatsUpdateExecutor.scheduleAtFixedRate(new NetworkStatsUpdateTask(), (endDate - System.currentTimeMillis()), (_usageAggregationRange * 60 * 1000),
                TimeUnit.MILLISECONDS);

        if (_routerCheckInterval > 0) {
            _checkExecutor.scheduleAtFixedRate(new CheckRouterTask(), _routerCheckInterval, _routerCheckInterval, TimeUnit.SECONDS);
            for (int i = 0; i < _rvrStatusUpdatePoolSize; i++) {
                _rvrStatusUpdateExecutor.execute(new RvRStatusUpdateTask());
            }
        } else {
            s_logger.debug("router.check.interval - " + _routerCheckInterval + " so not scheduling the redundant router checking thread");
        }

        int _routerAlertsCheckInterval = RouterAlertsCheckInterval.value();
        if (_routerAlertsCheckInterval > 0) {
            _checkExecutor.scheduleAtFixedRate(new CheckRouterAlertsTask(), _routerAlertsCheckInterval, _routerAlertsCheckInterval, TimeUnit.SECONDS);
        } else {
            s_logger.debug("router.alerts.check.interval - " + _routerAlertsCheckInterval + " so not scheduling the router alerts checking thread");
        }

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected VirtualNetworkApplianceManagerImpl() {
    }

    private VmDataCommand generateVmDataCommand(final VirtualRouter router, final String vmPrivateIpAddress, final String userData, final String serviceOffering, final String zoneName,
            final String guestIpAddress, final String vmName, final String vmInstanceName, final long vmId, final String vmUuid, final String publicKey, final long guestNetworkId) {
        final VmDataCommand cmd = new VmDataCommand(vmPrivateIpAddress, vmName, _networkModel.getExecuteInSeqNtwkElmtCmd());

        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());

        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmd.addVmData("userdata", "user-data", userData);
        cmd.addVmData("metadata", "service-offering", StringUtils.unicodeEscape(serviceOffering));
        cmd.addVmData("metadata", "availability-zone", StringUtils.unicodeEscape(zoneName));
        cmd.addVmData("metadata", "local-ipv4", guestIpAddress);
        cmd.addVmData("metadata", "local-hostname", StringUtils.unicodeEscape(vmName));
        if (dcVo.getNetworkType() == NetworkType.Basic) {
            cmd.addVmData("metadata", "public-ipv4", guestIpAddress);
            cmd.addVmData("metadata", "public-hostname", StringUtils.unicodeEscape(vmName));
        } else {
            if (router.getPublicIpAddress() == null) {
                cmd.addVmData("metadata", "public-ipv4", guestIpAddress);
            } else {
                cmd.addVmData("metadata", "public-ipv4", router.getPublicIpAddress());
            }
            cmd.addVmData("metadata", "public-hostname", router.getPublicIpAddress());
        }
        if (vmUuid == null) {
            setVmInstanceId(vmInstanceName, vmId, cmd);
        } else {
            setVmInstanceId(vmUuid, cmd);
        }
        cmd.addVmData("metadata", "public-keys", publicKey);

        String cloudIdentifier = _configDao.getValue("cloud.identifier");
        if (cloudIdentifier == null) {
            cloudIdentifier = "";
        } else {
            cloudIdentifier = "CloudStack-{" + cloudIdentifier + "}";
        }
        cmd.addVmData("metadata", "cloud-identifier", cloudIdentifier);

        return cmd;
    }

    private void setVmInstanceId(final String vmUuid, final VmDataCommand cmd) {
        cmd.addVmData("metadata", "instance-id", vmUuid);
        cmd.addVmData("metadata", "vm-id", vmUuid);
    }

    private void setVmInstanceId(final String vmInstanceName, final long vmId, final VmDataCommand cmd) {
        cmd.addVmData("metadata", "instance-id", vmInstanceName);
        cmd.addVmData("metadata", "vm-id", String.valueOf(vmId));
    }

    protected class NetworkUsageTask extends ManagedContextRunnable {

        public NetworkUsageTask() {
        }

        @Override
        protected void runInContext() {
            try {
                final List<DomainRouterVO> routers = _routerDao.listByStateAndNetworkType(State.Running, GuestType.Isolated, mgmtSrvrId);
                s_logger.debug("Found " + routers.size() + " running routers. ");

                for (final DomainRouterVO router : routers) {
                    final String privateIP = router.getPrivateIpAddress();

                    if (privateIP != null) {
                        final boolean forVpc = router.getVpcId() != null;
                        final List<? extends Nic> routerNics = _nicDao.listByVmId(router.getId());
                        for (final Nic routerNic : routerNics) {
                            final Network network = _networkModel.getNetwork(routerNic.getNetworkId());
                            //Send network usage command for public nic in VPC VR
                            //Send network usage command for isolated guest nic of non VPC VR
                            if ((forVpc && network.getTrafficType() == TrafficType.Public) ||
                                    (!forVpc && network.getTrafficType() == TrafficType.Guest && network.getGuestType() == Network.GuestType.Isolated)) {
                                final NetworkUsageCommand usageCmd = new NetworkUsageCommand(privateIP, router.getHostName(), forVpc, routerNic.getIp4Address());
                                final String routerType = router.getType().toString();
                                final UserStatisticsVO previousStats =
                                        _userStatsDao.findBy(router.getAccountId(), router.getDataCenterId(), network.getId(), (forVpc ? routerNic.getIp4Address() : null),
                                                router.getId(), routerType);
                                NetworkUsageAnswer answer = null;
                                try {
                                    answer = (NetworkUsageAnswer)_agentMgr.easySend(router.getHostId(), usageCmd);
                                } catch (final Exception e) {
                                    s_logger.warn("Error while collecting network stats from router: " + router.getInstanceName() + " from host: " + router.getHostId(),
                                            e);
                                    continue;
                                }

                                if (answer != null) {
                                    if (!answer.getResult()) {
                                        s_logger.warn("Error while collecting network stats from router: " + router.getInstanceName() + " from host: " +
                                                router.getHostId() + "; details: " + answer.getDetails());
                                        continue;
                                    }
                                    try {
                                        if ((answer.getBytesReceived() == 0) && (answer.getBytesSent() == 0)) {
                                            s_logger.debug("Recieved and Sent bytes are both 0. Not updating user_statistics");
                                            continue;
                                        }
                                        final NetworkUsageAnswer answerFinal = answer;
                                        Transaction.execute(new TransactionCallbackNoReturn() {
                                            @Override
                                            public void doInTransactionWithoutResult(final TransactionStatus status) {
                                                final UserStatisticsVO stats =
                                                        _userStatsDao.lock(router.getAccountId(), router.getDataCenterId(), network.getId(),
                                                                (forVpc ? routerNic.getIp4Address() : null), router.getId(), routerType);
                                                if (stats == null) {
                                                    s_logger.warn("unable to find stats for account: " + router.getAccountId());
                                                    return;
                                                }

                                                if (previousStats != null &&
                                                        ((previousStats.getCurrentBytesReceived() != stats.getCurrentBytesReceived()) || (previousStats.getCurrentBytesSent() != stats.getCurrentBytesSent()))) {
                                                    s_logger.debug("Router stats changed from the time NetworkUsageCommand was sent. " +
                                                            "Ignoring current answer. Router: " + answerFinal.getRouterName() + " Rcvd: " + answerFinal.getBytesReceived() +
                                                            "Sent: " + answerFinal.getBytesSent());
                                                    return;
                                                }

                                                if (stats.getCurrentBytesReceived() > answerFinal.getBytesReceived()) {
                                                    if (s_logger.isDebugEnabled()) {
                                                        s_logger.debug("Received # of bytes that's less than the last one.  " +
                                                                "Assuming something went wrong and persisting it. Router: " + answerFinal.getRouterName() + " Reported: " +
                                                                answerFinal.getBytesReceived() + " Stored: " + stats.getCurrentBytesReceived());
                                                    }
                                                    stats.setNetBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                                }
                                                stats.setCurrentBytesReceived(answerFinal.getBytesReceived());
                                                if (stats.getCurrentBytesSent() > answerFinal.getBytesSent()) {
                                                    if (s_logger.isDebugEnabled()) {
                                                        s_logger.debug("Received # of bytes that's less than the last one.  " +
                                                                "Assuming something went wrong and persisting it. Router: " + answerFinal.getRouterName() + " Reported: " +
                                                                answerFinal.getBytesSent() + " Stored: " + stats.getCurrentBytesSent());
                                                    }
                                                    stats.setNetBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                                }
                                                stats.setCurrentBytesSent(answerFinal.getBytesSent());
                                                if (!_dailyOrHourly) {
                                                    //update agg bytes
                                                    stats.setAggBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                                    stats.setAggBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                                }
                                                _userStatsDao.update(stats.getId(), stats);
                                            }
                                        });

                                    } catch (final Exception e) {
                                        s_logger.warn("Unable to update user statistics for account: " + router.getAccountId() + " Rx: " + answer.getBytesReceived() +
                                                "; Tx: " + answer.getBytesSent());
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (final Exception e) {
                s_logger.warn("Error while collecting network stats", e);
            }
        }
    }

    protected class NetworkStatsUpdateTask extends ManagedContextRunnable {

        public NetworkStatsUpdateTask() {
        }

        @Override
        protected void runInContext() {
            final GlobalLock scanLock = GlobalLock.getInternLock("network.stats");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    //Check for ownership
                    //msHost in UP state with min id should run the job
                    final ManagementServerHostVO msHost = _msHostDao.findOneInUpState(new Filter(ManagementServerHostVO.class, "id", true, 0L, 1L));
                    if (msHost == null || (msHost.getMsid() != mgmtSrvrId)) {
                        s_logger.debug("Skipping aggregate network stats update");
                        scanLock.unlock();
                        return;
                    }
                    try {
                        Transaction.execute(new TransactionCallbackNoReturn() {
                            @Override
                            public void doInTransactionWithoutResult(final TransactionStatus status) {
                                //get all stats with delta > 0
                                final List<UserStatisticsVO> updatedStats = _userStatsDao.listUpdatedStats();
                                final Date updatedTime = new Date();
                                for (final UserStatisticsVO stat : updatedStats) {
                                    //update agg bytes
                                    stat.setAggBytesReceived(stat.getCurrentBytesReceived() + stat.getNetBytesReceived());
                                    stat.setAggBytesSent(stat.getCurrentBytesSent() + stat.getNetBytesSent());
                                    _userStatsDao.update(stat.getId(), stat);
                                    //insert into op_user_stats_log
                                    final UserStatsLogVO statsLog =
                                            new UserStatsLogVO(stat.getId(), stat.getNetBytesReceived(), stat.getNetBytesSent(), stat.getCurrentBytesReceived(),
                                                    stat.getCurrentBytesSent(), stat.getAggBytesReceived(), stat.getAggBytesSent(), updatedTime);
                                    _userStatsLogDao.persist(statsLog);
                                }
                                s_logger.debug("Successfully updated aggregate network stats");
                            }
                        });
                    } catch (final Exception e) {
                        s_logger.debug("Failed to update aggregate network stats", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } catch (final Exception e) {
                s_logger.debug("Exception while trying to acquire network stats lock", e);
            } finally {
                scanLock.releaseRef();
            }
        }
    }

    @DB
    protected void updateSite2SiteVpnConnectionState(final List<DomainRouterVO> routers) {
        for (final DomainRouterVO router : routers) {
            final List<Site2SiteVpnConnectionVO> conns = _s2sVpnMgr.getConnectionsForRouter(router);
            if (conns == null || conns.isEmpty()) {
                continue;
            }
            if (router.getState() != State.Running) {
                for (final Site2SiteVpnConnectionVO conn : conns) {
                    if (conn.getState() != Site2SiteVpnConnection.State.Error) {
                        conn.setState(Site2SiteVpnConnection.State.Disconnected);
                        _s2sVpnConnectionDao.persist(conn);
                    }
                }
                continue;
            }
            final List<String> ipList = new ArrayList<String>();
            for (final Site2SiteVpnConnectionVO conn : conns) {
                if (conn.getState() != Site2SiteVpnConnection.State.Connected && conn.getState() != Site2SiteVpnConnection.State.Disconnected) {
                    continue;
                }
                final Site2SiteCustomerGateway gw = _s2sCustomerGatewayDao.findById(conn.getCustomerGatewayId());
                ipList.add(gw.getGatewayIp());
            }
            final String privateIP = router.getPrivateIpAddress();
            final HostVO host = _hostDao.findById(router.getHostId());
            if (host == null || host.getState() != Status.Up) {
                continue;
            } else if (host.getManagementServerId() != ManagementServerNode.getManagementServerId()) {
                /* Only cover hosts managed by this management server */
                continue;
            } else if (privateIP != null) {
                final CheckS2SVpnConnectionsCommand command = new CheckS2SVpnConnectionsCommand(ipList);
                command.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
                command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
                command.setWait(30);
                final Answer origAnswer = _agentMgr.easySend(router.getHostId(), command);
                CheckS2SVpnConnectionsAnswer answer = null;
                if (origAnswer instanceof CheckS2SVpnConnectionsAnswer) {
                    answer = (CheckS2SVpnConnectionsAnswer)origAnswer;
                } else {
                    s_logger.warn("Unable to update router " + router.getHostName() + "'s VPN connection status");
                    continue;
                }
                if (!answer.getResult()) {
                    s_logger.warn("Unable to update router " + router.getHostName() + "'s VPN connection status");
                    continue;
                }
                for (final Site2SiteVpnConnectionVO conn : conns) {
                    final Site2SiteVpnConnectionVO lock = _s2sVpnConnectionDao.acquireInLockTable(conn.getId());
                    if (lock == null) {
                        throw new CloudRuntimeException("Unable to acquire lock on " + lock);
                    }
                    try {
                        if (conn.getState() != Site2SiteVpnConnection.State.Connected && conn.getState() != Site2SiteVpnConnection.State.Disconnected) {
                            continue;
                        }
                        final Site2SiteVpnConnection.State oldState = conn.getState();
                        final Site2SiteCustomerGateway gw = _s2sCustomerGatewayDao.findById(conn.getCustomerGatewayId());
                        if (answer.isConnected(gw.getGatewayIp())) {
                            conn.setState(Site2SiteVpnConnection.State.Connected);
                        } else {
                            conn.setState(Site2SiteVpnConnection.State.Disconnected);
                        }
                        _s2sVpnConnectionDao.persist(conn);
                        if (oldState != conn.getState()) {
                            final String title = "Site-to-site Vpn Connection to " + gw.getName() + " just switch from " + oldState + " to " + conn.getState();
                            final String context =
                                    "Site-to-site Vpn Connection to " + gw.getName() + " on router " + router.getHostName() + "(id: " + router.getId() + ") " +
                                            " just switch from " + oldState + " to " + conn.getState();
                            s_logger.info(context);
                            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), title, context);
                        }
                    } finally {
                        _s2sVpnConnectionDao.releaseFromLockTable(lock.getId());
                    }
                }
            }
        }
    }

    protected void updateRoutersRedundantState(final List<DomainRouterVO> routers) {
        boolean updated = false;
        for (final DomainRouterVO router : routers) {
            updated = false;
            if (!router.getIsRedundantRouter()) {
                continue;
            }
            final RedundantState prevState = router.getRedundantState();
            if (router.getState() != State.Running) {
                router.setRedundantState(RedundantState.UNKNOWN);
                router.setIsPriorityBumpUp(false);
                updated = true;
            } else {
                final String privateIP = router.getPrivateIpAddress();
                final HostVO host = _hostDao.findById(router.getHostId());
                if (host == null || host.getState() != Status.Up) {
                    router.setRedundantState(RedundantState.UNKNOWN);
                    updated = true;
                } else if (privateIP != null) {
                    final CheckRouterCommand command = new CheckRouterCommand();
                    command.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
                    command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
                    command.setWait(30);
                    final Answer origAnswer = _agentMgr.easySend(router.getHostId(), command);
                    CheckRouterAnswer answer = null;
                    if (origAnswer instanceof CheckRouterAnswer) {
                        answer = (CheckRouterAnswer)origAnswer;
                    } else {
                        s_logger.warn("Unable to update router " + router.getHostName() + "'s status");
                    }
                    RedundantState state = RedundantState.UNKNOWN;
                    boolean isBumped = router.getIsPriorityBumpUp();
                    if (answer != null && answer.getResult()) {
                        state = answer.getState();
                        isBumped = answer.isBumped();
                    }
                    router.setRedundantState(state);
                    router.setIsPriorityBumpUp(isBumped);
                    updated = true;
                }
            }
            if (updated) {
                _routerDao.update(router.getId(), router);
            }
            final RedundantState currState = router.getRedundantState();
            if (prevState != currState) {
                final String title = "Redundant virtual router " + router.getInstanceName() + " just switch from " + prevState + " to " + currState;
                final String context =
                        "Redundant virtual router (name: " + router.getHostName() + ", id: " + router.getId() + ") " + " just switch from " + prevState + " to " + currState;
                s_logger.info(context);
                if (currState == RedundantState.MASTER) {
                    _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), title, context);
                }
            }
        }
    }

    //Ensure router status is update to date before execute this function. The function would try best to recover all routers except MASTER
    protected void recoverRedundantNetwork(final DomainRouterVO masterRouter, final DomainRouterVO backupRouter) {
        if (masterRouter.getState() == State.Running && backupRouter.getState() == State.Running) {
            final HostVO masterHost = _hostDao.findById(masterRouter.getHostId());
            final HostVO backupHost = _hostDao.findById(backupRouter.getHostId());
            if (masterHost.getState() == Status.Up && backupHost.getState() == Status.Up) {
                final String title = "Reboot " + backupRouter.getInstanceName() + " to ensure redundant virtual routers work";
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug(title);
                }
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, backupRouter.getDataCenterId(), backupRouter.getPodIdToDeployIn(), title, title);
                try {
                    rebootRouter(backupRouter.getId(), true);
                } catch (final ConcurrentOperationException e) {
                    s_logger.warn("Fail to reboot " + backupRouter.getInstanceName(), e);
                } catch (final ResourceUnavailableException e) {
                    s_logger.warn("Fail to reboot " + backupRouter.getInstanceName(), e);
                } catch (final InsufficientCapacityException e) {
                    s_logger.warn("Fail to reboot " + backupRouter.getInstanceName(), e);
                }
            }
        }
    }

    private int getRealPriority(final DomainRouterVO router) {
        int priority = router.getPriority();
        if (router.getIsPriorityBumpUp()) {
            priority += DEFAULT_DELTA;
        }
        return priority;
    }

    protected class RvRStatusUpdateTask extends ManagedContextRunnable {

        public RvRStatusUpdateTask() {
        }

        /*
         * In order to make fail-over works well at any time, we have to ensure:
         * 1. Backup router's priority = Master's priority - DELTA + 1
         * 2. Backup router's priority hasn't been bumped up.
         */
        private void checkSanity(final List<DomainRouterVO> routers) {
            final Set<Long> checkedNetwork = new HashSet<Long>();
            for (final DomainRouterVO router : routers) {
                if (!router.getIsRedundantRouter()) {
                    continue;
                }

                final List<Long> routerGuestNtwkIds = _routerDao.getRouterNetworks(router.getId());

                for (final Long routerGuestNtwkId : routerGuestNtwkIds) {
                    if (checkedNetwork.contains(routerGuestNtwkId)) {
                        continue;
                    }
                    checkedNetwork.add(routerGuestNtwkId);
                    final List<DomainRouterVO> checkingRouters = _routerDao.listByNetworkAndRole(routerGuestNtwkId, Role.VIRTUAL_ROUTER);
                    if (checkingRouters.size() != 2) {
                        continue;
                    }
                    DomainRouterVO masterRouter = null;
                    DomainRouterVO backupRouter = null;
                    for (final DomainRouterVO r : checkingRouters) {
                        if (r.getRedundantState() == RedundantState.MASTER) {
                            if (masterRouter == null) {
                                masterRouter = r;
                            } else {
                                //Duplicate master! We give up, until the admin fix duplicate MASTER issue
                                break;
                            }
                        } else if (r.getRedundantState() == RedundantState.BACKUP) {
                            if (backupRouter == null) {
                                backupRouter = r;
                            } else {
                                break;
                            }
                        }
                    }
                    if (masterRouter != null && backupRouter != null) {
                        if (getRealPriority(masterRouter) - DEFAULT_DELTA + 1 != getRealPriority(backupRouter) || backupRouter.getIsPriorityBumpUp()) {
                            recoverRedundantNetwork(masterRouter, backupRouter);
                        }
                    }
                }
            }
        }

        private void checkDuplicateMaster(final List<DomainRouterVO> routers) {
            final Map<Long, DomainRouterVO> networkRouterMaps = new HashMap<Long, DomainRouterVO>();
            for (final DomainRouterVO router : routers) {
                final List<Long> routerGuestNtwkIds = _routerDao.getRouterNetworks(router.getId());

                for (final Long routerGuestNtwkId : routerGuestNtwkIds) {
                    if (router.getRedundantState() == RedundantState.MASTER) {
                        if (networkRouterMaps.containsKey(routerGuestNtwkId)) {
                            final DomainRouterVO dupRouter = networkRouterMaps.get(routerGuestNtwkId);
                            final String title =
                                    "More than one redundant virtual router is in MASTER state! Router " + router.getHostName() + " and router " + dupRouter.getHostName();
                            final String context =
                                    "Virtual router (name: " + router.getHostName() + ", id: " + router.getId() + " and router (name: " + dupRouter.getHostName() + ", id: " +
                                            router.getId() + ") are both in MASTER state! If the problem persist, restart both of routers. ";
                            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), title, context);
                            _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, dupRouter.getDataCenterId(), dupRouter.getPodIdToDeployIn(), title,
                                    context);
                            s_logger.warn(context);
                        } else {
                            networkRouterMaps.put(routerGuestNtwkId, router);
                        }
                    }
                }
            }
        }

        @Override
        protected void runInContext() {
            while (true) {
                try {
                    final Long networkId = _vrUpdateQueue.take();  // This is a blocking call so this thread won't run all the time if no work item in queue.
                    final List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(networkId, Role.VIRTUAL_ROUTER);

                    if (routers.size() != 2) {
                        continue;
                    }
                    /*
                     * We update the router pair which the lower id router owned by this mgmt server, in order
                     * to prevent duplicate update of router status from cluster mgmt servers
                     */
                    final DomainRouterVO router0 = routers.get(0);
                    final DomainRouterVO router1 = routers.get(1);
                    DomainRouterVO router = router0;
                    if ((router0.getId() < router1.getId()) && router0.getHostId() != null) {
                        router = router0;
                    } else {
                        router = router1;
                    }
                    if (router.getHostId() == null) {
                        s_logger.debug("Skip router pair (" + router0.getInstanceName() + "," + router1.getInstanceName() + ") due to can't find host");
                        continue;
                    }
                    final HostVO host = _hostDao.findById(router.getHostId());
                    if (host == null || host.getManagementServerId() == null || host.getManagementServerId() != ManagementServerNode.getManagementServerId()) {
                        s_logger.debug("Skip router pair (" + router0.getInstanceName() + "," + router1.getInstanceName() + ") due to not belong to this mgmt server");
                        continue;
                    }
                    updateRoutersRedundantState(routers);
                    checkDuplicateMaster(routers);
                    checkSanity(routers);
                } catch (final Exception ex) {
                    s_logger.error("Fail to complete the RvRStatusUpdateTask! ", ex);
                }
            }
        }
    }

    protected class CheckRouterTask extends ManagedContextRunnable {

        public CheckRouterTask() {
        }

        @Override
        protected void runInContext() {
            try {
                final List<DomainRouterVO> routers = _routerDao.listIsolatedByHostId(null);
                s_logger.debug("Found " + routers.size() + " routers to update status. ");

                updateSite2SiteVpnConnectionState(routers);

                final List<NetworkVO> networks = _networkDao.listRedundantNetworks();
                s_logger.debug("Found " + networks.size() + " networks to update RvR status. ");
                for (final NetworkVO network : networks) {
                    if (!_vrUpdateQueue.offer(network.getId(), 500, TimeUnit.MILLISECONDS)) {
                        s_logger.warn("Cannot insert into virtual router update queue! Adjustment of router.check.interval and router.check.poolsize maybe needed.");
                        break;
                    }
                }
            } catch (final Exception ex) {
                s_logger.error("Fail to complete the CheckRouterTask! ", ex);
            }
        }
    }

    protected class CheckRouterAlertsTask extends ManagedContextRunnable {
        public CheckRouterAlertsTask() {
        }

        @Override
        protected void runInContext() {
            try {
                getRouterAlerts();
            } catch (final Exception ex) {
                s_logger.error("Fail to complete the CheckRouterAlertsTask! ", ex);
            }
        }
    }

    protected void getRouterAlerts() {
        try{
            List<DomainRouterVO> routers = _routerDao.listByStateAndManagementServer(State.Running, mgmtSrvrId);

            s_logger.debug("Found " + routers.size() + " running routers. ");

            for (final DomainRouterVO router : routers) {
                String serviceMonitoringFlag = SetServiceMonitor.valueIn(router.getDataCenterId());
                // Skip the routers in VPC network or skip the routers where Monitor service is not enabled in the corresponding Zone
                if ( !Boolean.parseBoolean(serviceMonitoringFlag) || router.getVpcId() != null) {
                    continue;
                }

                String privateIP = router.getPrivateIpAddress();

                if (privateIP != null) {
                    OpRouterMonitorServiceVO opRouterMonitorServiceVO = _opRouterMonitorServiceDao.findById(router.getId());

                    GetRouterAlertsCommand command = null;
                    if (opRouterMonitorServiceVO == null) {
                        command = new GetRouterAlertsCommand(new String("1970-01-01 00:00:00")); // To avoid sending null value
                    } else {
                        command = new GetRouterAlertsCommand(opRouterMonitorServiceVO.getLastAlertTimestamp());
                    }

                    command.setAccessDetail(NetworkElementCommand.ROUTER_IP, router.getPrivateIpAddress());

                    try {
                        final Answer origAnswer = _agentMgr.easySend(router.getHostId(), command);
                        GetRouterAlertsAnswer answer = null;

                        if (origAnswer == null) {
                            s_logger.warn("Unable to get alerts from router " + router.getHostName());
                            continue;
                        }
                        if (origAnswer instanceof GetRouterAlertsAnswer) {
                            answer = (GetRouterAlertsAnswer)origAnswer;
                        } else {
                            s_logger.warn("Unable to get alerts from router " + router.getHostName());
                            continue;
                        }
                        if (!answer.getResult()) {
                            s_logger.warn("Unable to get alerts from router " + router.getHostName() + " " + answer.getDetails());
                            continue;
                        }

                        String alerts[] = answer.getAlerts();
                        if (alerts != null) {
                            String lastAlertTimeStamp = answer.getTimeStamp();
                            SimpleDateFormat sdfrmt = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                            sdfrmt.setLenient(false);
                            try
                            {
                                sdfrmt.parse(lastAlertTimeStamp);
                            }
                            catch (ParseException e)
                            {
                                s_logger.warn("Invalid last alert timestamp received while collecting alerts from router: " + router.getInstanceName());
                                continue;
                            }
                            for (String alert: alerts) {
                                _alertMgr.sendAlert(AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), "Monitoring Service on VR " + router.getInstanceName(), alert);
                            }
                            if (opRouterMonitorServiceVO == null) {
                                opRouterMonitorServiceVO = new OpRouterMonitorServiceVO(router.getId(), router.getHostName(), lastAlertTimeStamp);
                                _opRouterMonitorServiceDao.persist(opRouterMonitorServiceVO);
                            } else {
                                opRouterMonitorServiceVO.setLastAlertTimestamp(lastAlertTimeStamp);
                                _opRouterMonitorServiceDao.update(opRouterMonitorServiceVO.getId(), opRouterMonitorServiceVO);
                            }
                        }
                    } catch (Exception e) {
                        s_logger.warn("Error while collecting alerts from router: " + router.getInstanceName(), e);
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            s_logger.warn("Error while collecting alerts from router", e);
        }
    }

    private final static int DEFAULT_PRIORITY = 100;
    private final static int DEFAULT_DELTA = 2;

    protected int getUpdatedPriority(final Network guestNetwork, final List<DomainRouterVO> routers, final DomainRouterVO exclude)
            throws InsufficientVirtualNetworkCapacityException {
        int priority;
        if (routers.size() == 0) {
            priority = DEFAULT_PRIORITY;
        } else {
            int maxPriority = 0;
            for (final DomainRouterVO r : routers) {
                if (!r.getIsRedundantRouter()) {
                    throw new CloudRuntimeException("Redundant router is mixed with single router in one network!");
                }
                //FIXME Assume the maxPriority one should be running or just created.
                if (r.getId() != exclude.getId() && getRealPriority(r) > maxPriority) {
                    maxPriority = getRealPriority(r);
                }
            }
            if (maxPriority == 0) {
                return DEFAULT_PRIORITY;
            }
            if (maxPriority < 20) {
                s_logger.error("Current maximum priority is too low!");
                throw new InsufficientVirtualNetworkCapacityException("Current maximum priority is too low as " + maxPriority + "!", guestNetwork.getId());
            } else if (maxPriority > 200) {
                s_logger.error("Too many times fail-over happened! Current maximum priority is too high as " + maxPriority + "!");
                throw new InsufficientVirtualNetworkCapacityException("Too many times fail-over happened! Current maximum priority is too high as " + maxPriority + "!",
                        guestNetwork.getId());
            }
            priority = maxPriority - DEFAULT_DELTA + 1;
        }
        return priority;
    }

    @Override
    public boolean finalizeVirtualMachineProfile(final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context) {

        boolean dnsProvided = true;
        boolean dhcpProvided = true;
        boolean publicNetwork = false;
        final DataCenterVO dc = _dcDao.findById(dest.getDataCenter().getId());
        _dcDao.loadDetails(dc);

        //1) Set router details
        final DomainRouterVO router = _routerDao.findById(profile.getVirtualMachine().getId());
        final Map<String, String> details = _vmDetailsDao.listDetailsKeyPairs(router.getId());
        router.setDetails(details);

        //2) Prepare boot loader elements related with Control network

        final StringBuilder buf = profile.getBootArgsBuilder();
        buf.append(" template=domP");
        buf.append(" name=").append(profile.getHostName());

        if (Boolean.valueOf(_configDao.getValue("system.vm.random.password"))) {
            buf.append(" vmpassword=").append(_configDao.getValue("system.vm.password"));
        }

        NicProfile controlNic = null;
        String defaultDns1 = null;
        String defaultDns2 = null;
        String defaultIp6Dns1 = null;
        String defaultIp6Dns2 = null;
        for (final NicProfile nic : profile.getNics()) {
            final int deviceId = nic.getDeviceId();
            boolean ipv4 = false, ipv6 = false;
            if (nic.getIp4Address() != null) {
                ipv4 = true;
                buf.append(" eth").append(deviceId).append("ip=").append(nic.getIp4Address());
                buf.append(" eth").append(deviceId).append("mask=").append(nic.getNetmask());
            }
            if (nic.getIp6Address() != null) {
                ipv6 = true;
                buf.append(" eth").append(deviceId).append("ip6=").append(nic.getIp6Address());
                buf.append(" eth").append(deviceId).append("ip6prelen=").append(NetUtils.getIp6CidrSize(nic.getIp6Cidr()));
            }

            if (nic.isDefaultNic()) {
                if (ipv4) {
                    buf.append(" gateway=").append(nic.getGateway());
                }
                if (ipv6) {
                    buf.append(" ip6gateway=").append(nic.getIp6Gateway());
                }
                defaultDns1 = nic.getDns1();
                defaultDns2 = nic.getDns2();
                defaultIp6Dns1 = nic.getIp6Dns1();
                defaultIp6Dns2 = nic.getIp6Dns2();
            }

            if (nic.getTrafficType() == TrafficType.Management) {
                buf.append(" localgw=").append(dest.getPod().getGateway());
            } else if (nic.getTrafficType() == TrafficType.Control) {
                controlNic = nic;
                // DOMR control command is sent over management server in VMware
                if (dest.getHost().getHypervisorType() == HypervisorType.VMware || dest.getHost().getHypervisorType() == HypervisorType.Hyperv) {
                    s_logger.info("Check if we need to add management server explicit route to DomR. pod cidr: " + dest.getPod().getCidrAddress() + "/" +
                            dest.getPod().getCidrSize() + ", pod gateway: " + dest.getPod().getGateway() + ", management host: " + ApiServiceConfiguration.ManagementHostIPAdr.value());

                    if (s_logger.isInfoEnabled()) {
                        s_logger.info("Add management server explicit route to DomR.");
                    }

                    // always add management explicit route, for basic networking setup, DomR may have two interfaces while both
                    // are on the same subnet
                    _mgmtCidr = _configDao.getValue(Config.ManagementNetwork.key());
                    if (NetUtils.isValidCIDR(_mgmtCidr)) {
                        buf.append(" mgmtcidr=").append(_mgmtCidr);
                        buf.append(" localgw=").append(dest.getPod().getGateway());
                    }

                    if (dc.getNetworkType() == NetworkType.Basic) {
                        // ask domR to setup SSH on guest network
                        buf.append(" sshonguest=true");
                    }

                }
            } else if (nic.getTrafficType() == TrafficType.Guest) {
                dnsProvided = _networkModel.isProviderSupportServiceInNetwork(nic.getNetworkId(), Service.Dns, Provider.VirtualRouter);
                dhcpProvided = _networkModel.isProviderSupportServiceInNetwork(nic.getNetworkId(), Service.Dhcp, Provider.VirtualRouter);
                //build bootloader parameter for the guest
                buf.append(createGuestBootLoadArgs(nic, defaultDns1, defaultDns2, router));
            } else if (nic.getTrafficType() == TrafficType.Public) {
                publicNetwork = true;
            }
        }

        if (controlNic == null) {
            throw new CloudRuntimeException("Didn't start a control port");
        }

        final String rpValue = _configDao.getValue(Config.NetworkRouterRpFilter.key());
        if (rpValue != null && rpValue.equalsIgnoreCase("true")) {
            _disableRpFilter = true;
        } else {
            _disableRpFilter = false;
        }

        String rpFilter = " ";
        String type = null;
        if (router.getVpcId() != null) {
            type = "vpcrouter";
            if (_disableRpFilter) {
                rpFilter = " disable_rp_filter=true";
            }
        } else if (!publicNetwork) {
            type = "dhcpsrvr";
        } else {
            type = "router";
            if (_disableRpFilter) {
                rpFilter = " disable_rp_filter=true";
            }
        }

        if (_disableRpFilter) {
            rpFilter = " disable_rp_filter=true";
        }

        buf.append(" type=" + type + rpFilter);

        final String domain_suffix = dc.getDetail(ZoneConfig.DnsSearchOrder.getName());
        if (domain_suffix != null) {
            buf.append(" dnssearchorder=").append(domain_suffix);
        }

        if (profile.getHypervisorType() == HypervisorType.VMware || profile.getHypervisorType() == HypervisorType.Hyperv) {
            buf.append(" extra_pubnics=" + _routerExtraPublicNics);
        }

        /* If virtual router didn't provide DNS service but provide DHCP service, we need to override the DHCP response
         * to return DNS server rather than
         * virtual router itself. */
        if (dnsProvided || dhcpProvided) {
            if (defaultDns1 != null) {
                buf.append(" dns1=").append(defaultDns1);
            }
            if (defaultDns2 != null) {
                buf.append(" dns2=").append(defaultDns2);
            }
            if (defaultIp6Dns1 != null) {
                buf.append(" ip6dns1=").append(defaultIp6Dns1);
            }
            if (defaultIp6Dns2 != null) {
                buf.append(" ip6dns2=").append(defaultIp6Dns2);
            }

            boolean useExtDns = !dnsProvided;
            /* For backward compatibility */
            useExtDns = useExtDns || UseExternalDnsServers.valueIn(dc.getId());

            if (useExtDns) {
                buf.append(" useextdns=true");
            }
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Boot Args for " + profile + ": " + buf.toString());
        }

        return true;
    }

    protected StringBuilder createGuestBootLoadArgs(final NicProfile guestNic, final String defaultDns1, final String defaultDns2, DomainRouterVO router) {
        final long guestNetworkId = guestNic.getNetworkId();
        final NetworkVO guestNetwork = _networkDao.findById(guestNetworkId);
        String dhcpRange = null;
        final DataCenterVO dc = _dcDao.findById(guestNetwork.getDataCenterId());

        final StringBuilder buf = new StringBuilder();

        final boolean isRedundant = router.getIsRedundantRouter();
        if (isRedundant) {
            buf.append(" redundant_router=1");
            final List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(guestNetwork.getId(), Role.VIRTUAL_ROUTER);
            try {
                final int priority = getUpdatedPriority(guestNetwork, routers, router);
                router.setPriority(priority);
                router = _routerDao.persist(router);
            } catch (final InsufficientVirtualNetworkCapacityException e) {
                s_logger.error("Failed to get update priority!", e);
                throw new CloudRuntimeException("Failed to get update priority!");
            }
            final Network net = _networkModel.getNetwork(guestNic.getNetworkId());
            buf.append(" guestgw=").append(net.getGateway());
            final String brd = NetUtils.long2Ip(NetUtils.ip2Long(guestNic.getIp4Address()) | ~NetUtils.ip2Long(guestNic.getNetmask()));
            buf.append(" guestbrd=").append(brd);
            buf.append(" guestcidrsize=").append(NetUtils.getCidrSize(guestNic.getNetmask()));
            buf.append(" router_pr=").append(router.getPriority());

            int advertInt = NumbersUtil.parseInt(_configDao.getValue(Config.RedundantRouterVrrpInterval.key()), 1);
            buf.append(" advert_int=").append(advertInt);
        }

        //setup network domain
        final String domain = guestNetwork.getNetworkDomain();
        if (domain != null) {
            buf.append(" domain=" + domain);
        }

        long cidrSize = 0;

        //setup dhcp range
        if (dc.getNetworkType() == NetworkType.Basic) {
            if (guestNic.isDefaultNic()) {
                cidrSize = NetUtils.getCidrSize(guestNic.getNetmask());
                final String cidr = NetUtils.getCidrSubNet(guestNic.getGateway(), cidrSize);
                if (cidr != null) {
                    dhcpRange = NetUtils.getIpRangeStartIpFromCidr(cidr, cidrSize);
                }
            }
        } else if (dc.getNetworkType() == NetworkType.Advanced) {
            final String cidr = guestNetwork.getCidr();
            if (cidr != null) {
                cidrSize = NetUtils.getCidrSize(NetUtils.getCidrNetmask(cidr));
                dhcpRange = NetUtils.getDhcpRange(cidr);
            }
        }

        if (dhcpRange != null) {
            // To limit DNS to the cidr range
            buf.append(" cidrsize=" + String.valueOf(cidrSize));
            buf.append(" dhcprange=" + dhcpRange);
        }

        return buf;
    }

    protected String getGuestDhcpRange(final NicProfile guestNic, final Network guestNetwork, final DataCenter dc) {
        String dhcpRange = null;
        //setup dhcp range
        if (dc.getNetworkType() == NetworkType.Basic) {
            final long cidrSize = NetUtils.getCidrSize(guestNic.getNetmask());
            final String cidr = NetUtils.getCidrSubNet(guestNic.getGateway(), cidrSize);
            if (cidr != null) {
                dhcpRange = NetUtils.getIpRangeStartIpFromCidr(cidr, cidrSize);
            }
        } else if (dc.getNetworkType() == NetworkType.Advanced) {
            final String cidr = guestNetwork.getCidr();
            if (cidr != null) {
                dhcpRange = NetUtils.getDhcpRange(cidr);
            }
        }
        return dhcpRange;
    }

    @Override
    public boolean setupDhcpForPvlan(final boolean add, final DomainRouterVO router, final Long hostId, final NicProfile nic) {
        if (!nic.getBroadCastUri().getScheme().equals("pvlan")) {
            return false;
        }
        String op = "add";
        if (!add) {
            op = "delete";
        }
        final Network network = _networkDao.findById(nic.getNetworkId());
        final String networkTag = _networkModel.getNetworkTag(router.getHypervisorType(), network);
        final PvlanSetupCommand cmd =
                PvlanSetupCommand.createDhcpSetup(op, nic.getBroadCastUri(), networkTag, router.getInstanceName(), nic.getMacAddress(), nic.getIp4Address());
        // In fact we send command to the host of router, we're not programming router but the host
        Commands cmds = new Commands(Command.OnError.Stop);
        cmds.addCommand(cmd);

        try {
            return sendCommandsToRouter(router, cmds);
        } catch (final ResourceUnavailableException e) {
            s_logger.warn("Timed Out", e);
            return false;
        }
    }

    @Override
    public boolean finalizeDeployment(final Commands cmds, final VirtualMachineProfile profile, final DeployDestination dest, final ReservationContext context)
            throws ResourceUnavailableException {
        final DomainRouterVO router = _routerDao.findById(profile.getId());

        final List<NicProfile> nics = profile.getNics();
        for (final NicProfile nic : nics) {
            if (nic.getTrafficType() == TrafficType.Public) {
                router.setPublicIpAddress(nic.getIp4Address());
                router.setPublicNetmask(nic.getNetmask());
                router.setPublicMacAddress(nic.getMacAddress());
            } else if (nic.getTrafficType() == TrafficType.Control) {
                router.setPrivateIpAddress(nic.getIp4Address());
                router.setPrivateMacAddress(nic.getMacAddress());
            }
        }
        _routerDao.update(router.getId(), router);

        finalizeCommandsOnStart(cmds, profile);
        return true;
    }

    @Override
    public boolean finalizeCommandsOnStart(final Commands cmds, final VirtualMachineProfile profile) {
        final DomainRouterVO router = _routerDao.findById(profile.getId());
        final NicProfile controlNic = getControlNic(profile);

        if (controlNic == null) {
            s_logger.error("Control network doesn't exist for the router " + router);
            return false;
        }

        finalizeSshAndVersionAndNetworkUsageOnStart(cmds, profile, router, controlNic);

        // restart network if restartNetwork = false is not specified in profile parameters
        boolean reprogramGuestNtwks = true;
        if (profile.getParameter(Param.ReProgramGuestNetworks) != null && (Boolean)profile.getParameter(Param.ReProgramGuestNetworks) == false) {
            reprogramGuestNtwks = false;
        }

        final VirtualRouterProvider vrProvider = _vrProviderDao.findById(router.getElementId());
        if (vrProvider == null) {
            throw new CloudRuntimeException("Cannot find related virtual router provider of router: " + router.getHostName());
        }
        final Provider provider = Network.Provider.getProvider(vrProvider.getType().toString());
        if (provider == null) {
            throw new CloudRuntimeException("Cannot find related provider of virtual router provider: " + vrProvider.getType().toString());
        }

        final List<Long> routerGuestNtwkIds = _routerDao.getRouterNetworks(router.getId());
        for (final Long guestNetworkId : routerGuestNtwkIds) {
            AggregationControlCommand startCmd = new AggregationControlCommand(Action.Start, router.getInstanceName(), controlNic.getIp4Address(),
                    getRouterIpInNetwork(guestNetworkId, router.getId()));
            cmds.addCommand(startCmd);

            if (reprogramGuestNtwks) {
                finalizeIpAssocForNetwork(cmds, router, provider, guestNetworkId, null);
                finalizeNetworkRulesForNetwork(cmds, router, provider, guestNetworkId);

                NetworkOffering offering = _networkOfferingDao.findById((_networkDao.findById(guestNetworkId)).getNetworkOfferingId());
                //service monitoring is currently not added in RVR
                if (!offering.getRedundantRouter()) {
                    String serviceMonitringSet = _configDao.getValue(Config.EnableServiceMonitoring.key());

                    if (serviceMonitringSet != null && serviceMonitringSet.equalsIgnoreCase("true")) {
                        finalizeMonitorServiceOnStrat(cmds, profile, router, provider, guestNetworkId, true);
                    } else {
                        finalizeMonitorServiceOnStrat(cmds, profile, router, provider, guestNetworkId, false);
                    }
                }

            }

            finalizeUserDataAndDhcpOnStart(cmds, router, provider, guestNetworkId);

            AggregationControlCommand finishCmd = new AggregationControlCommand(Action.Finish, router.getInstanceName(), controlNic.getIp4Address(),
                    getRouterIpInNetwork(guestNetworkId, router.getId()));
            cmds.addCommand(finishCmd);
        }


        return true;
    }

    private void finalizeMonitorServiceOnStrat(final Commands cmds, final VirtualMachineProfile profile, final DomainRouterVO router, final Provider provider, final long networkId, final Boolean add) {

        final NetworkVO network = _networkDao.findById(networkId);

        s_logger.debug("Creating  monitoring services on " + router + " start...");

        // get the list of sevices for this network to monitor
        final List<MonitoringServiceVO> services = new ArrayList<MonitoringServiceVO>();
        if (_networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, Provider.VirtualRouter) ||
                _networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, Provider.VirtualRouter)) {
            final MonitoringServiceVO dhcpService = _monitorServiceDao.getServiceByName(MonitoringService.Service.Dhcp.toString());
            if (dhcpService != null) {
                services.add(dhcpService);
            }
        }

        if (_networkModel.isProviderSupportServiceInNetwork(network.getId(), Service.Lb, Provider.VirtualRouter)) {
            final MonitoringServiceVO lbService = _monitorServiceDao.getServiceByName(MonitoringService.Service.LoadBalancing.toString());
            if (lbService != null) {
                services.add(lbService);
            }
        }
        final List<MonitoringServiceVO> defaultServices = _monitorServiceDao.listDefaultServices(true);
        services.addAll(defaultServices);

        final List<MonitorServiceTO> servicesTO = new ArrayList<MonitorServiceTO>();
        for (final MonitoringServiceVO service : services) {
            final MonitorServiceTO serviceTO = new MonitorServiceTO(service.getService(), service.getProcessName(), service.getServiceName(), service.getServicePath(),
                    service.getServicePidFile(), service.isDefaultService());
            servicesTO.add(serviceTO);
        }

        // TODO : This is a hacking fix
        // at VR startup time, information in VirtualMachineProfile may not updated to DB yet,
        // getRouterControlIp() may give wrong IP under basic network mode in VMware environment
        final NicProfile controlNic = getControlNic(profile);
        if (controlNic == null) {
            throw new CloudRuntimeException("VirtualMachine " + profile.getInstanceName() + " doesn't have a control interface");
        }
        final SetMonitorServiceCommand command = new SetMonitorServiceCommand(servicesTO);
        command.setAccessDetail(NetworkElementCommand.ROUTER_IP, controlNic.getIp4Address());
        command.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(networkId, router.getId()));
        command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());

        if (!add) {
            command.setAccessDetail(NetworkElementCommand.ROUTER_MONITORING_ENABLE, add.toString());
        }
        cmds.addCommand("monitor", command);
    }

    protected NicProfile getControlNic(final VirtualMachineProfile profile) {
        final DomainRouterVO router = _routerDao.findById(profile.getId());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        NicProfile controlNic = null;
        if (profile.getHypervisorType() == HypervisorType.VMware && dcVo.getNetworkType() == NetworkType.Basic) {
            // TODO this is a ugly to test hypervisor type here
            // for basic network mode, we will use the guest NIC for control NIC
            for (final NicProfile nic : profile.getNics()) {
                if (nic.getTrafficType() == TrafficType.Guest && nic.getIp4Address() != null) {
                    controlNic = nic;
                }
            }
        } else {
            for (final NicProfile nic : profile.getNics()) {
                if (nic.getTrafficType() == TrafficType.Control && nic.getIp4Address() != null) {
                    controlNic = nic;
                }
            }
        }
        return controlNic;
    }

    protected void finalizeSshAndVersionAndNetworkUsageOnStart(final Commands cmds, final VirtualMachineProfile profile, final DomainRouterVO router, final NicProfile controlNic) {
        final DomainRouterVO vr = _routerDao.findById(profile.getId());
        cmds.addCommand("checkSsh", new CheckSshCommand(profile.getInstanceName(), controlNic.getIp4Address(), 3922));

        // Update router template/scripts version
        final GetDomRVersionCmd command = new GetDomRVersionCmd();
        command.setAccessDetail(NetworkElementCommand.ROUTER_IP, controlNic.getIp4Address());
        command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        cmds.addCommand("getDomRVersion", command);

        // Network usage command to create iptables rules
        final boolean forVpc = vr.getVpcId() != null;
        if (!forVpc) {
            cmds.addCommand("networkUsage", new NetworkUsageCommand(controlNic.getIp4Address(), router.getHostName(), "create", forVpc));
        }
    }

    protected void finalizeUserDataAndDhcpOnStart(final Commands cmds, final DomainRouterVO router, final Provider provider, final Long guestNetworkId) {
        if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Dhcp, provider)) {
            // Resend dhcp
            s_logger.debug("Reapplying dhcp entries as a part of domR " + router + " start...");
            createDhcpEntryCommandsForVMs(router, cmds, guestNetworkId);
        }

        if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.UserData, provider)) {
            // Resend user data
            s_logger.debug("Reapplying vm data (userData and metaData) entries as a part of domR " + router + " start...");
            createVmDataCommandForVMs(router, cmds, guestNetworkId);
        }
    }

    protected void finalizeNetworkRulesForNetwork(final Commands cmds, final DomainRouterVO router, final Provider provider, final Long guestNetworkId) {
        s_logger.debug("Resending ipAssoc, port forwarding, load balancing rules as a part of Virtual router start");

        final ArrayList<? extends PublicIpAddress> publicIps = getPublicIpsToApply(router, provider, guestNetworkId);
        final List<FirewallRule> firewallRulesEgress = new ArrayList<FirewallRule>();

        //  Fetch firewall Egress rules.
        if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Firewall, provider)) {
            firewallRulesEgress.addAll(_rulesDao.listByNetworkPurposeTrafficType(guestNetworkId, Purpose.Firewall, FirewallRule.TrafficType.Egress));
        }

        // Re-apply firewall Egress rules
        s_logger.debug("Found " + firewallRulesEgress.size() + " firewall Egress rule(s) to apply as a part of domR " + router + " start.");
        if (!firewallRulesEgress.isEmpty()) {
            createFirewallRulesCommands(firewallRulesEgress, router, cmds, guestNetworkId);
        }

        if (publicIps != null && !publicIps.isEmpty()) {
            final List<RemoteAccessVpn> vpns = new ArrayList<RemoteAccessVpn>();
            final List<PortForwardingRule> pfRules = new ArrayList<PortForwardingRule>();
            final List<FirewallRule> staticNatFirewallRules = new ArrayList<FirewallRule>();
            final List<StaticNat> staticNats = new ArrayList<StaticNat>();
            final List<FirewallRule> firewallRulesIngress = new ArrayList<FirewallRule>();

            //Get information about all the rules (StaticNats and StaticNatRules; PFVPN to reapply on domR start)
            for (final PublicIpAddress ip : publicIps) {
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.PortForwarding, provider)) {
                    pfRules.addAll(_pfRulesDao.listForApplication(ip.getId()));
                }
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.StaticNat, provider)) {
                    staticNatFirewallRules.addAll(_rulesDao.listByIpAndPurpose(ip.getId(), Purpose.StaticNat));
                }
                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Firewall, provider)) {
                    firewallRulesIngress.addAll(_rulesDao.listByIpAndPurpose(ip.getId(), Purpose.Firewall));
                }

                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Vpn, provider)) {
                    final RemoteAccessVpn vpn = _vpnDao.findByPublicIpAddress(ip.getId());
                    if (vpn != null) {
                        vpns.add(vpn);
                    }
                }

                if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.StaticNat, provider)) {
                    if (ip.isOneToOneNat()) {
                        final StaticNatImpl staticNat = new StaticNatImpl(ip.getAccountId(), ip.getDomainId(), guestNetworkId, ip.getId(), ip.getVmIp(), false);
                        staticNats.add(staticNat);
                    }
                }
            }

            // Re-apply static nats
            s_logger.debug("Found " + staticNats.size() + " static nat(s) to apply as a part of domR " + router + " start.");
            if (!staticNats.isEmpty()) {
                createApplyStaticNatCommands(staticNats, router, cmds, guestNetworkId);
            }

            // Re-apply firewall Ingress rules
            s_logger.debug("Found " + firewallRulesIngress.size() + " firewall Ingress rule(s) to apply as a part of domR " + router + " start.");
            if (!firewallRulesIngress.isEmpty()) {
                createFirewallRulesCommands(firewallRulesIngress, router, cmds, guestNetworkId);
            }

            // Re-apply port forwarding rules
            s_logger.debug("Found " + pfRules.size() + " port forwarding rule(s) to apply as a part of domR " + router + " start.");
            if (!pfRules.isEmpty()) {
                createApplyPortForwardingRulesCommands(pfRules, router, cmds, guestNetworkId);
            }

            // Re-apply static nat rules
            s_logger.debug("Found " + staticNatFirewallRules.size() + " static nat rule(s) to apply as a part of domR " + router + " start.");
            if (!staticNatFirewallRules.isEmpty()) {
                final List<StaticNatRule> staticNatRules = new ArrayList<StaticNatRule>();
                for (final FirewallRule rule : staticNatFirewallRules) {
                    staticNatRules.add(_rulesMgr.buildStaticNatRule(rule, false));
                }
                createApplyStaticNatRulesCommands(staticNatRules, router, cmds, guestNetworkId);
            }

            // Re-apply vpn rules
            s_logger.debug("Found " + vpns.size() + " vpn(s) to apply as a part of domR " + router + " start.");
            if (!vpns.isEmpty()) {
                for (final RemoteAccessVpn vpn : vpns) {
                    createApplyVpnCommands(true, vpn, router, cmds);
                }
            }

            final List<LoadBalancerVO> lbs = _loadBalancerDao.listByNetworkIdAndScheme(guestNetworkId, Scheme.Public);
            final List<LoadBalancingRule> lbRules = new ArrayList<LoadBalancingRule>();
            if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Lb, provider)) {
                // Re-apply load balancing rules
                for (final LoadBalancerVO lb : lbs) {
                    final List<LbDestination> dstList = _lbMgr.getExistingDestinations(lb.getId());
                    final List<LbStickinessPolicy> policyList = _lbMgr.getStickinessPolicies(lb.getId());
                    final List<LbHealthCheckPolicy> hcPolicyList = _lbMgr.getHealthCheckPolicies(lb.getId());
                    final Ip sourceIp = _networkModel.getPublicIpAddress(lb.getSourceIpAddressId()).getAddress();
                    final LbSslCert sslCert = _lbMgr.getLbSslCert(lb.getId());
                    final LoadBalancingRule loadBalancing = new LoadBalancingRule(lb, dstList, policyList, hcPolicyList, sourceIp, sslCert, lb.getLbProtocol());
                    lbRules.add(loadBalancing);
                }
            }

            s_logger.debug("Found " + lbRules.size() + " load balancing rule(s) to apply as a part of domR " + router + " start.");
            if (!lbRules.isEmpty()) {
                createApplyLoadBalancingRulesCommands(lbRules, router, cmds, guestNetworkId);
            }
        }
        //Reapply dhcp and dns configuration.
        final Network guestNetwork = _networkDao.findById(guestNetworkId);
        if (guestNetwork.getGuestType() == GuestType.Shared && _networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Dhcp, provider)) {
            final Map<Network.Capability, String> dhcpCapabilities =
                    _networkSvc.getNetworkOfferingServiceCapabilities(_networkOfferingDao.findById(_networkDao.findById(guestNetworkId).getNetworkOfferingId()), Service.Dhcp);
            final String supportsMultipleSubnets = dhcpCapabilities.get(Network.Capability.DhcpAccrossMultipleSubnets);
            if (supportsMultipleSubnets != null && Boolean.valueOf(supportsMultipleSubnets)) {
                final List<NicIpAliasVO> revokedIpAliasVOs = _nicIpAliasDao.listByNetworkIdAndState(guestNetworkId, NicIpAlias.state.revoked);
                s_logger.debug("Found" + revokedIpAliasVOs.size() + "ip Aliases to revoke on the router as a part of dhcp configuration");
                removeRevokedIpAliasFromDb(revokedIpAliasVOs);

                final List<NicIpAliasVO> aliasVOs = _nicIpAliasDao.listByNetworkIdAndState(guestNetworkId, NicIpAlias.state.active);
                s_logger.debug("Found" + aliasVOs.size() + "ip Aliases to apply on the router as a part of dhcp configuration");
                final List<IpAliasTO> activeIpAliasTOs = new ArrayList<IpAliasTO>();
                for (final NicIpAliasVO aliasVO : aliasVOs) {
                    activeIpAliasTOs.add(new IpAliasTO(aliasVO.getIp4Address(), aliasVO.getNetmask(), aliasVO.getAliasCount().toString()));
                }
                if (activeIpAliasTOs.size() != 0) {
                    createIpAlias(router, activeIpAliasTOs, guestNetworkId, cmds);
                    configDnsMasq(router, _networkDao.findById(guestNetworkId), cmds);
                }
            }
        }
    }

    private void removeRevokedIpAliasFromDb(final List<NicIpAliasVO> revokedIpAliasVOs) {
        for (final NicIpAliasVO ipalias : revokedIpAliasVOs) {
            _nicIpAliasDao.expunge(ipalias.getId());
        }
    }

    protected void finalizeIpAssocForNetwork(final Commands cmds, final VirtualRouter router, final Provider provider, final Long guestNetworkId,
            final Map<String, String> vlanMacAddress) {

        final ArrayList<? extends PublicIpAddress> publicIps = getPublicIpsToApply(router, provider, guestNetworkId);

        if (publicIps != null && !publicIps.isEmpty()) {
            s_logger.debug("Found " + publicIps.size() + " ip(s) to apply as a part of domR " + router + " start.");
            // Re-apply public ip addresses - should come before PF/LB/VPN
            if (_networkModel.isProviderSupportServiceInNetwork(guestNetworkId, Service.Firewall, provider)) {
                createAssociateIPCommands(router, publicIps, cmds, 0);
            }
        }
    }

    protected ArrayList<? extends PublicIpAddress> getPublicIpsToApply(final VirtualRouter router, final Provider provider, final Long guestNetworkId,
            final com.cloud.network.IpAddress.State... skipInStates) {
        final long ownerId = router.getAccountId();
        final List<? extends IpAddress> userIps;

        final Network guestNetwork = _networkDao.findById(guestNetworkId);
        if (guestNetwork.getGuestType() == GuestType.Shared) {
            // ignore the account id for the shared network
            userIps = _networkModel.listPublicIpsAssignedToGuestNtwk(guestNetworkId, null);
        } else {
            userIps = _networkModel.listPublicIpsAssignedToGuestNtwk(ownerId, guestNetworkId, null);
        }

        final List<PublicIp> allPublicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            boolean addIp = true;
            for (final IpAddress userIp : userIps) {
                if (skipInStates != null) {
                    for (final IpAddress.State stateToSkip : skipInStates) {
                        if (userIp.getState() == stateToSkip) {
                            s_logger.debug("Skipping ip address " + userIp + " in state " + userIp.getState());
                            addIp = false;
                            break;
                        }
                    }
                }

                if (addIp) {
                    final IPAddressVO ipVO = _ipAddressDao.findById(userIp.getId());
                    final PublicIp publicIp = PublicIp.createFromAddrAndVlan(ipVO, _vlanDao.findById(userIp.getVlanId()));
                    allPublicIps.add(publicIp);
                }
            }
        }

        //Get public Ips that should be handled by router
        final Network network = _networkDao.findById(guestNetworkId);
        final Map<PublicIpAddress, Set<Service>> ipToServices = _networkModel.getIpToServices(allPublicIps, false, true);
        final Map<Provider, ArrayList<PublicIpAddress>> providerToIpList = _networkModel.getProviderToIpList(network, ipToServices);
        // Only cover virtual router for now, if ELB use it this need to be modified

        final ArrayList<PublicIpAddress> publicIps = providerToIpList.get(provider);
        return publicIps;
    }

    @Override
    public boolean finalizeStart(final VirtualMachineProfile profile, final long hostId, final Commands cmds, final ReservationContext context) {
        DomainRouterVO router = _routerDao.findById(profile.getId());

        //process all the answers
        for (Answer answer : cmds.getAnswers()) {
            // handle any command failures
            if (!answer.getResult()) {
                String cmdClassName = answer.getClass().getCanonicalName().replace("Answer", "Command");
                String errorMessage = "Command: " + cmdClassName + " failed while starting virtual router";
                String errorDetails = "Details: " + answer.getDetails() + " " + answer.toString();
                //add alerts for the failed commands
                _alertMgr.sendAlert(AlertService.AlertType.ALERT_TYPE_DOMAIN_ROUTER, router.getDataCenterId(), router.getPodIdToDeployIn(), errorMessage, errorDetails);
                s_logger.warn(errorMessage);
                //Stop the router if any of the commands failed
                return false;
            }
        }

        // at this point, all the router command are successful.
        boolean result = true;
        //Get guest networks info
        final List<Network> guestNetworks = new ArrayList<Network>();

        final List<? extends Nic> routerNics = _nicDao.listByVmId(profile.getId());
        for (final Nic nic : routerNics) {
            final Network network = _networkModel.getNetwork(nic.getNetworkId());
            if (network.getTrafficType() == TrafficType.Guest) {
                guestNetworks.add(network);
                if (nic.getBroadcastUri().getScheme().equals("pvlan")) {
                    final NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                    result = setupDhcpForPvlan(true, router, router.getHostId(), nicProfile);
                }
            }
        }
        if (result) {
            GetDomRVersionAnswer versionAnswer = (GetDomRVersionAnswer)cmds.getAnswer("getDomRVersion");
            router.setTemplateVersion(versionAnswer.getTemplateVersion());
            router.setScriptsVersion(versionAnswer.getScriptsVersion());
            _routerDao.persist(router, guestNetworks);
        }

        return result;
    }

    @Override
    public void finalizeStop(final VirtualMachineProfile profile, final Answer answer) {
        if (answer != null) {
            final VirtualMachine vm = profile.getVirtualMachine();
            final DomainRouterVO domR = _routerDao.findById(vm.getId());
            processStopOrRebootAnswer(domR, answer);
            final List<? extends Nic> routerNics = _nicDao.listByVmId(profile.getId());
            for (final Nic nic : routerNics) {
                final Network network = _networkModel.getNetwork(nic.getNetworkId());
                if (network.getTrafficType() == TrafficType.Guest && nic.getBroadcastUri() != null && nic.getBroadcastUri().getScheme().equals("pvlan")) {
                    final NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 0, false, "pvlan-nic");
                    setupDhcpForPvlan(false, domR, domR.getHostId(), nicProfile);
                }
            }

        }
    }

    @Override
    public void finalizeExpunge(final VirtualMachine vm) {
    }

    @Override
    public boolean startRemoteAccessVpn(final Network network, final RemoteAccessVpn vpn, final List<? extends VirtualRouter> routers) throws ResourceUnavailableException {
        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Failed to start remote access VPN: no router found for account and zone");
            throw new ResourceUnavailableException("Failed to start remote access VPN: no router found for account and zone", DataCenter.class, network.getDataCenterId());
        }

        for (final VirtualRouter router : routers) {
            if (router.getState() != State.Running) {
                s_logger.warn("Failed to start remote access VPN: router not in right state " + router.getState());
                throw new ResourceUnavailableException("Failed to start remote access VPN: router not in right state " + router.getState(), DataCenter.class,
                        network.getDataCenterId());
            }

            final Commands cmds = new Commands(Command.OnError.Stop);
            createApplyVpnCommands(true, vpn, router, cmds);

            if (!sendCommandsToRouter(router, cmds)) {
                throw new AgentUnavailableException("Unable to send commands to virtual router ", router.getHostId());
            }

            Answer answer = cmds.getAnswer("users");
            if (!answer.getResult()) {
                s_logger.error("Unable to start vpn: unable add users to vpn in zone " + router.getDataCenterId() + " for account " + vpn.getAccountId() + " on domR: " +
                        router.getInstanceName() + " due to " + answer.getDetails());
                throw new ResourceUnavailableException("Unable to start vpn: Unable to add users to vpn in zone " + router.getDataCenterId() + " for account " +
                        vpn.getAccountId() + " on domR: " + router.getInstanceName() + " due to " + answer.getDetails(), DataCenter.class, router.getDataCenterId());
            }
            answer = cmds.getAnswer("startVpn");
            if (!answer.getResult()) {
                s_logger.error("Unable to start vpn in zone " + router.getDataCenterId() + " for account " + vpn.getAccountId() + " on domR: " +
                        router.getInstanceName() + " due to " + answer.getDetails());
                throw new ResourceUnavailableException("Unable to start vpn in zone " + router.getDataCenterId() + " for account " + vpn.getAccountId() + " on domR: " +
                        router.getInstanceName() + " due to " + answer.getDetails(), DataCenter.class, router.getDataCenterId());
            }

        }
        return true;
    }

    @Override
    public boolean deleteRemoteAccessVpn(final Network network, final RemoteAccessVpn vpn, final List<? extends VirtualRouter> routers) throws ResourceUnavailableException {
        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Failed to delete remote access VPN: no router found for account and zone");
            throw new ResourceUnavailableException("Failed to delete remote access VPN", DataCenter.class, network.getDataCenterId());
        }

        boolean result = true;
        for (final VirtualRouter router : routers) {
            if (router.getState() == State.Running) {
                final Commands cmds = new Commands(Command.OnError.Continue);
                createApplyVpnCommands(false, vpn, router, cmds);
                result = result && sendCommandsToRouter(router, cmds);
            } else if (router.getState() == State.Stopped) {
                s_logger.debug("Router " + router + " is in Stopped state, not sending deleteRemoteAccessVpn command to it");
                continue;
            } else {
                s_logger.warn("Failed to delete remote access VPN: domR " + router + " is not in right state " + router.getState());
                throw new ResourceUnavailableException("Failed to delete remote access VPN: domR is not in right state " + router.getState(), DataCenter.class,
                        network.getDataCenterId());
            }
        }

        return result;
    }

    @Override
    public DomainRouterVO stop(final VirtualRouter router, final boolean forced, final User user, final Account caller) throws ConcurrentOperationException,
    ResourceUnavailableException {
        s_logger.debug("Stopping router " + router);
        try {
            _itMgr.advanceStop(router.getUuid(), forced);
            return _routerDao.findById(router.getId());
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to stop " + router, e);
        }
    }

    @Override
    public boolean configDhcpForSubnet(final Network network, final NicProfile nic, final VirtualMachineProfile profile, final DeployDestination dest, final List<DomainRouterVO> routers)
            throws ResourceUnavailableException {
        final UserVmVO vm = _userVmDao.findById(profile.getId());
        _userVmDao.loadDetails(vm);

        //Asuming we have only one router per network For Now.
        final DomainRouterVO router = routers.get(0);
        if (router.getState() != State.Running) {
            s_logger.warn("Failed to configure dhcp: router not in running state");
            throw new ResourceUnavailableException("Unable to assign ip addresses, domR is not in right state " + router.getState(), DataCenter.class,
                    network.getDataCenterId());
        }
        //check if this is not the primary subnet.
        final NicVO domr_guest_nic =
                _nicDao.findByInstanceIdAndIpAddressAndVmtype(router.getId(), _nicDao.getIpAddress(nic.getNetworkId(), router.getId()), VirtualMachine.Type.DomainRouter);
        //check if the router ip address and the vm ip address belong to same subnet.
        //if they do not belong to same netwoek check for the alias ips. if not create one.
        // This should happen only in case of Basic and Advanced SG enabled networks.
        if (!NetUtils.sameSubnet(domr_guest_nic.getIp4Address(), nic.getIp4Address(), nic.getNetmask())) {
            final List<NicIpAliasVO> aliasIps = _nicIpAliasDao.listByNetworkIdAndState(domr_guest_nic.getNetworkId(), NicIpAlias.state.active);
            boolean ipInVmsubnet = false;
            for (final NicIpAliasVO alias : aliasIps) {
                //check if any of the alias ips belongs to the Vm's subnet.
                if (NetUtils.sameSubnet(alias.getIp4Address(), nic.getIp4Address(), nic.getNetmask())) {
                    ipInVmsubnet = true;
                    break;
                }
            }
            PublicIp routerPublicIP = null;
            String routerAliasIp = null;
            final DataCenter dc = _dcDao.findById(router.getDataCenterId());
            if (ipInVmsubnet == false) {
                try {
                    if (network.getTrafficType() == TrafficType.Guest && network.getGuestType() == GuestType.Shared) {
                        _podDao.findById(vm.getPodIdToDeployIn());
                        final Account caller = CallContext.current().getCallingAccount();
                        final List<VlanVO> vlanList = _vlanDao.listVlansByNetworkIdAndGateway(network.getId(), nic.getGateway());
                        final List<Long> vlanDbIdList = new ArrayList<Long>();
                        for (final VlanVO vlan : vlanList) {
                            vlanDbIdList.add(vlan.getId());
                        }
                        if (dc.getNetworkType() == NetworkType.Basic) {
                            routerPublicIP =
                                    _ipAddrMgr.assignPublicIpAddressFromVlans(router.getDataCenterId(), vm.getPodIdToDeployIn(), caller, Vlan.VlanType.DirectAttached,
                                            vlanDbIdList, nic.getNetworkId(), null, false);
                        } else {
                            routerPublicIP =
                                    _ipAddrMgr.assignPublicIpAddressFromVlans(router.getDataCenterId(), null, caller, Vlan.VlanType.DirectAttached, vlanDbIdList,
                                            nic.getNetworkId(), null, false);
                        }

                        routerAliasIp = routerPublicIP.getAddress().addr();
                    }
                } catch (final InsufficientAddressCapacityException e) {
                    s_logger.info(e.getMessage());
                    s_logger.info("unable to configure dhcp for this VM.");
                    return false;
                }
                //this means we did not create a ip alis on the router.
                final NicIpAliasVO alias =
                        new NicIpAliasVO(domr_guest_nic.getId(), routerAliasIp, router.getId(), CallContext.current().getCallingAccountId(), network.getDomainId(),
                                nic.getNetworkId(), nic.getGateway(), nic.getNetmask());
                alias.setAliasCount((routerPublicIP.getIpMacAddress()));
                _nicIpAliasDao.persist(alias);
                final List<IpAliasTO> ipaliasTo = new ArrayList<IpAliasTO>();
                ipaliasTo.add(new IpAliasTO(routerAliasIp, alias.getNetmask(), alias.getAliasCount().toString()));
                final Commands cmds = new Commands(Command.OnError.Stop);
                createIpAlias(router, ipaliasTo, alias.getNetworkId(), cmds);
                //also add the required configuration to the dnsmasq for supporting dhcp and dns on the new ip.
                configDnsMasq(router, network, cmds);
                final boolean result = sendCommandsToRouter(router, cmds);
                if (result == false) {
                    final NicIpAliasVO ipAliasVO = _nicIpAliasDao.findByInstanceIdAndNetworkId(network.getId(), router.getId());
                    final PublicIp routerPublicIPFinal = routerPublicIP;
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(final TransactionStatus status) {
                            _nicIpAliasDao.expunge(ipAliasVO.getId());
                            _ipAddressDao.unassignIpAddress(routerPublicIPFinal.getId());
                        }
                    });
                    throw new CloudRuntimeException("failed to configure ip alias on the router as a part of dhcp config");
                }
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean removeDhcpSupportForSubnet(final Network network, final List<DomainRouterVO> routers) throws ResourceUnavailableException {
        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Failed to add/remove VPN users: no router found for account and zone");
            throw new ResourceUnavailableException("Unable to assign ip addresses, domR doesn't exist for network " + network.getId(), DataCenter.class,
                    network.getDataCenterId());
        }

        for (final DomainRouterVO router : routers) {
            if (router.getState() != State.Running) {
                s_logger.warn("Failed to add/remove VPN users: router not in running state");
                throw new ResourceUnavailableException("Unable to assign ip addresses, domR is not in right state " + router.getState(), DataCenter.class,
                        network.getDataCenterId());
            }

            final Commands cmds = new Commands(Command.OnError.Continue);
            final List<NicIpAliasVO> revokedIpAliasVOs = _nicIpAliasDao.listByNetworkIdAndState(network.getId(), NicIpAlias.state.revoked);
            s_logger.debug("Found" + revokedIpAliasVOs.size() + "ip Aliases to revoke on the router as a part of dhcp configuration");
            final List<IpAliasTO> revokedIpAliasTOs = new ArrayList<IpAliasTO>();
            for (final NicIpAliasVO revokedAliasVO : revokedIpAliasVOs) {
                revokedIpAliasTOs.add(new IpAliasTO(revokedAliasVO.getIp4Address(), revokedAliasVO.getNetmask(), revokedAliasVO.getAliasCount().toString()));
            }
            final List<NicIpAliasVO> aliasVOs = _nicIpAliasDao.listByNetworkIdAndState(network.getId(), NicIpAlias.state.active);
            s_logger.debug("Found" + aliasVOs.size() + "ip Aliases to apply on the router as a part of dhcp configuration");
            final List<IpAliasTO> activeIpAliasTOs = new ArrayList<IpAliasTO>();
            for (final NicIpAliasVO aliasVO : aliasVOs) {
                activeIpAliasTOs.add(new IpAliasTO(aliasVO.getIp4Address(), aliasVO.getNetmask(), aliasVO.getAliasCount().toString()));
            }
            createDeleteIpAliasCommand(router, revokedIpAliasTOs, activeIpAliasTOs, network.getId(), cmds);
            configDnsMasq(router, network, cmds);
            final boolean result = sendCommandsToRouter(router, cmds);
            if (result) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        for (final NicIpAliasVO revokedAliasVO : revokedIpAliasVOs) {
                            _nicIpAliasDao.expunge(revokedAliasVO.getId());
                        }
                    }
                });
                return true;
            }
        }
        return false;
    }

    private void createDeleteIpAliasCommand(final DomainRouterVO router, final List<IpAliasTO> deleteIpAliasTOs, final List<IpAliasTO> createIpAliasTos, final long networkId,
            final Commands cmds) {
        final String routerip = getRouterIpInNetwork(networkId, router.getId());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        final DeleteIpAliasCommand deleteIpaliasCmd = new DeleteIpAliasCommand(routerip, deleteIpAliasTOs, createIpAliasTos);
        deleteIpaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        deleteIpaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        deleteIpaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, routerip);
        deleteIpaliasCmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand("deleteIpalias", deleteIpaliasCmd);
    }

    private NicVO findDefaultDnsIp(final long userVmId) {
        final NicVO defaultNic = _nicDao.findDefaultNicForVM(userVmId);

        //check if DNS provider is the domR
        if (!_networkModel.isProviderSupportServiceInNetwork(defaultNic.getNetworkId(), Service.Dns, Provider.VirtualRouter)) {
            return null;
        }

        final NetworkOffering offering = _networkOfferingDao.findById(_networkDao.findById(defaultNic.getNetworkId()).getNetworkOfferingId());
        if (offering.getRedundantRouter()) {
            return findGatewayIp(userVmId);
        }

        final DataCenter dc = _dcDao.findById(_networkModel.getNetwork(defaultNic.getNetworkId()).getDataCenterId());
        final boolean isZoneBasic = (dc.getNetworkType() == NetworkType.Basic);

        //find domR's nic in the network
        NicVO domrDefaultNic;
        if (isZoneBasic) {
            domrDefaultNic = _nicDao.findByNetworkIdTypeAndGateway(defaultNic.getNetworkId(), VirtualMachine.Type.DomainRouter, defaultNic.getGateway());
        } else {
            domrDefaultNic = _nicDao.findByNetworkIdAndType(defaultNic.getNetworkId(), VirtualMachine.Type.DomainRouter);
        }
        return domrDefaultNic;
    }

    private NicVO findGatewayIp(final long userVmId) {
        final NicVO defaultNic = _nicDao.findDefaultNicForVM(userVmId);
        return defaultNic;
    }

    protected void createApplyVpnUsersCommand(final List<? extends VpnUser> users, final VirtualRouter router, final Commands cmds) {
        final List<VpnUser> addUsers = new ArrayList<VpnUser>();
        final List<VpnUser> removeUsers = new ArrayList<VpnUser>();
        for (final VpnUser user : users) {
            if (user.getState() == VpnUser.State.Add || user.getState() == VpnUser.State.Active) {
                addUsers.add(user);
            } else if (user.getState() == VpnUser.State.Revoke) {
                removeUsers.add(user);
            }
        }

        final VpnUsersCfgCommand cmd = new VpnUsersCfgCommand(addUsers, removeUsers);
        cmd.setAccessDetail(NetworkElementCommand.ACCOUNT_ID, String.valueOf(router.getAccountId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand("users", cmd);
    }

    @Override
    //FIXME add partial success and STOP state support
    public
    String[] applyVpnUsers(final Network network, final List<? extends VpnUser> users, final List<DomainRouterVO> routers) throws ResourceUnavailableException {
        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Failed to add/remove VPN users: no router found for account and zone");
            throw new ResourceUnavailableException("Unable to assign ip addresses, domR doesn't exist for network " + network.getId(), DataCenter.class,
                    network.getDataCenterId());
        }

        boolean agentResults = true;

        for (final DomainRouterVO router : routers) {
            if (router.getState() != State.Running) {
                s_logger.warn("Failed to add/remove VPN users: router not in running state");
                throw new ResourceUnavailableException("Unable to assign ip addresses, domR is not in right state " + router.getState(), DataCenter.class,
                        network.getDataCenterId());
            }

            final Commands cmds = new Commands(Command.OnError.Continue);
            createApplyVpnUsersCommand(users, router, cmds);

            // Currently we receive just one answer from the agent. In the future we have to parse individual answers and set
            // results accordingly
            final boolean agentResult = sendCommandsToRouter(router, cmds);
            agentResults = agentResults && agentResult;
        }

        final String[] result = new String[users.size()];
        for (int i = 0; i < result.length; i++) {
            if (agentResults) {
                result[i] = null;
            } else {
                result[i] = String.valueOf(agentResults);
            }
        }

        return result;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ROUTER_START, eventDescription = "starting router Vm", async = true)
    public VirtualRouter startRouter(final long id) throws ResourceUnavailableException, InsufficientCapacityException, ConcurrentOperationException {
        return startRouter(id, true);
    }

    @Override
    public VirtualRouter startRouter(final long routerId, final boolean reprogramNetwork) throws ResourceUnavailableException, InsufficientCapacityException,
    ConcurrentOperationException {
        final Account caller = CallContext.current().getCallingAccount();
        final User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());

        // verify parameters
        DomainRouterVO router = _routerDao.findById(routerId);
        if (router == null) {
            throw new InvalidParameterValueException("Unable to find router by id " + routerId + ".");
        }
        _accountMgr.checkAccess(caller, null, true, router);

        final Account owner = _accountMgr.getAccount(router.getAccountId());

        // Check if all networks are implemented for the domR; if not - implement them
        final DataCenter dc = _dcDao.findById(router.getDataCenterId());
        HostPodVO pod = null;
        if (router.getPodIdToDeployIn() != null) {
            pod = _podDao.findById(router.getPodIdToDeployIn());
        }
        final DeployDestination dest = new DeployDestination(dc, pod, null, null);

        final ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);

        final List<NicVO> nics = _nicDao.listByVmId(routerId);

        for (final NicVO nic : nics) {
            if (!_networkMgr.startNetwork(nic.getNetworkId(), dest, context)) {
                s_logger.warn("Failed to start network id=" + nic.getNetworkId() + " as a part of domR start");
                throw new CloudRuntimeException("Failed to start network id=" + nic.getNetworkId() + " as a part of domR start");
            }
        }

        //After start network, check if it's already running
        router = _routerDao.findById(routerId);
        if (router.getState() == State.Running) {
            return router;
        }

        final UserVO user = _userDao.findById(CallContext.current().getCallingUserId());
        final Map<Param, Object> params = new HashMap<Param, Object>();
        if (reprogramNetwork) {
            params.put(Param.ReProgramGuestNetworks, true);
        } else {
            params.put(Param.ReProgramGuestNetworks, false);
        }
        final VirtualRouter virtualRouter = nwHelper.startVirtualRouter(router, user, caller, params);
        if (virtualRouter == null) {
            throw new CloudRuntimeException("Failed to start router with id " + routerId);
        }
        return virtualRouter;
    }

    private void createAssociateIPCommands(final VirtualRouter router, final List<? extends PublicIpAddress> ips, final Commands cmds, final long vmId) {

        // Ensure that in multiple vlans case we first send all ip addresses of vlan1, then all ip addresses of vlan2, etc..
        final Map<String, ArrayList<PublicIpAddress>> vlanIpMap = new HashMap<String, ArrayList<PublicIpAddress>>();
        for (final PublicIpAddress ipAddress : ips) {
            final String vlanTag = ipAddress.getVlanTag();
            ArrayList<PublicIpAddress> ipList = vlanIpMap.get(vlanTag);
            if (ipList == null) {
                ipList = new ArrayList<PublicIpAddress>();
            }
            //domR doesn't support release for sourceNat IP address; so reset the state
            if (ipAddress.isSourceNat() && ipAddress.getState() == IpAddress.State.Releasing) {
                ipAddress.setState(IpAddress.State.Allocated);
            }
            ipList.add(ipAddress);
            vlanIpMap.put(vlanTag, ipList);
        }

        final List<NicVO> nics = _nicDao.listByVmId(router.getId());
        String baseMac = null;
        for (final NicVO nic : nics) {
            final NetworkVO nw = _networkDao.findById(nic.getNetworkId());
            if (nw.getTrafficType() == TrafficType.Public) {
                baseMac = nic.getMacAddress();
                break;
            }
        }

        for (final Map.Entry<String, ArrayList<PublicIpAddress>> vlanAndIp : vlanIpMap.entrySet()) {
            final List<PublicIpAddress> ipAddrList = vlanAndIp.getValue();
            // Source nat ip address should always be sent first
            Collections.sort(ipAddrList, new Comparator<PublicIpAddress>() {
                @Override
                public int compare(final PublicIpAddress o1, final PublicIpAddress o2) {
                    final boolean s1 = o1.isSourceNat();
                    final boolean s2 = o2.isSourceNat();
                    return (s1 ^ s2) ? ((s1 ^ true) ? 1 : -1) : 0;
                }
            });

            // Get network rate - required for IpAssoc
            final Integer networkRate = _networkModel.getNetworkRate(ipAddrList.get(0).getNetworkId(), router.getId());
            final Network network = _networkModel.getNetwork(ipAddrList.get(0).getNetworkId());

            final IpAddressTO[] ipsToSend = new IpAddressTO[ipAddrList.size()];
            int i = 0;
            boolean firstIP = true;
            boolean isSourceNatNw = false;

            for (final PublicIpAddress ipAddr : ipAddrList) {

                final boolean add = (ipAddr.getState() == IpAddress.State.Releasing ? false : true);
                boolean sourceNat = ipAddr.isSourceNat();

                //set the isSourceNatNw from the first ip of ipAddrList
                //For non source network ips the isSourceNatNw is always false
                if (sourceNat) {
                    isSourceNatNw = ipAddr.isSourceNat();
                }

                /* enable sourceNAT for the first ip of the public interface */
                if (firstIP) {
                    sourceNat = true;
                }

                // setting sourceNat=true to make sure the snat rule of the ip is deleted
                if (!isSourceNatNw && !add ) {
                    sourceNat = true;
                }
                final String vlanId = ipAddr.getVlanTag();
                final String vlanGateway = ipAddr.getGateway();
                final String vlanNetmask = ipAddr.getNetmask();
                String vifMacAddress = null;
                // For non-source nat IP, set the mac to be something based on first public nic's MAC
                // We cannot depends on first ip because we need to deal with first ip of other nics
                if (!ipAddr.isSourceNat() && ipAddr.getVlanId() != 0) {
                    vifMacAddress = NetUtils.generateMacOnIncrease(baseMac, ipAddr.getVlanId());
                } else {
                    vifMacAddress = ipAddr.getMacAddress();
                }

                final IpAddressTO ip =
                        new IpAddressTO(ipAddr.getAccountId(), ipAddr.getAddress().addr(), add, firstIP, sourceNat, vlanId, vlanGateway, vlanNetmask, vifMacAddress,
                                networkRate, ipAddr.isOneToOneNat());

                ip.setTrafficType(network.getTrafficType());
                ip.setNetworkName(_networkModel.getNetworkTag(router.getHypervisorType(), network));
                ipsToSend[i++] = ip;
                /* send the firstIP = true for the first Add, this is to create primary on interface*/
                if (!firstIP || add) {
                    firstIP = false;
                }
            }
            final IpAssocCommand cmd = new IpAssocCommand(ipsToSend);
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(ipAddrList.get(0).getAssociatedWithNetworkId(), router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
            final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
            cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

            cmds.addCommand("IPAssocCommand", cmd);
        }
    }

    private void createApplyPortForwardingRulesCommands(final List<? extends PortForwardingRule> rules, final VirtualRouter router, final Commands cmds, final long guestNetworkId) {
        List<PortForwardingRuleTO> rulesTO = new ArrayList<PortForwardingRuleTO>();
        if (rules != null) {
            for (final PortForwardingRule rule : rules) {
                final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
                final PortForwardingRuleTO ruleTO = new PortForwardingRuleTO(rule, null, sourceIp.getAddress().addr());
                rulesTO.add(ruleTO);
            }
        }

        SetPortForwardingRulesCommand cmd = null;

        if (router.getVpcId() != null) {
            cmd = new SetPortForwardingRulesVpcCommand(rulesTO);
        } else {
            cmd = new SetPortForwardingRulesCommand(rulesTO);
        }

        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand(cmd);
    }

    private void createApplyStaticNatRulesCommands(final List<? extends StaticNatRule> rules, final VirtualRouter router, final Commands cmds, final long guestNetworkId) {
        List<StaticNatRuleTO> rulesTO = new ArrayList<StaticNatRuleTO>();
        if (rules != null) {
            for (final StaticNatRule rule : rules) {
                final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
                final StaticNatRuleTO ruleTO = new StaticNatRuleTO(rule, null, sourceIp.getAddress().addr(), rule.getDestIpAddress());
                rulesTO.add(ruleTO);
            }
        }

        final SetStaticNatRulesCommand cmd = new SetStaticNatRulesCommand(rulesTO, router.getVpcId());
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
        cmds.addCommand(cmd);
    }

    private void createApplyLoadBalancingRulesCommands(final List<LoadBalancingRule> rules, final VirtualRouter router, final Commands cmds, final long guestNetworkId) {

        final LoadBalancerTO[] lbs = new LoadBalancerTO[rules.size()];
        int i = 0;
        // We don't support VR to be inline currently
        final boolean inline = false;
        for (final LoadBalancingRule rule : rules) {
            final boolean revoked = (rule.getState().equals(FirewallRule.State.Revoke));
            final String protocol = rule.getProtocol();
            final String algorithm = rule.getAlgorithm();
            final String uuid = rule.getUuid();

            final String srcIp = rule.getSourceIp().addr();
            final int srcPort = rule.getSourcePortStart();
            final List<LbDestination> destinations = rule.getDestinations();
            final List<LbStickinessPolicy> stickinessPolicies = rule.getStickinessPolicies();
            final LoadBalancerTO lb = new LoadBalancerTO(uuid, srcIp, srcPort, protocol, algorithm, revoked, false, inline, destinations, stickinessPolicies);
            lbs[i++] = lb;
        }
        String routerPublicIp = null;

        if (router instanceof DomainRouterVO) {
            final DomainRouterVO domr = _routerDao.findById(router.getId());
            routerPublicIp = domr.getPublicIpAddress();
        }

        final Network guestNetwork = _networkModel.getNetwork(guestNetworkId);
        final Nic nic = _nicDao.findByNtwkIdAndInstanceId(guestNetwork.getId(), router.getId());
        final NicProfile nicProfile =
                new NicProfile(nic, guestNetwork, nic.getBroadcastUri(), nic.getIsolationUri(), _networkModel.getNetworkRate(guestNetwork.getId(), router.getId()),
                        _networkModel.isSecurityGroupSupportedInNetwork(guestNetwork), _networkModel.getNetworkTag(router.getHypervisorType(), guestNetwork));
        final NetworkOffering offering = _networkOfferingDao.findById(guestNetwork.getNetworkOfferingId());
        String maxconn = null;
        if (offering.getConcurrentConnections() == null) {
            maxconn = _configDao.getValue(Config.NetworkLBHaproxyMaxConn.key());
        } else {
            maxconn = offering.getConcurrentConnections().toString();
        }

        final LoadBalancerConfigCommand cmd =
                new LoadBalancerConfigCommand(lbs, routerPublicIp, getRouterIpInNetwork(guestNetworkId, router.getId()), router.getPrivateIpAddress(), _itMgr.toNicTO(
                        nicProfile, router.getHypervisorType()), router.getVpcId(), maxconn, offering.isKeepAliveEnabled());

        cmd.lbStatsVisibility = _configDao.getValue(Config.NetworkLBHaproxyStatsVisbility.key());
        cmd.lbStatsUri = _configDao.getValue(Config.NetworkLBHaproxyStatsUri.key());
        cmd.lbStatsAuth = _configDao.getValue(Config.NetworkLBHaproxyStatsAuth.key());
        cmd.lbStatsPort = _configDao.getValue(Config.NetworkLBHaproxyStatsPort.key());

        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
        cmds.addCommand(cmd);

    }

    protected String getVpnCidr(final RemoteAccessVpn vpn) {
        final Network network = _networkDao.findById(vpn.getNetworkId());
        return network.getCidr();
    }

    protected void createApplyVpnCommands(final boolean isCreate, final RemoteAccessVpn vpn, final VirtualRouter router, final Commands cmds) {
        final List<VpnUserVO> vpnUsers = _vpnUsersDao.listByAccount(vpn.getAccountId());

        createApplyVpnUsersCommand(vpnUsers, router, cmds);

        final IpAddress ip = _networkModel.getIp(vpn.getServerAddressId());

        final String cidr = getVpnCidr(vpn);
        final RemoteAccessVpnCfgCommand startVpnCmd =
                new RemoteAccessVpnCfgCommand(isCreate, ip.getAddress().addr(), vpn.getLocalIp(), vpn.getIpRange(), vpn.getIpsecPresharedKey(), (vpn.getVpcId() != null));
        startVpnCmd.setLocalCidr(cidr);
        startVpnCmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        startVpnCmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        startVpnCmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand("startVpn", startVpnCmd);
    }

    private void createPasswordCommand(final VirtualRouter router, final VirtualMachineProfile profile, final NicVO nic, final Commands cmds) {
        final String password = (String)profile.getParameter(VirtualMachineProfile.Param.VmPassword);
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());

        // password should be set only on default network element
        if (password != null && nic.isDefaultNic()) {
            final String encodedPassword = PasswordGenerator.rot13(password);
            final SavePasswordCommand cmd =
                    new SavePasswordCommand(encodedPassword, nic.getIp4Address(), profile.getVirtualMachine().getHostName(), _networkModel.getExecuteInSeqNtwkElmtCmd());
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(nic.getNetworkId(), router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
            cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

            cmds.addCommand("password", cmd);
        }

    }

    private void createVmDataCommand(final VirtualRouter router, final UserVm vm, final NicVO nic, final String publicKey, final Commands cmds) {
        final String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
        final String zoneName = _dcDao.findById(router.getDataCenterId()).getName();
        cmds.addCommand(
                "vmdata",
                generateVmDataCommand(router, nic.getIp4Address(), vm.getUserData(), serviceOffering, zoneName, nic.getIp4Address(), vm.getHostName(), vm.getInstanceName(),
                        vm.getId(), vm.getUuid(), publicKey, nic.getNetworkId()));
    }

    private void createVmDataCommandForVMs(final DomainRouterVO router, final Commands cmds, final long guestNetworkId) {
        final List<UserVmVO> vms = _userVmDao.listByNetworkIdAndStates(guestNetworkId, State.Running, State.Migrating, State.Stopping);
        final DataCenterVO dc = _dcDao.findById(router.getDataCenterId());
        for (final UserVmVO vm : vms) {
            boolean createVmData = true;
            if (dc.getNetworkType() == NetworkType.Basic && router.getPodIdToDeployIn().longValue() != vm.getPodIdToDeployIn().longValue()) {
                createVmData = false;
            }

            if (createVmData) {
                final NicVO nic = _nicDao.findByNtwkIdAndInstanceId(guestNetworkId, vm.getId());
                if (nic != null) {
                    s_logger.debug("Creating user data entry for vm " + vm + " on domR " + router);
                    createVmDataCommand(router, vm, nic, null, cmds);
                }
            }
        }
    }

    private void createDhcpEntryCommand(final VirtualRouter router, final UserVm vm, final NicVO nic, final Commands cmds) {
        final DhcpEntryCommand dhcpCommand =
                new DhcpEntryCommand(nic.getMacAddress(), nic.getIp4Address(), vm.getHostName(), nic.getIp6Address(), _networkModel.getExecuteInSeqNtwkElmtCmd());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        final Nic defaultNic = findGatewayIp(vm.getId());
        String gatewayIp = defaultNic.getGateway();
        if (gatewayIp != null && !gatewayIp.equals(nic.getGateway())) {
            gatewayIp = "0.0.0.0";
        }
        dhcpCommand.setDefaultRouter(gatewayIp);
        dhcpCommand.setIp6Gateway(nic.getIp6Gateway());
        String ipaddress = null;
        final NicVO domrDefaultNic = findDefaultDnsIp(vm.getId());
        if (domrDefaultNic != null) {
            ipaddress = domrDefaultNic.getIp4Address();
        }
        dhcpCommand.setDefaultDns(ipaddress);
        dhcpCommand.setDuid(NetUtils.getDuidLL(nic.getMacAddress()));
        dhcpCommand.setDefault(nic.isDefaultNic());

        dhcpCommand.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        dhcpCommand.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        dhcpCommand.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(nic.getNetworkId(), router.getId()));
        dhcpCommand.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand("dhcp", dhcpCommand);
    }

    private void configDnsMasq(final VirtualRouter router, final Network network, final Commands cmds) {
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        final List<NicIpAliasVO> ipAliasVOList = _nicIpAliasDao.listByNetworkIdAndState(network.getId(), NicIpAlias.state.active);
        final List<DhcpTO> ipList = new ArrayList<DhcpTO>();

        final NicVO router_guest_nic = _nicDao.findByNtwkIdAndInstanceId(network.getId(), router.getId());
        final String cidr = NetUtils.getCidrFromGatewayAndNetmask(router_guest_nic.getGateway(), router_guest_nic.getNetmask());
        final String[] cidrPair = cidr.split("\\/");
        final String cidrAddress = cidrPair[0];
        final long cidrSize = Long.parseLong(cidrPair[1]);
        final String startIpOfSubnet = NetUtils.getIpRangeStartIpFromCidr(cidrAddress, cidrSize);

        ipList.add(new DhcpTO(router_guest_nic.getIp4Address(), router_guest_nic.getGateway(), router_guest_nic.getNetmask(), startIpOfSubnet));
        for (final NicIpAliasVO ipAliasVO : ipAliasVOList) {
            final DhcpTO DhcpTO = new DhcpTO(ipAliasVO.getIp4Address(), ipAliasVO.getGateway(), ipAliasVO.getNetmask(), ipAliasVO.getStartIpOfSubnet());
            if (s_logger.isTraceEnabled()) {
                s_logger.trace("configDnsMasq : adding ip {" + DhcpTO.getGateway() + ", " + DhcpTO.getNetmask() + ", " + DhcpTO.getRouterIp() + ", " +
                        DhcpTO.getStartIpOfSubnet() + "}");
            }
            ipList.add(DhcpTO);
            ipAliasVO.setVmId(router.getId());
        }
        _dcDao.findById(router.getDataCenterId());
        final DnsMasqConfigCommand dnsMasqConfigCmd = new DnsMasqConfigCommand(ipList);
        dnsMasqConfigCmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        dnsMasqConfigCmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        dnsMasqConfigCmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(network.getId(), router.getId()));
        dnsMasqConfigCmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
        cmds.addCommand("dnsMasqConfig", dnsMasqConfigCmd);
    }

    private void createIpAlias(final VirtualRouter router, final List<IpAliasTO> ipAliasTOs, final Long networkid, final Commands cmds) {

        final String routerip = getRouterIpInNetwork(networkid, router.getId());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        final CreateIpAliasCommand ipaliasCmd = new CreateIpAliasCommand(routerip, ipAliasTOs);
        ipaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        ipaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        ipaliasCmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, routerip);
        ipaliasCmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

        cmds.addCommand("ipalias", ipaliasCmd);
    }

    private void createDhcpEntryCommandsForVMs(final DomainRouterVO router, final Commands cmds, final long guestNetworkId) {
        final List<UserVmVO> vms = _userVmDao.listByNetworkIdAndStates(guestNetworkId, State.Running, State.Migrating, State.Stopping);
        final DataCenterVO dc = _dcDao.findById(router.getDataCenterId());
        for (final UserVmVO vm : vms) {
            boolean createDhcp = true;
            if (dc.getNetworkType() == NetworkType.Basic && router.getPodIdToDeployIn().longValue() != vm.getPodIdToDeployIn().longValue() &&
                    _dnsBasicZoneUpdates.equalsIgnoreCase("pod")) {
                createDhcp = false;
            }
            if (createDhcp) {
                final NicVO nic = _nicDao.findByNtwkIdAndInstanceId(guestNetworkId, vm.getId());
                if (nic != null) {
                    s_logger.debug("Creating dhcp entry for vm " + vm + " on domR " + router + ".");
                    createDhcpEntryCommand(router, vm, nic, cmds);
                }
            }
        }
    }

    protected boolean sendCommandsToRouter(final VirtualRouter router, final Commands cmds) throws AgentUnavailableException {
        if(!nwHelper.checkRouterVersion(router)){
            s_logger.debug("Router requires upgrade. Unable to send command to router:" + router.getId() + ", router template version : " + router.getTemplateVersion()
                    + ", minimal required version : " + MinVRVersion);
            throw new CloudRuntimeException("Unable to send command. Upgrade in progress. Please contact administrator.");
        }
        Answer[] answers = null;
        try {
            answers = _agentMgr.send(router.getHostId(), cmds);
        } catch (final OperationTimedoutException e) {
            s_logger.warn("Timed Out", e);
            throw new AgentUnavailableException("Unable to send commands to virtual router ", router.getHostId(), e);
        }

        if (answers == null) {
            return false;
        }

        if (answers.length != cmds.size()) {
            return false;
        }

        // FIXME: Have to return state for individual command in the future
        boolean result = true;
        if (answers.length > 0) {
            for (final Answer answer : answers) {
                if (!answer.getResult()) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    protected void handleSingleWorkingRedundantRouter(final List<? extends VirtualRouter> connectedRouters, final List<? extends VirtualRouter> disconnectedRouters, final String reason)
            throws ResourceUnavailableException {
        if (connectedRouters.isEmpty() || disconnectedRouters.isEmpty()) {
            return;
        }
        if (connectedRouters.size() != 1 || disconnectedRouters.size() != 1) {
            s_logger.warn("How many redundant routers do we have?? ");
            return;
        }
        if (!connectedRouters.get(0).getIsRedundantRouter()) {
            throw new ResourceUnavailableException("Who is calling this with non-redundant router or non-domain router?", DataCenter.class, connectedRouters.get(0)
                    .getDataCenterId());
        }
        if (!disconnectedRouters.get(0).getIsRedundantRouter()) {
            throw new ResourceUnavailableException("Who is calling this with non-redundant router or non-domain router?", DataCenter.class, disconnectedRouters.get(0)
                    .getDataCenterId());
        }

        final DomainRouterVO connectedRouter = (DomainRouterVO)connectedRouters.get(0);
        DomainRouterVO disconnectedRouter = (DomainRouterVO)disconnectedRouters.get(0);

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("About to stop the router " + disconnectedRouter.getInstanceName() + " due to: " + reason);
        }
        final String title = "Virtual router " + disconnectedRouter.getInstanceName() + " would be stopped after connecting back, due to " + reason;
        final String context =
                "Virtual router (name: " + disconnectedRouter.getInstanceName() + ", id: " + disconnectedRouter.getId() +
                ") would be stopped after connecting back, due to: " + reason;
        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, disconnectedRouter.getDataCenterId(), disconnectedRouter.getPodIdToDeployIn(), title,
                context);
        disconnectedRouter.setStopPending(true);
        disconnectedRouter = _routerDao.persist(disconnectedRouter);

        final int connRouterPR = getRealPriority(connectedRouter);
        final int disconnRouterPR = getRealPriority(disconnectedRouter);
        if (connRouterPR < disconnRouterPR) {
            //connRouterPR < disconnRouterPR, they won't equal at anytime
            if (!connectedRouter.getIsPriorityBumpUp()) {
                final BumpUpPriorityCommand command = new BumpUpPriorityCommand();
                command.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(connectedRouter.getId()));
                command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, connectedRouter.getInstanceName());
                final Answer answer = _agentMgr.easySend(connectedRouter.getHostId(), command);
                if (!answer.getResult()) {
                    s_logger.error("Failed to bump up " + connectedRouter.getInstanceName() + "'s priority! " + answer.getDetails());
                }
            } else {
                final String t = "Can't bump up virtual router " + connectedRouter.getInstanceName() + "'s priority due to it's already bumped up!";
                _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, connectedRouter.getDataCenterId(), connectedRouter.getPodIdToDeployIn(), t, t);
            }
        }
    }

    protected boolean sendLBRules(final VirtualRouter router, final List<LoadBalancingRule> rules, final long guestNetworkId) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        createApplyLoadBalancingRulesCommands(rules, router, cmds, guestNetworkId);
        return sendCommandsToRouter(router, cmds);
    }

    protected boolean sendPortForwardingRules(final VirtualRouter router, final List<PortForwardingRule> rules, final long guestNetworkId) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        createApplyPortForwardingRulesCommands(rules, router, cmds, guestNetworkId);
        return sendCommandsToRouter(router, cmds);
    }

    protected boolean sendStaticNatRules(final VirtualRouter router, final List<StaticNatRule> rules, final long guestNetworkId) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        createApplyStaticNatRulesCommands(rules, router, cmds, guestNetworkId);
        return sendCommandsToRouter(router, cmds);
    }

    @Override
    public List<VirtualRouter> getRoutersForNetwork(final long networkId) {
        final List<DomainRouterVO> routers = _routerDao.findByNetwork(networkId);
        final List<VirtualRouter> vrs = new ArrayList<VirtualRouter>(routers.size());
        for (final DomainRouterVO router : routers) {
            vrs.add(router);
        }
        return vrs;
    }

    private void createFirewallRulesCommands(final List<? extends FirewallRule> rules, final VirtualRouter router, final Commands cmds, final long guestNetworkId) {
        List<FirewallRuleTO> rulesTO = new ArrayList<FirewallRuleTO>();
        String systemRule = null;
        Boolean defaultEgressPolicy = false;
        if (rules != null) {
            if (rules.size() > 0) {
                if (rules.get(0).getTrafficType() == FirewallRule.TrafficType.Egress && rules.get(0).getType() == FirewallRule.FirewallRuleType.System) {
                    systemRule = String.valueOf(FirewallRule.FirewallRuleType.System);
                }
            }
            for (final FirewallRule rule : rules) {
                _rulesDao.loadSourceCidrs((FirewallRuleVO)rule);
                final FirewallRule.TrafficType traffictype = rule.getTrafficType();
                if (traffictype == FirewallRule.TrafficType.Ingress) {
                    final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
                    final FirewallRuleTO ruleTO = new FirewallRuleTO(rule, null, sourceIp.getAddress().addr(), Purpose.Firewall, traffictype);
                    rulesTO.add(ruleTO);
                } else if (rule.getTrafficType() == FirewallRule.TrafficType.Egress) {
                    final NetworkVO network = _networkDao.findById(guestNetworkId);
                    final NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());
                    defaultEgressPolicy = offering.getEgressDefaultPolicy();
                    assert (rule.getSourceIpAddressId() == null) : "ipAddressId should be null for egress firewall rule. ";
                    final FirewallRuleTO ruleTO = new FirewallRuleTO(rule, null, "", Purpose.Firewall, traffictype, defaultEgressPolicy);
                    rulesTO.add(ruleTO);
                }
            }
        }

        final SetFirewallRulesCommand cmd = new SetFirewallRulesCommand(rulesTO);
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
        if (systemRule != null) {
            cmd.setAccessDetail(NetworkElementCommand.FIREWALL_EGRESS_DEFAULT, systemRule);
        } else {
            cmd.setAccessDetail(NetworkElementCommand.FIREWALL_EGRESS_DEFAULT, String.valueOf(defaultEgressPolicy));
        }

        cmds.addCommand(cmd);
    }

    protected boolean sendFirewallRules(final VirtualRouter router, final List<FirewallRule> rules, final long guestNetworkId) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        createFirewallRulesCommands(rules, router, cmds, guestNetworkId);
        return sendCommandsToRouter(router, cmds);
    }

    @Override
    public String getDnsBasicZoneUpdate() {
        return _dnsBasicZoneUpdates;
    }

    protected interface RuleApplier {
        boolean execute(Network network, VirtualRouter router) throws ResourceUnavailableException;
    }

    protected boolean applyRules(final Network network, final List<? extends VirtualRouter> routers, final String typeString, final boolean isPodLevelException, final Long podId,
            final boolean failWhenDisconnect, final RuleApplier applier) throws ResourceUnavailableException {
        if (routers == null || routers.isEmpty()) {
            s_logger.warn("Unable to apply " + typeString + ", virtual router doesn't exist in the network " + network.getId());
            throw new ResourceUnavailableException("Unable to apply " + typeString, DataCenter.class, network.getDataCenterId());
        }

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
                    result = applier.execute(network, router);
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
                handleSingleWorkingRedundantRouter(connectedRouters, disconnectedRouters, msg);
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

    protected boolean applyStaticNat(final VirtualRouter router, final List<? extends StaticNat> rules, final long guestNetworkId) throws ResourceUnavailableException {
        final Commands cmds = new Commands(Command.OnError.Continue);
        createApplyStaticNatCommands(rules, router, cmds, guestNetworkId);
        return sendCommandsToRouter(router, cmds);
    }

    private void createApplyStaticNatCommands(final List<? extends StaticNat> rules, final VirtualRouter router, final Commands cmds, final long guestNetworkId) {
        List<StaticNatRuleTO> rulesTO = new ArrayList<StaticNatRuleTO>();
        if (rules != null) {
            for (final StaticNat rule : rules) {
                final IpAddress sourceIp = _networkModel.getIp(rule.getSourceIpAddressId());
                final StaticNatRuleTO ruleTO =
                        new StaticNatRuleTO(0, sourceIp.getAddress().addr(), null, null, rule.getDestIpAddress(), null, null, null, rule.isForRevoke(), false);
                rulesTO.add(ruleTO);
            }
        }

        final SetStaticNatRulesCommand cmd = new SetStaticNatRulesCommand(rulesTO, router.getVpcId());
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(guestNetworkId, router.getId()));
        cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        final DataCenterVO dcVo = _dcDao.findById(router.getDataCenterId());
        cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
        cmds.addCommand(cmd);
    }

    @Override
    public int getTimeout() {
        return -1;
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public boolean processAnswers(final long agentId, final long seq, final Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(final long agentId, final long seq, final Command[] commands) {
        return false;
    }

    @Override
    public void processConnect(final Host host, final StartupCommand cmd, final boolean forRebalance) throws ConnectionException {
        final List<DomainRouterVO> routers = _routerDao.listIsolatedByHostId(host.getId());
        for (DomainRouterVO router : routers) {
            if (router.isStopPending()) {
                s_logger.info("Stopping router " + router.getInstanceName() + " due to stop pending flag found!");
                final State state = router.getState();
                if (state != State.Stopped && state != State.Destroyed) {
                    try {
                        stopRouter(router.getId(), false);
                    } catch (final ResourceUnavailableException e) {
                        s_logger.warn("Fail to stop router " + router.getInstanceName(), e);
                        throw new ConnectionException(false, "Fail to stop router " + router.getInstanceName());
                    } catch (final ConcurrentOperationException e) {
                        s_logger.warn("Fail to stop router " + router.getInstanceName(), e);
                        throw new ConnectionException(false, "Fail to stop router " + router.getInstanceName());
                    }
                }
                router.setStopPending(false);
                router = _routerDao.persist(router);
            }
        }
    }

    @Override
    public AgentControlAnswer processControlCommand(final long agentId, final AgentControlCommand cmd) {
        return null;
    }

    @Override
    public boolean processDisconnect(final long agentId, final Status state) {
        return false;
    }

    @Override
    public boolean processTimeout(final long agentId, final long seq) {
        return false;
    }

    protected String getRouterControlIp(final long routerId) {
        String routerControlIpAddress = null;
        final List<NicVO> nics = _nicDao.listByVmId(routerId);
        for (final NicVO n : nics) {
            final NetworkVO nc = _networkDao.findById(n.getNetworkId());
            if (nc != null && nc.getTrafficType() == TrafficType.Control) {
                routerControlIpAddress = n.getIp4Address();
                // router will have only one control ip
                break;
            }
        }

        if (routerControlIpAddress == null) {
            s_logger.warn("Unable to find router's control ip in its attached NICs!. routerId: " + routerId);
            final DomainRouterVO router = _routerDao.findById(routerId);
            return router.getPrivateIpAddress();
        }

        return routerControlIpAddress;
    }

    protected String getRouterIpInNetwork(final long networkId, final long instanceId) {
        return _nicDao.getIpAddress(networkId, instanceId);
    }

    @Override
    public void prepareStop(final VirtualMachineProfile profile) {
        //Collect network usage before stopping Vm

        final DomainRouterVO router = _routerDao.findById(profile.getVirtualMachine().getId());
        if (router == null) {
            return;
        }

        final String privateIP = router.getPrivateIpAddress();

        if (privateIP != null) {
            final boolean forVpc = router.getVpcId() != null;
            final List<? extends Nic> routerNics = _nicDao.listByVmId(router.getId());
            for (final Nic routerNic : routerNics) {
                final Network network = _networkModel.getNetwork(routerNic.getNetworkId());
                //Send network usage command for public nic in VPC VR
                //Send network usage command for isolated guest nic of non VPC VR
                if ((forVpc && network.getTrafficType() == TrafficType.Public) ||
                        (!forVpc && network.getTrafficType() == TrafficType.Guest && network.getGuestType() == Network.GuestType.Isolated)) {
                    final NetworkUsageCommand usageCmd = new NetworkUsageCommand(privateIP, router.getHostName(), forVpc, routerNic.getIp4Address());
                    final String routerType = router.getType().toString();
                    final UserStatisticsVO previousStats =
                            _userStatsDao.findBy(router.getAccountId(), router.getDataCenterId(), network.getId(), (forVpc ? routerNic.getIp4Address() : null),
                                    router.getId(), routerType);
                    NetworkUsageAnswer answer = null;
                    try {
                        answer = (NetworkUsageAnswer)_agentMgr.easySend(router.getHostId(), usageCmd);
                    } catch (final Exception e) {
                        s_logger.warn("Error while collecting network stats from router: " + router.getInstanceName() + " from host: " + router.getHostId(), e);
                        continue;
                    }

                    if (answer != null) {
                        if (!answer.getResult()) {
                            s_logger.warn("Error while collecting network stats from router: " + router.getInstanceName() + " from host: " + router.getHostId() +
                                    "; details: " + answer.getDetails());
                            continue;
                        }
                        try {
                            if ((answer.getBytesReceived() == 0) && (answer.getBytesSent() == 0)) {
                                s_logger.debug("Recieved and Sent bytes are both 0. Not updating user_statistics");
                                continue;
                            }

                            final NetworkUsageAnswer answerFinal = answer;
                            Transaction.execute(new TransactionCallbackNoReturn() {
                                @Override
                                public void doInTransactionWithoutResult(final TransactionStatus status) {
                                    final UserStatisticsVO stats =
                                            _userStatsDao.lock(router.getAccountId(), router.getDataCenterId(), network.getId(), (forVpc ? routerNic.getIp4Address() : null),
                                                    router.getId(), routerType);
                                    if (stats == null) {
                                        s_logger.warn("unable to find stats for account: " + router.getAccountId());
                                        return;
                                    }

                                    if (previousStats != null &&
                                            ((previousStats.getCurrentBytesReceived() != stats.getCurrentBytesReceived()) || (previousStats.getCurrentBytesSent() != stats.getCurrentBytesSent()))) {
                                        s_logger.debug("Router stats changed from the time NetworkUsageCommand was sent. " + "Ignoring current answer. Router: " +
                                                answerFinal.getRouterName() + " Rcvd: " + answerFinal.getBytesReceived() + "Sent: " + answerFinal.getBytesSent());
                                        return;
                                    }

                                    if (stats.getCurrentBytesReceived() > answerFinal.getBytesReceived()) {
                                        if (s_logger.isDebugEnabled()) {
                                            s_logger.debug("Received # of bytes that's less than the last one.  " +
                                                    "Assuming something went wrong and persisting it. Router: " + answerFinal.getRouterName() + " Reported: " +
                                                    answerFinal.getBytesReceived() + " Stored: " + stats.getCurrentBytesReceived());
                                        }
                                        stats.setNetBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                    }
                                    stats.setCurrentBytesReceived(answerFinal.getBytesReceived());
                                    if (stats.getCurrentBytesSent() > answerFinal.getBytesSent()) {
                                        if (s_logger.isDebugEnabled()) {
                                            s_logger.debug("Received # of bytes that's less than the last one.  " +
                                                    "Assuming something went wrong and persisting it. Router: " + answerFinal.getRouterName() + " Reported: " +
                                                    answerFinal.getBytesSent() + " Stored: " + stats.getCurrentBytesSent());
                                        }
                                        stats.setNetBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                    }
                                    stats.setCurrentBytesSent(answerFinal.getBytesSent());
                                    if (!_dailyOrHourly) {
                                        //update agg bytes
                                        stats.setAggBytesSent(stats.getNetBytesSent() + stats.getCurrentBytesSent());
                                        stats.setAggBytesReceived(stats.getNetBytesReceived() + stats.getCurrentBytesReceived());
                                    }
                                    _userStatsDao.update(stats.getId(), stats);
                                }
                            });
                        } catch (final Exception e) {
                            s_logger.warn("Unable to update user statistics for account: " + router.getAccountId() + " Rx: " + answer.getBytesReceived() + "; Tx: " +
                                    answer.getBytesSent());
                        }
                    }
                }
            }
        }
    }

    @Override
    public VirtualRouter findRouter(final long routerId) {
        return _routerDao.findById(routerId);
    }

    @Override
    public List<Long> upgradeRouterTemplate(final UpgradeRouterTemplateCmd cmd) {

        List<DomainRouterVO> routers = new ArrayList<DomainRouterVO>();
        int params = 0;

        final Long routerId = cmd.getId();
        if (routerId != null) {
            params++;
            final DomainRouterVO router = _routerDao.findById(routerId);
            if (router != null) {
                routers.add(router);
            }
        }

        final Long domainId = cmd.getDomainId();
        if(domainId != null){
            final String accountName = cmd.getAccount();
            //List by account, if account Name is specified along with domainId
            if(accountName != null){
                final Account account = _accountMgr.getActiveAccountByName(accountName, domainId);
                if(account == null){
                    throw new InvalidParameterValueException("Account :"+accountName+" does not exist in domain: "+domainId);
                }
                routers = _routerDao.listRunningByAccountId(account.getId());
            } else {
                //List by domainId, account name not specified
                routers = _routerDao.listRunningByDomain(domainId);
            }
            params++;
        }

        final Long clusterId = cmd.getClusterId();
        if (clusterId != null) {
            params++;
            routers = _routerDao.listRunningByClusterId(clusterId);
        }

        final Long podId = cmd.getPodId();
        if (podId != null) {
            params++;
            routers = _routerDao.listRunningByPodId(podId);
        }

        final Long zoneId = cmd.getZoneId();
        if (zoneId != null) {
            params++;
            routers = _routerDao.listRunningByDataCenter(zoneId);
        }

        if (params > 1) {
            throw new InvalidParameterValueException("Multiple parameters not supported. Specify only one among routerId/zoneId/podId/clusterId/accountId/domainId");
        }

        if (routers != null) {
            return rebootRouters(routers);
        }

        return null;
    }

    private List<Long> rebootRouters(final List<DomainRouterVO> routers){
        List<Long> jobIds = new ArrayList<Long>();
        for(DomainRouterVO router: routers){
            if(!nwHelper.checkRouterVersion(router)){
                s_logger.debug("Upgrading template for router: "+router.getId());
                Map<String, String> params = new HashMap<String, String>();
                params.put("ctxUserId", "1");
                params.put("ctxAccountId", "" + router.getAccountId());

                RebootRouterCmd cmd = new RebootRouterCmd();
                ComponentContext.inject(cmd);
                params.put("id", ""+router.getId());
                params.put("ctxStartEventId", "1");
                AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, router.getAccountId(), RebootRouterCmd.class.getName(),
                        ApiGsonHelper.getBuilder().create().toJson(params), router.getId(),
                        cmd.getInstanceType() != null ? cmd.getInstanceType().toString() : null, null);
                job.setDispatcher(_asyncDispatcher.getName());
                long jobId = _asyncMgr.submitAsyncJob(job);
                jobIds.add(jobId);
            } else {
                s_logger.debug("Router: " + router.getId() + " is already at the latest version. No upgrade required");
            }
        }
        return jobIds;
    }

    @Override
    public String getConfigComponentName() {
        return VirtualNetworkApplianceManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {UseExternalDnsServers, routerVersionCheckEnabled, SetServiceMonitor, RouterAlertsCheckInterval};
    }

    @Override
    public boolean preStateTransitionEvent(final State oldState, final VirtualMachine.Event event, final State newState, final VirtualMachine vo, final boolean status, final Object opaque) {
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(final State oldState, final VirtualMachine.Event event, final State newState, final VirtualMachine vo, final boolean status, final Object opaque) {
        if (oldState == State.Stopped && event == VirtualMachine.Event.FollowAgentPowerOnReport && newState == State.Running) {
            if (vo.getType() == VirtualMachine.Type.DomainRouter) {
                s_logger.info("Schedule a router reboot task as router " + vo.getId() + " is powered-on out-of-band. we need to reboot to refresh network rules");
                _executor.schedule(new RebootTask(vo.getId()), 1000, TimeUnit.MICROSECONDS);
            }
        }
        return true;
    }

    protected class RebootTask extends ManagedContextRunnable {

        long _routerId;

        public RebootTask(final long routerId) {
            _routerId = routerId;
        }

        @Override
        protected void runInContext() {
            try {
                s_logger.info("Reboot router " + _routerId + " to refresh network rules");
                rebootRouter(_routerId, true);
            } catch (Exception e) {
                s_logger.warn("Error while rebooting the router", e);
            }
        }
    }

    protected boolean aggregationExecution(final AggregationControlCommand.Action action, final Network network, final List<DomainRouterVO> routers) throws AgentUnavailableException {
        for (DomainRouterVO router : routers) {
            AggregationControlCommand cmd = new AggregationControlCommand(action, router.getInstanceName(), getRouterControlIp(router.getId()),
                    getRouterIpInNetwork(network.getId(), router.getId()));
            Commands cmds = new Commands(cmd);
            if (!sendCommandsToRouter(router, cmds)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean prepareAggregatedExecution(final Network network, final List<DomainRouterVO> routers) throws AgentUnavailableException {
        return aggregationExecution(Action.Start, network, routers);
    }

    @Override
    public boolean completeAggregatedExecution(final Network network, final List<DomainRouterVO> routers) throws AgentUnavailableException {
        return aggregationExecution(Action.Finish, network, routers);
    }

    @Override
    public boolean cleanupAggregatedExecution(final Network network, final List<DomainRouterVO> routers) throws AgentUnavailableException {
        return aggregationExecution(Action.Cleanup, network, routers);
    }

}
