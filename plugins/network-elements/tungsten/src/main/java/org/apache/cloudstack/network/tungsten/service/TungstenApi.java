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
package org.apache.cloudstack.network.tungsten.service;

import com.cloud.utils.TungstenUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import net.juniper.tungsten.api.ApiConnector;
import net.juniper.tungsten.api.ApiObjectBase;
import net.juniper.tungsten.api.ApiPropertyBase;
import net.juniper.tungsten.api.ObjectReference;
import net.juniper.tungsten.api.Status;
import net.juniper.tungsten.api.types.AccessControlList;
import net.juniper.tungsten.api.types.AclRuleType;
import net.juniper.tungsten.api.types.ActionListType;
import net.juniper.tungsten.api.types.AddressType;
import net.juniper.tungsten.api.types.Domain;
import net.juniper.tungsten.api.types.FatFlowProtocols;
import net.juniper.tungsten.api.types.FloatingIp;
import net.juniper.tungsten.api.types.FloatingIpPool;
import net.juniper.tungsten.api.types.InstanceIp;
import net.juniper.tungsten.api.types.IpamSubnetType;
import net.juniper.tungsten.api.types.KeyValuePairs;
import net.juniper.tungsten.api.types.Loadbalancer;
import net.juniper.tungsten.api.types.LoadbalancerHealthmonitor;
import net.juniper.tungsten.api.types.LoadbalancerHealthmonitorType;
import net.juniper.tungsten.api.types.LoadbalancerListener;
import net.juniper.tungsten.api.types.LoadbalancerListenerType;
import net.juniper.tungsten.api.types.LoadbalancerMember;
import net.juniper.tungsten.api.types.LoadbalancerMemberType;
import net.juniper.tungsten.api.types.LoadbalancerPool;
import net.juniper.tungsten.api.types.LoadbalancerPoolType;
import net.juniper.tungsten.api.types.LoadbalancerType;
import net.juniper.tungsten.api.types.LogicalRouter;
import net.juniper.tungsten.api.types.MacAddressesType;
import net.juniper.tungsten.api.types.MatchConditionType;
import net.juniper.tungsten.api.types.NetworkIpam;
import net.juniper.tungsten.api.types.NetworkPolicy;
import net.juniper.tungsten.api.types.PolicyEntriesType;
import net.juniper.tungsten.api.types.PolicyRuleType;
import net.juniper.tungsten.api.types.PortMap;
import net.juniper.tungsten.api.types.PortMappings;
import net.juniper.tungsten.api.types.PortType;
import net.juniper.tungsten.api.types.Project;
import net.juniper.tungsten.api.types.SecurityGroup;
import net.juniper.tungsten.api.types.SequenceType;
import net.juniper.tungsten.api.types.SubnetType;
import net.juniper.tungsten.api.types.VirtualMachine;
import net.juniper.tungsten.api.types.VirtualMachineInterface;
import net.juniper.tungsten.api.types.VirtualNetwork;
import net.juniper.tungsten.api.types.VirtualNetworkPolicyType;
import net.juniper.tungsten.api.types.VnSubnetsType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.network.tungsten.model.TungstenLoadBalancerMember;
import org.apache.cloudstack.network.tungsten.model.TungstenRule;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TungstenApi {

    private static final Logger S_LOGGER = Logger.getLogger(TungstenApi.class);
    private Status.ErrorHandler errorHandler = new Status.ErrorHandler() {
        @Override
        public void handle(final String s) {
            S_LOGGER.error(s);
        }
    };

    public static final String TUNGSTEN_DEFAULT_DOMAIN = "default-domain";
    public static final String TUNGSTEN_DEFAULT_PROJECT = "default-project";
    public static final String TUNGSTEN_DEFAULT_IPAM = "default-network-ipam";

    private String hostname;
    private String port;
    private ApiConnector apiConnector;

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public ApiConnector getApiConnector() {
        return apiConnector;
    }

    public void setApiConnector(ApiConnector apiConnector) {
        this.apiConnector = apiConnector;
    }

    public void checkTungstenProviderConnection() {
        try {
            URL url = new URL("http://" + hostname + ":" + port);
            HttpURLConnection huc = (HttpURLConnection) url.openConnection();

            if (huc.getResponseCode() != 200) {
                throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR,
                    "There is not a tungsten provider using hostname: " + hostname + " and port: " + port);
            }
        } catch (IOException e) {
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR,
                "There is not a tungsten provider using hostname: " + hostname + " and port: " + port);
        }
    }

    public VirtualNetwork createTungstenNetwork(String uuid, String name, String displayName, String parent,
        boolean routerExternal, boolean shared, String ipPrefix, int ipPrefixLen, String gateway, boolean dhcpEnable,
        String dnsServer, String allocationStart, String allocationEnd, boolean ipFromStart,
        boolean isManagementNetwork, String subnetName) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, uuid);
            if (virtualNetwork != null)
                return virtualNetwork;
            Project project = (Project) apiConnector.findById(Project.class, parent);
            virtualNetwork = new VirtualNetwork();
            if (subnetName != null) {
                NetworkIpam networkIpam = getDefaultProjectNetworkIpam(project);
                VnSubnetsType vnSubnetsType = new VnSubnetsType();
                IpamSubnetType ipamSubnetType = getIpamSubnetType(ipPrefix, ipPrefixLen, gateway, dhcpEnable,
                    ipFromStart, allocationStart, allocationEnd, subnetName, dnsServer);
                vnSubnetsType.addIpamSubnets(ipamSubnetType);
                virtualNetwork.addNetworkIpam(networkIpam, vnSubnetsType);
            }

            if (uuid != null) {
                virtualNetwork.setUuid(uuid);
            }

            virtualNetwork.setName(name);
            virtualNetwork.setDisplayName(displayName);
            virtualNetwork.setParent(project);
            virtualNetwork.setRouterExternal(routerExternal);
            virtualNetwork.setIsShared(shared);

            if (isManagementNetwork) {
                VirtualNetwork fabricNetwork = (VirtualNetwork) apiConnector.findByFQN(VirtualNetwork.class,
                    TungstenUtils.FABRIC_NETWORK_FQN);
                if (fabricNetwork != null) {
                    virtualNetwork.setVirtualNetwork(fabricNetwork);
                }
            }

            Status status = apiConnector.create(virtualNetwork);
            status.ifFailure(errorHandler);
            return (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, virtualNetwork.getUuid());
        } catch (IOException e) {
            return null;
        }
    }

    public VirtualMachine createTungstenVirtualMachine(String vmUuid, String vmName) {
        try {
            VirtualMachine virtualMachine = new VirtualMachine();
            virtualMachine.setName(vmName);
            virtualMachine.setUuid(vmUuid);
            Status status = apiConnector.create(virtualMachine);
            status.ifFailure(errorHandler);
            return (VirtualMachine) apiConnector.findByFQN(VirtualMachine.class, getFqnName(virtualMachine));
        } catch (IOException e) {
            S_LOGGER.error("Unable to create tungsten vm " + vmUuid, e);
            return null;
        }
    }

    public VirtualMachineInterface createTungstenVmInterface(String nicUuid, String nicName, String mac,
        String virtualNetworkUuid, String virtualMachineUuid, String projectUuid) {
        VirtualNetwork virtualNetwork = null;
        VirtualMachine virtualMachine = null;
        Project project = null;
        SecurityGroup securityGroup = null;

        try {
            virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, virtualNetworkUuid);
            virtualMachine = (VirtualMachine) apiConnector.findById(VirtualMachine.class, virtualMachineUuid);
            project = (Project) apiConnector.findById(Project.class, projectUuid);
        } catch (IOException e) {
            S_LOGGER.error("Failed getting the resources needed for virtual machine interface creation from tungsten");
        }

        VirtualMachineInterface virtualMachineInterface = new VirtualMachineInterface();
        try {
            virtualMachineInterface.setUuid(nicUuid);
            virtualMachineInterface.setName(nicName);
            virtualMachineInterface.setVirtualNetwork(virtualNetwork);
            virtualMachineInterface.setVirtualMachine(virtualMachine);
            virtualMachineInterface.setParent(project);
            virtualMachineInterface.setPortSecurityEnabled(false);
            MacAddressesType macAddressesType = new MacAddressesType();
            macAddressesType.addMacAddress(mac);
            virtualMachineInterface.setMacAddresses(macAddressesType);
            Status status = apiConnector.create(virtualMachineInterface);
            status.ifFailure(errorHandler);
            return (VirtualMachineInterface) apiConnector.findById(VirtualMachineInterface.class,
                virtualMachineInterface.getUuid());
        } catch (IOException e) {
            S_LOGGER.error("Failed creating virtual machine interface in tungsten");
            return null;
        }
    }

    public InstanceIp createTungstenInstanceIp(String instanceIpName, String ip, String virtualNetworkUuid,
        String vmInterfaceUuid) {
        VirtualNetwork virtualNetwork;
        VirtualMachineInterface virtualMachineInterface;

        try {
            virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, virtualNetworkUuid);
            virtualMachineInterface = (VirtualMachineInterface) apiConnector.findById(VirtualMachineInterface.class,
                vmInterfaceUuid);
        } catch (IOException e) {
            S_LOGGER.error("Failed getting the resources needed for virtual machine interface creation from tungsten");
            return null;
        }

        try {
            InstanceIp instanceIp = new InstanceIp();
            instanceIp.setName(instanceIpName);
            instanceIp.setVirtualNetwork(virtualNetwork);
            instanceIp.setVirtualMachineInterface(virtualMachineInterface);
            instanceIp.setAddress(ip);
            Status status = apiConnector.create(instanceIp);
            status.ifFailure(errorHandler);
            return (InstanceIp) apiConnector.findById(InstanceIp.class, instanceIp.getUuid());
        } catch (IOException e) {
            S_LOGGER.error("Failed creating instance ip in tungsten");
            return null;
        }
    }

    public InstanceIp createTungstenInstanceIp(String instanceIpName, String ip, String virtualNetworkUuid,
        String vmInterfaceUuid, String subnetUuid) {
        VirtualNetwork virtualNetwork;
        VirtualMachineInterface virtualMachineInterface;

        try {
            virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, virtualNetworkUuid);
            virtualMachineInterface = (VirtualMachineInterface) apiConnector.findById(VirtualMachineInterface.class,
                vmInterfaceUuid);
        } catch (IOException e) {
            S_LOGGER.error("Failed getting the resources needed for virtual machine interface creation from tungsten");
            return null;
        }

        try {
            InstanceIp instanceIp = new InstanceIp();
            instanceIp.setName(instanceIpName);
            instanceIp.setVirtualNetwork(virtualNetwork);
            instanceIp.setVirtualMachineInterface(virtualMachineInterface);
            instanceIp.setAddress(ip);
            instanceIp.setSubnetUuid(subnetUuid);
            Status status = apiConnector.create(instanceIp);
            status.ifFailure(errorHandler);
            return (InstanceIp) apiConnector.findById(InstanceIp.class, instanceIp.getUuid());
        } catch (IOException e) {
            S_LOGGER.error("Failed creating instance ip in tungsten");
            return null;
        }
    }

    public boolean deleteTungstenNetwork(VirtualNetwork network) {
        try {
            Status status = apiConnector.delete(network);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            S_LOGGER.error("Failed deleting the network from tungsten");
            return false;
        }
    }

    public boolean deleteTungstenVmInterface(VirtualMachineInterface vmi) {
        try {
            List<ObjectReference<ApiPropertyBase>> instanceIpORs = vmi.getInstanceIpBackRefs();
            if (instanceIpORs != null) {
                for (ObjectReference<ApiPropertyBase> instanceIpOR : instanceIpORs) {
                    Status status = apiConnector.delete(InstanceIp.class, instanceIpOR.getUuid());
                    status.ifFailure(errorHandler);
                }
            }
            Status status = apiConnector.delete(vmi);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            S_LOGGER.error("Failed deleting the virtual machine interface from tungsten");
            return false;
        }
    }

    public boolean deleteTungstenVm(VirtualMachine virtualMachine) {
        try {
            if (virtualMachine != null) {
                Status status = apiConnector.delete(virtualMachine);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            S_LOGGER.error("Failed deleting the virtual machine from tungsten");
            return false;
        }
    }

    public NetworkIpam getDefaultProjectNetworkIpam(Project project) {
        try {
            List<String> names = new ArrayList<>();
            Domain domain = (Domain) apiConnector.findById(Domain.class, project.getParentUuid());
            names.add(domain.getName());
            names.add(project.getName());
            names.add(TUNGSTEN_DEFAULT_IPAM);
            String ipamUuid = apiConnector.findByName(NetworkIpam.class, names);
            if (ipamUuid == null) {
                NetworkIpam defaultIpam = new NetworkIpam();
                defaultIpam.setName(TUNGSTEN_DEFAULT_IPAM);
                defaultIpam.setParent(project);
                Status status = apiConnector.create(defaultIpam);
                status.ifFailure(errorHandler);
                ipamUuid = defaultIpam.getUuid();
            }
            return (NetworkIpam) apiConnector.findById(NetworkIpam.class, ipamUuid);
        } catch (IOException ex) {
            return null;
        }
    }

    public String getFqnName(ApiObjectBase obj) {
        StringBuilder sb = new StringBuilder();
        for (String item : obj.getQualifiedName()) {
            sb.append(item);
            sb.append(":");
        }
        sb.deleteCharAt(sb.toString().length() - 1);
        return sb.toString();
    }

    public ApiObjectBase getTungstenObject(Class<? extends ApiObjectBase> aClass, String uuid) {
        try {
            return apiConnector.findById(aClass, uuid);
        } catch (IOException ex) {
            return null;
        }
    }

    public ApiObjectBase getTungstenProjectByFqn(String fqn) {
        try {
            if (fqn == null) {
                return apiConnector.findByFQN(Project.class, TUNGSTEN_DEFAULT_DOMAIN + ":" + TUNGSTEN_DEFAULT_PROJECT);
            } else {
                return apiConnector.findByFQN(Project.class, fqn);
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public ApiObjectBase getTungstenObjectByName(Class<? extends ApiObjectBase> aClass, List<String> parent,
        String name) {
        try {
            if (parent == null) {
                List<String> names = new ArrayList<>();
                names.add(name);
                String uuid = apiConnector.findByName(aClass, names);
                return apiConnector.findById(aClass, uuid);
            } else {
                List<String> names = new ArrayList(parent);
                names.add(name);
                String uuid = apiConnector.findByName(aClass, names);
                return apiConnector.findById(aClass, uuid);
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLogicalRouter(String name, String parentUuid, String pubNetworkUuid) {
        try {
            Project project = (Project) apiConnector.findById(Project.class, parentUuid);
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class,
                pubNetworkUuid);
            LogicalRouter logicalRouter = (LogicalRouter) apiConnector.find(LogicalRouter.class, project, name);
            if (logicalRouter == null) {
                logicalRouter = new LogicalRouter();
                logicalRouter.setName(name);
                logicalRouter.setParent(project);
                logicalRouter.setVirtualNetwork(virtualNetwork, null);
                Status status = apiConnector.create(logicalRouter);
                status.ifFailure(errorHandler);
                if (status.isSuccess()) {
                    return apiConnector.findById(LogicalRouter.class, logicalRouter.getUuid());
                } else {
                    return null;
                }
            } else {
                return logicalRouter;
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public ApiObjectBase createTungstenGatewayVmi(String name, String projectUuid, String vnUuid) {
        try {
            Project project = (Project) apiConnector.findById(Project.class, projectUuid);
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, vnUuid);
            VirtualMachineInterface virtualMachineInterface = new VirtualMachineInterface();
            virtualMachineInterface.setName(name);
            virtualMachineInterface.setParent(project);
            virtualMachineInterface.setVirtualNetwork(virtualNetwork);
            virtualMachineInterface.setPortSecurityEnabled(false);
            Status status = apiConnector.create(virtualMachineInterface);
            status.ifFailure(errorHandler);
            return apiConnector.findById(VirtualMachineInterface.class, virtualMachineInterface.getUuid());
        } catch (IOException ex) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLbVmi(String name, String projectUuid, String vnUuid) {
        try {
            Project project = (Project) apiConnector.findById(Project.class, projectUuid);
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, vnUuid);
            VirtualMachineInterface virtualMachineInterface = new VirtualMachineInterface();
            virtualMachineInterface.setName(name);
            virtualMachineInterface.setParent(project);
            virtualMachineInterface.setVirtualNetwork(virtualNetwork);
            //add this when tungsten support cloudstack
            //virtualMachineInterface.setDeviceOwner("CS:LOADBALANCER");
            virtualMachineInterface.setPortSecurityEnabled(false);
            Status status = apiConnector.create(virtualMachineInterface);
            status.ifFailure(errorHandler);
            return apiConnector.findById(VirtualMachineInterface.class, virtualMachineInterface.getUuid());
        } catch (IOException ex) {
            return null;
        }
    }

    public boolean updateTungstenObject(ApiObjectBase apiObjectBase) {
        try {
            Status status = apiConnector.update(apiObjectBase);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException ex) {
            return false;
        }
    }

    public boolean deleteTungstenLogicalRouter(LogicalRouter logicalRouter) {
        try {
            Status status = apiConnector.delete(logicalRouter);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException ex) {
            return false;
        }
    }

    public ApiObjectBase createTungstenFloatingIpPool(String networkUuid, String fipName) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            FloatingIpPool floatingIpPool = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork,
                fipName);
            if (floatingIpPool == null) {
                floatingIpPool = new FloatingIpPool();
                floatingIpPool.setName(fipName);
                floatingIpPool.setParent(virtualNetwork);
                Status status = apiConnector.create(floatingIpPool);
                status.ifFailure(errorHandler);
                if (status.isSuccess()) {
                    return apiConnector.findById(FloatingIpPool.class, floatingIpPool.getUuid());
                } else {
                    return null;
                }
            } else {
                return floatingIpPool;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenFloatingIp(String projectUuid, String networkUuid, String fipName, String name,
        String publicIp) {
        try {
            Project project = (Project) apiConnector.findById(Project.class, projectUuid);
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            FloatingIpPool fip = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork, fipName);
            FloatingIp floatingIp = (FloatingIp) apiConnector.find(FloatingIp.class, fip, name);
            if (floatingIp == null) {
                floatingIp = new FloatingIp();
                floatingIp.setName(name);
                floatingIp.setParent(fip);
                floatingIp.setProject(project);
                floatingIp.setAddress(publicIp);
                Status status = apiConnector.create(floatingIp);
                status.ifFailure(errorHandler);
                if (status.isSuccess()) {
                    return apiConnector.findById(FloatingIp.class, floatingIp.getUuid());
                } else {
                    return null;
                }
            } else {
                return floatingIp;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public boolean assignTungstenFloatingIp(String networkUuid, String vmiUuid, String fipName, String name,
        String privateIp) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            FloatingIpPool fip = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork, fipName);
            VirtualMachineInterface vmi = (VirtualMachineInterface) apiConnector.findById(VirtualMachineInterface.class,
                vmiUuid);
            FloatingIp floatingIp = (FloatingIp) apiConnector.find(FloatingIp.class, fip, name);
            floatingIp.setVirtualMachineInterface(vmi);
            floatingIp.setFixedIpAddress(privateIp);
            Status status = apiConnector.update(floatingIp);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean releaseTungstenFloatingIp(String networkUuid, String fipName, String name) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            FloatingIpPool fip = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork, fipName);
            FloatingIp floatingIp = (FloatingIp) apiConnector.find(FloatingIp.class, fip, name);
            floatingIp.clearVirtualMachineInterface();
            floatingIp.setFixedIpAddress(null);
            Status status = apiConnector.update(floatingIp);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public String getTungstenNatIp(String projectUuid, String logicalRouterUuid) {
        try {
            Project project = (Project) apiConnector.findById(Project.class, projectUuid);
            List<InstanceIp> instanceIps = (List<InstanceIp>) apiConnector.list(InstanceIp.class, null);
            if (instanceIps != null) {
                for (InstanceIp instanceIp : instanceIps) {
                    if (instanceIp.getQualifiedName()
                        .get(0)
                        .startsWith(
                            TungstenUtils.getSnatNetworkStartName(project.getQualifiedName(), logicalRouterUuid))
                        && instanceIp.getQualifiedName().get(0).endsWith(TungstenUtils.getSnatNetworkEndName())) {
                        InstanceIp natInstanceIp = (InstanceIp) apiConnector.findById(InstanceIp.class,
                            instanceIp.getUuid());
                        if (natInstanceIp != null) {
                            return natInstanceIp.getAddress();
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean deleteTungstenFloatingIpPool(FloatingIpPool floatingIpPool) {
        try {
            if (floatingIpPool != null) {
                Status status = apiConnector.delete(floatingIpPool);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenFloatingIp(FloatingIp floatingIp) {
        try {
            if (floatingIp != null) {
                Status status = apiConnector.delete(floatingIp);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenNetworkPolicy(NetworkPolicy networkPolicy) {
        try {
            if (networkPolicy != null) {
                Status status = apiConnector.delete(networkPolicy);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenLoadBalancer(Loadbalancer loadbalancer) {
        try {
            if (loadbalancer != null) {
                Status status = apiConnector.delete(loadbalancer);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenLoadBalancerHealthMonitor(LoadbalancerHealthmonitor loadbalancerHealthmonitor) {
        try {
            if (loadbalancerHealthmonitor != null) {
                Status status = apiConnector.delete(loadbalancerHealthmonitor);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenLoadBalancerMember(LoadbalancerMember loadbalancerMember) {
        try {
            if (loadbalancerMember != null) {
                Status status = apiConnector.delete(loadbalancerMember);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenLoadBalancerPool(LoadbalancerPool loadbalancerPool) {
        try {
            if (loadbalancerPool != null) {
                Status status = apiConnector.delete(loadbalancerPool);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenLoadBalancerListener(LoadbalancerListener loadbalancerListener) {
        try {
            if (loadbalancerListener != null) {
                Status status = apiConnector.delete(loadbalancerListener);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public ApiObjectBase createTungstenNetworkPolicy(String name, String projectUuid,
        List<TungstenRule> tungstenRuleList) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            NetworkPolicy networkPolicy = (NetworkPolicy) apiConnector.find(NetworkPolicy.class, project, name);
            if (networkPolicy == null) {
                PolicyEntriesType policyEntriesType = new PolicyEntriesType();
                for (TungstenRule tungstenRule : tungstenRuleList) {
                    PolicyRuleType policyRuleType = getPolicyRuleType(tungstenRule);
                    AddressType srcAddressType = new AddressType();
                    srcAddressType.addSubnet(
                        new SubnetType(tungstenRule.getSrcIpPrefix(), tungstenRule.getSrcIpPrefixLen()));
                    AddressType dstAddressType = new AddressType();
                    dstAddressType.addSubnet(
                        new SubnetType(tungstenRule.getDstIpPrefix(), tungstenRule.getDstIpPrefixLen()));
                    policyRuleType.addSrcAddresses(srcAddressType);
                    policyRuleType.addDstAddresses(dstAddressType);
                    policyRuleType.addSrcPorts(tungstenRule.getSrcStartPort(), tungstenRule.getSrcEndPort());
                    policyRuleType.addDstPorts(tungstenRule.getDstStartPort(), tungstenRule.getDstEndPort());
                    policyEntriesType.addPolicyRule(policyRuleType);
                }

                networkPolicy = new NetworkPolicy();
                networkPolicy.setName(name);
                networkPolicy.setParent(project);
                networkPolicy.setEntries(policyEntriesType);

                Status status = apiConnector.create(networkPolicy);
                status.ifFailure(errorHandler);
                if (status.isSuccess()) {
                    return apiConnector.findById(NetworkPolicy.class, networkPolicy.getUuid());
                } else {
                    return null;
                }
            }

            return networkPolicy;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean applyTungstenNetworkPolicy(String projectUuid, String networkPolicyName, String networkUuid,
        boolean priority) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            NetworkPolicy networkPolicy = (NetworkPolicy) apiConnector.find(NetworkPolicy.class, project,
                networkPolicyName);
            VirtualNetwork network = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);

            List<ObjectReference<VirtualNetworkPolicyType>> objectReferenceList = network.getNetworkPolicy();
            if (objectReferenceList != null) {
                for (ObjectReference<VirtualNetworkPolicyType> objectReference : objectReferenceList) {
                    if (objectReference.getUuid().equals(networkPolicy.getUuid())) {
                        return true;
                    }
                }
            }

            if (networkPolicy == null || network == null) {
                return false;
            }

            network.addNetworkPolicy(networkPolicy,
                new VirtualNetworkPolicyType(new SequenceType(priority ? 0 : 1, 0)));
            network.setPerms2(null);
            Status status = apiConnector.update(network);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public ApiObjectBase getTungstenFabricNetwork() {
        try {
            return apiConnector.findByFQN(VirtualNetwork.class, TungstenUtils.FABRIC_NETWORK_FQN);
        } catch (IOException e) {
            return null;
        }
    }



    public ApiObjectBase createTungstenDomain(String domainName, String domainUuid) {
        try {

            Domain domain = (Domain) apiConnector.findById(Domain.class, domainUuid);
            if (domain != null)
                return domain;
            //create tungsten domain
            Domain tungstenDomain = new Domain();
            tungstenDomain.setDisplayName(domainName);
            tungstenDomain.setName(domainName);
            tungstenDomain.setUuid(domainUuid);
            Status status = apiConnector.create(tungstenDomain);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                // create default project in tungsten for this newly created domain
                Project tungstenDefaultProject = new Project();
                tungstenDefaultProject.setDisplayName(TUNGSTEN_DEFAULT_PROJECT);
                tungstenDefaultProject.setName(TUNGSTEN_DEFAULT_PROJECT);
                tungstenDefaultProject.setParent(tungstenDomain);
                apiConnector.create(tungstenDefaultProject);
                Status defaultProjectStatus = apiConnector.create(tungstenDefaultProject);
                defaultProjectStatus.ifFailure(errorHandler);
            }
            return getTungstenObject(Domain.class, tungstenDomain.getUuid());
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed creating domain resource in tungsten.");
        }
    }

    public ApiObjectBase createTungstenProject(String projectName, String projectUuid, String domainUuid,
        String domainName) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            if (project != null)
                return project;
            //Create tungsten project
            Project tungstenProject = new Project();
            tungstenProject.setDisplayName(projectName);
            tungstenProject.setName(projectName);
            tungstenProject.setUuid(projectUuid);
            Domain tungstenDomain;

            if (domainUuid == null && domainName == null)
                tungstenDomain = getDefaultTungstenDomain();
            else {
                tungstenDomain = (Domain) getTungstenObject(Domain.class, domainUuid);
                if (tungstenDomain == null)
                    tungstenDomain = (Domain) createTungstenDomain(domainName, domainUuid);
            }
            tungstenProject.setParent(tungstenDomain);
            apiConnector.create(tungstenProject);
            return getTungstenObject(Project.class, tungstenProject.getUuid());
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed creating project resource in tungsten.");
        }
    }

    public boolean deleteTungstenDomain(String domainUuid) {
        try {
            Domain domain = (Domain) getTungstenObject(Domain.class, domainUuid);
            //delete the projects of this domain
            for (ObjectReference<ApiPropertyBase> project : domain.getProjects()) {
                apiConnector.delete(Project.class, project.getUuid());
            }
            Status status = apiConnector.delete(Domain.class, domainUuid);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenProject(String projectUuid) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            if (project != null) {
                Status status = apiConnector.delete(Project.class, projectUuid);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Domain getDefaultTungstenDomain() throws IOException {
        Domain domain = (Domain) apiConnector.findByFQN(Domain.class, TUNGSTEN_DEFAULT_DOMAIN);
        return domain;
    }

    public ApiObjectBase createTungstenLoadbalancer(String projectUuid, String lbName, String vmiUuid,
        String subnetUuid, String privateIp) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            VirtualMachineInterface virtualMachineInterface = (VirtualMachineInterface) apiConnector.findById(
                VirtualMachineInterface.class, vmiUuid);
            LoadbalancerType loadbalancerType = new LoadbalancerType();
            loadbalancerType.setVipSubnetId(subnetUuid);
            loadbalancerType.setVipAddress(privateIp);
            loadbalancerType.setAdminState(true);
            loadbalancerType.setOperatingStatus("ONLINE");
            loadbalancerType.setProvisioningStatus("ACTIVE");

            Loadbalancer loadbalancer = new Loadbalancer();
            loadbalancer.setName(lbName);
            loadbalancer.setParent(project);
            loadbalancer.setProperties(loadbalancerType);
            loadbalancer.setProvider("opencontrail");
            loadbalancer.setVirtualMachineInterface(virtualMachineInterface);
            Status status = apiConnector.create(loadbalancer);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(Loadbalancer.class, loadbalancer.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLoadbalancerListener(String projectUuid, String loadBalancerUuid, String name,
        String protocol, int port) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            Loadbalancer loadbalancer = (Loadbalancer) apiConnector.findById(Loadbalancer.class, loadBalancerUuid);
            LoadbalancerListenerType loadbalancerListenerType = new LoadbalancerListenerType();
            loadbalancerListenerType.setConnectionLimit(-1);
            loadbalancerListenerType.setAdminState(true);
            loadbalancerListenerType.setProtocol(protocol);
            loadbalancerListenerType.setProtocolPort(port);

            LoadbalancerListener loadbalancerListener = new LoadbalancerListener();
            loadbalancerListener.setName(name);
            loadbalancerListener.setParent(project);
            loadbalancerListener.setLoadbalancer(loadbalancer);
            loadbalancerListener.setProperties(loadbalancerListenerType);
            Status status = apiConnector.create(loadbalancerListener);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(LoadbalancerListener.class, loadbalancerListener.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLoadbalancerHealthMonitor(String projectUuid, String name, String monitorType,
        int maxRetries, int delay, int timeout, String httpMethod, String urlPath, String expectedCode) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            LoadbalancerHealthmonitorType loadbalancerHealthmonitorType = new LoadbalancerHealthmonitorType();
            loadbalancerHealthmonitorType.setMonitorType(monitorType);
            loadbalancerHealthmonitorType.setMaxRetries(maxRetries);
            loadbalancerHealthmonitorType.setDelay(delay);
            loadbalancerHealthmonitorType.setAdminState(true);
            loadbalancerHealthmonitorType.setTimeout(timeout);
            if (monitorType == "HTTP") {
                loadbalancerHealthmonitorType.setHttpMethod(httpMethod);
                loadbalancerHealthmonitorType.setUrlPath(urlPath);
                loadbalancerHealthmonitorType.setExpectedCodes(expectedCode);
            }

            LoadbalancerHealthmonitor loadbalancerHealthmonitor = new LoadbalancerHealthmonitor();
            loadbalancerHealthmonitor.setName(name);
            loadbalancerHealthmonitor.setParent(project);
            loadbalancerHealthmonitor.setProperties(loadbalancerHealthmonitorType);
            Status status = apiConnector.create(loadbalancerHealthmonitor);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(LoadbalancerHealthmonitor.class, loadbalancerHealthmonitor.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLoadbalancerPool(String projectUuid, String loadbalancerlistenerUuid,
        String loadbalancerHealthmonitorUuid, String name, String method, String protocol) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            LoadbalancerListener loadbalancerListener = (LoadbalancerListener) apiConnector.findById(
                LoadbalancerListener.class, loadbalancerlistenerUuid);
            LoadbalancerHealthmonitor loadbalancerHealthmonitor = (LoadbalancerHealthmonitor) apiConnector.findById(
                LoadbalancerHealthmonitor.class, loadbalancerHealthmonitorUuid);
            LoadbalancerPoolType loadbalancerPoolType = new LoadbalancerPoolType();
            loadbalancerPoolType.setLoadbalancerMethod(method);
            loadbalancerPoolType.setProtocol(protocol);
            loadbalancerPoolType.setAdminState(true);

            LoadbalancerPool loadbalancerPool = new LoadbalancerPool();
            loadbalancerPool.setName(name);
            loadbalancerPool.setParent(project);
            loadbalancerPool.setLoadbalancerListener(loadbalancerListener);
            loadbalancerPool.setLoadbalancerHealthmonitor(loadbalancerHealthmonitor);
            loadbalancerPool.setProperties(loadbalancerPoolType);
            Status status = apiConnector.create(loadbalancerPool);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(LoadbalancerPool.class, loadbalancerPool.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenLoadbalancerMember(String loadbalancerPoolUuid, String name, String address,
        String subnetUuid, int port, int weight) {
        try {
            LoadbalancerPool loadbalancerPool = (LoadbalancerPool) apiConnector.findById(LoadbalancerPool.class,
                loadbalancerPoolUuid);
            LoadbalancerMemberType loadbalancerMemberType = new LoadbalancerMemberType();
            loadbalancerMemberType.setAddress(address);
            loadbalancerMemberType.setAdminState(true);
            loadbalancerMemberType.setProtocolPort(port);
            loadbalancerMemberType.setSubnetId(subnetUuid);
            loadbalancerMemberType.setWeight(weight);

            LoadbalancerMember loadbalancerMember = new LoadbalancerMember();
            loadbalancerMember.setName(name);
            loadbalancerMember.setParent(loadbalancerPool);
            loadbalancerMember.setProperties(loadbalancerMemberType);
            Status status = apiConnector.create(loadbalancerMember);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(LoadbalancerMember.class, loadbalancerMember.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public boolean removeTungstenNetworkPolicy(String projectUuid, String networkUuid, String networkPolicyName) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            NetworkPolicy networkPolicy = (NetworkPolicy) apiConnector.find(NetworkPolicy.class, project,
                networkPolicyName);
            virtualNetwork.removeNetworkPolicy(networkPolicy,
                new VirtualNetworkPolicyType(new SequenceType(0, 0), null));
            Status status = apiConnector.update(virtualNetwork);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean updateLoadBalancerMember(String projectUuid, String lbPoolName,
        List<TungstenLoadBalancerMember> listTungstenLoadBalancerMember, String subnetUuid) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            LoadbalancerPool loadbalancerPool = (LoadbalancerPool) apiConnector.find(LoadbalancerPool.class, project,
                lbPoolName);
            List<ObjectReference<ApiPropertyBase>> listMember = loadbalancerPool.getLoadbalancerMembers();

            if (listMember != null) {
                for (ObjectReference<ApiPropertyBase> member : listMember) {
                    Status status = apiConnector.delete(LoadbalancerMember.class, member.getUuid());
                    status.ifFailure(errorHandler);
                    if (!status.isSuccess()) {
                        return false;
                    }
                }
            }

            for (TungstenLoadBalancerMember tungstenLoadBalancerMember : listTungstenLoadBalancerMember) {
                LoadbalancerMemberType loadbalancerMemberType = new LoadbalancerMemberType();
                loadbalancerMemberType.setAddress(tungstenLoadBalancerMember.getIpAddress());
                loadbalancerMemberType.setProtocolPort(tungstenLoadBalancerMember.getPort());
                loadbalancerMemberType.setSubnetId(subnetUuid);
                loadbalancerMemberType.setAdminState(true);
                loadbalancerMemberType.setWeight(tungstenLoadBalancerMember.getWeight());
                LoadbalancerMember loadbalancerMember = new LoadbalancerMember();
                loadbalancerMember.setName(tungstenLoadBalancerMember.getName());
                loadbalancerMember.setParent(loadbalancerPool);
                loadbalancerMember.setProperties(loadbalancerMemberType);
                Status status = apiConnector.create(loadbalancerMember);
                status.ifFailure(errorHandler);
                if (!status.isSuccess()) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean updateLoadBalancerPool(String projectUuid, String lbPoolName, String lbMethod,
        String lbSessionPersistence, String lbPersistenceCookieName, String lbProtocol, boolean statEnable,
        String statsPort, String statsUri, String statsAuth) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            LoadbalancerPool loadbalancerPool = (LoadbalancerPool) apiConnector.find(LoadbalancerPool.class, project,
                lbPoolName);
            LoadbalancerPoolType loadbalancerPoolType = loadbalancerPool.getProperties();
            if (lbMethod != null) {
                loadbalancerPoolType.setLoadbalancerMethod(lbMethod);
            }
            if (lbSessionPersistence != null) {
                loadbalancerPoolType.setSessionPersistence(lbSessionPersistence);
            }
            if (lbPersistenceCookieName != null) {
                loadbalancerPoolType.setPersistenceCookieName(lbPersistenceCookieName);
            }
            if (lbProtocol != null) {
                loadbalancerPoolType.setProtocol(lbProtocol);
            }

            if (statEnable) {
                KeyValuePairs keyValuePairs = new KeyValuePairs();
                keyValuePairs.addKeyValuePair("stats_enable", "enable");
                keyValuePairs.addKeyValuePair("stats_port", statsPort);
                keyValuePairs.addKeyValuePair("stats_realm", "Haproxy Statistics");
                keyValuePairs.addKeyValuePair("stats_uri", statsUri);
                keyValuePairs.addKeyValuePair("stats_auth", statsAuth);
                loadbalancerPool.setCustomAttributes(keyValuePairs);
            }

            Status status = apiConnector.update(loadbalancerPool);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean updateLoadBalancerListener(String projectUuid, String listenerName, String protocol, int port,
        String url) {
        try {
            Project project = (Project) getTungstenObject(Project.class, projectUuid);
            LoadbalancerListener loadbalancerListener = (LoadbalancerListener) apiConnector.find(
                LoadbalancerListener.class, project, listenerName);
            LoadbalancerListenerType loadbalancerListenerType = loadbalancerListener.getProperties();
            loadbalancerListenerType.setProtocolPort(port);
            loadbalancerListenerType.setProtocol(protocol);
            loadbalancerListenerType.setDefaultTlsContainer(url);
            Status status = apiConnector.update(loadbalancerListener);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean updateLBServiceInstanceFatFlow(String publicNetworkUuid, String floatingIpPoolName,
        String floatingIpName) {
        boolean result = true;
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class,
                publicNetworkUuid);
            FloatingIpPool floatingIpPool = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork,
                floatingIpPoolName);
            FloatingIp floatingIp = (FloatingIp) apiConnector.find(FloatingIp.class, floatingIpPool, floatingIpName);
            List<ObjectReference<ApiPropertyBase>> listRefVmi = floatingIp.getVirtualMachineInterface();
            for (ObjectReference<ApiPropertyBase> refVmi : listRefVmi) {
                if (refVmi.getReferredName().get(refVmi.getReferredName().size() - 1).contains("right__1")) {
                    String siUuid = refVmi.getUuid();
                    VirtualMachineInterface vmi = (VirtualMachineInterface) apiConnector.findById(
                        VirtualMachineInterface.class, siUuid);
                    FatFlowProtocols fatFlowProtocols = vmi.getFatFlowProtocols();
                    if (fatFlowProtocols != null) {
                        fatFlowProtocols.clearFatFlowProtocol();
                        Status status = apiConnector.update(vmi);
                        status.ifFailure(errorHandler);
                        result = result && status.isSuccess();
                    }
                }
            }

            return result;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean applyTungstenPortForwarding(boolean isAdd, String publicNetworkUuid, String floatingIpPoolName,
        String floatingIpName, String vmiUuid, String protocol, int publicPort, int privatePort) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class,
                publicNetworkUuid);
            VirtualMachineInterface virtualMachineInterface = (VirtualMachineInterface) apiConnector.findById(
                VirtualMachineInterface.class, vmiUuid);
            FloatingIpPool floatingIpPool = (FloatingIpPool) apiConnector.find(FloatingIpPool.class, virtualNetwork,
                floatingIpPoolName);
            FloatingIp floatingIp = (FloatingIp) apiConnector.find(FloatingIp.class, floatingIpPool, floatingIpName);
            PortMappings portMappings = floatingIp.getPortMappings();
            if (isAdd) {
                if (portMappings == null) {
                    portMappings = new PortMappings();
                }

                portMappings.addPortMappings(protocol, publicPort, privatePort);
                floatingIp.setPortMappings(portMappings);
                floatingIp.addVirtualMachineInterface(virtualMachineInterface);
                floatingIp.setPortMappingsEnable(true);
            } else {
                if (portMappings != null) {
                    List<PortMap> portMapList = portMappings.getPortMappings();
                    List<PortMap> removePortMapList = new ArrayList<>();
                    for (PortMap portMap : portMapList) {
                        if (portMap.getProtocol().equals(protocol) && portMap.getSrcPort() == publicPort
                            && portMap.getDstPort() == privatePort) {
                            removePortMapList.add(portMap);
                        }
                    }
                    portMapList.removeAll(removePortMapList);
                }

                floatingIp.removeVirtualMachineInterface(virtualMachineInterface);

                if (floatingIp.getVirtualMachineInterface() == null
                    || floatingIp.getVirtualMachineInterface().size() == 0) {
                    floatingIp.setPortMappingsEnable(false);
                }
            }
            Status status = apiConnector.update(floatingIp);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean addTungstenNetworkSubnetCommand(String networkUuid, String ipPrefix, int ipPrefixLen, String gateway,
        boolean dhcpEnable, String dnsServer, String allocationStart, String allocationEnd, boolean ipFromStart,
        String subnetName) {
        try {
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            if (virtualNetwork == null) {
                return false;
            }
            Project project = (Project) apiConnector.findById(Project.class, virtualNetwork.getParentUuid());
            NetworkIpam networkIpam = getDefaultProjectNetworkIpam(project);

            if (networkIpam == null) {
                return false;
            }

            IpamSubnetType ipamSubnetType = getIpamSubnetType(ipPrefix, ipPrefixLen, gateway, dhcpEnable, ipFromStart,
                allocationStart, allocationEnd, subnetName, dnsServer);
            List<ObjectReference<VnSubnetsType>> objectReferenceList = virtualNetwork.getNetworkIpam();

            if (objectReferenceList != null && objectReferenceList.size() == 1) {
                VnSubnetsType vnSubnetsType = objectReferenceList.get(0).getAttr();
                vnSubnetsType.addIpamSubnets(ipamSubnetType);
            } else {
                VnSubnetsType vnSubnetsType = new VnSubnetsType();
                vnSubnetsType.addIpamSubnets(ipamSubnetType);
                virtualNetwork.addNetworkIpam(networkIpam, vnSubnetsType);
            }

            virtualNetwork.setPerms2(null);
            Status status = apiConnector.update(virtualNetwork);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean removeTungstenNetworkSubnetCommand(String networkUuid, String subnetName) {
        try {
            boolean clear = false;
            VirtualNetwork virtualNetwork = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            if (virtualNetwork == null) {
                return false;
            }

            List<ObjectReference<VnSubnetsType>> objectReferenceList = virtualNetwork.getNetworkIpam();
            if (objectReferenceList == null) {
                return true;
            }

            for (ObjectReference<VnSubnetsType> vnSubnetsTypeObjectReference : objectReferenceList) {
                VnSubnetsType vnSubnetsType = vnSubnetsTypeObjectReference.getAttr();
                List<IpamSubnetType> ipamSubnetTypeList = vnSubnetsType.getIpamSubnets();

                List<IpamSubnetType> removeIpamSubnetTypelist = new ArrayList<>();
                for (IpamSubnetType ipamSubnetType : ipamSubnetTypeList) {
                    if (ipamSubnetType.getSubnetName().equals(subnetName)) {
                        removeIpamSubnetTypelist.add(ipamSubnetType);
                    }
                }

                if (ipamSubnetTypeList.size() != removeIpamSubnetTypelist.size()) {
                    ipamSubnetTypeList.removeAll(removeIpamSubnetTypelist);
                } else {
                    clear = true;
                }
            }

            if (clear) {
                virtualNetwork.clearNetworkIpam();
            }

            virtualNetwork.setPerms2(null);

            Status status = apiConnector.update(virtualNetwork);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public boolean deleteTungstenObject(ApiObjectBase apiObjectBase) {
        try {
            Status status = apiConnector.delete(apiObjectBase);
            status.ifFailure(errorHandler);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public List<? extends ApiObjectBase> getTungstenListObject(Class<? extends ApiObjectBase> cls,
        ApiObjectBase parent) {
        try {
            List resultList = new ArrayList();
            List<? extends ApiObjectBase> list = apiConnector.list(cls, parent.getQualifiedName());
            if (list != null) {
                for (ApiObjectBase object : list) {
                    resultList.add(apiConnector.findById(object.getClass(), object.getUuid()));
                }
            }
            return resultList;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private IpamSubnetType getIpamSubnetType(String ipPrefix, int ipPrefixLen, String gateway, boolean dhcpEnable,
        boolean ipFromStart, String allocationStart, String allocationEnd, String subnetName, String dnsServer) {
        IpamSubnetType ipamSubnetType = new IpamSubnetType();
        ipamSubnetType.setSubnetName(subnetName);
        ipamSubnetType.setSubnet(new SubnetType(ipPrefix, ipPrefixLen));

        if (gateway != null) {
            ipamSubnetType.setDefaultGateway(gateway);
        }

        ipamSubnetType.setEnableDhcp(dhcpEnable);
        ipamSubnetType.setAddrFromStart(ipFromStart);
        ipamSubnetType.setDnsServerAddress(dnsServer);

        if (allocationStart != null && allocationEnd != null) {
            ipamSubnetType.addAllocationPools(allocationStart, allocationEnd, false);
        }

        return ipamSubnetType;
    }

    private PolicyRuleType getPolicyRuleType(TungstenRule tungstenRule) {
        PolicyRuleType policyRuleType = new PolicyRuleType();
        if (tungstenRule.getRuleId() != null) {
            policyRuleType.setRuleUuid(tungstenRule.getRuleId());
        }
        policyRuleType.setActionList(new ActionListType(tungstenRule.getAction()));
        policyRuleType.setDirection(tungstenRule.getDirection());
        policyRuleType.setProtocol(tungstenRule.getProtocol());
        return policyRuleType;
    }

    public String getSubnetUuid(String networkUuid) {
        try {
            VirtualNetwork network = (VirtualNetwork) apiConnector.findById(VirtualNetwork.class, networkUuid);
            if (network != null) {
                List<ObjectReference<VnSubnetsType>> listIpam = network.getNetworkIpam();
                if (listIpam != null) {
                    for (ObjectReference<VnSubnetsType> objectReference : listIpam) {
                        VnSubnetsType vnSubnetsType = objectReference.getAttr();
                        List<IpamSubnetType> ipamSubnetTypeList = vnSubnetsType.getIpamSubnets();
                        for (IpamSubnetType ipamSubnetType : ipamSubnetTypeList) {
                            return ipamSubnetType.getSubnetUuid();
                        }
                    }
                }
                return null;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    public ApiObjectBase createTungstenSecurityGroup(String securityGroupUuid, String securityGroupName,
                                                     String securityGroupDescription, String projectFqn) {
        try {
            SecurityGroup tungstenSecurityGroup = (SecurityGroup) apiConnector.findById(SecurityGroup.class, securityGroupUuid);
            if(tungstenSecurityGroup != null) {
                return tungstenSecurityGroup;
            }
            Project project = (Project) getTungstenProjectByFqn(projectFqn);
            tungstenSecurityGroup = new SecurityGroup();
            tungstenSecurityGroup.setUuid(securityGroupUuid);
            tungstenSecurityGroup.setName(securityGroupName);
            tungstenSecurityGroup.setDisplayName(securityGroupDescription);
            tungstenSecurityGroup.setParent(project);
            Status status = apiConnector.create(tungstenSecurityGroup);
            status.ifFailure(errorHandler);
            if (status.isSuccess()) {
                return apiConnector.findById(SecurityGroup.class, tungstenSecurityGroup.getUuid());
            } else {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public boolean deleteTungstenSecurityGroup(String tungstenSecurityGroupUuid) {
        try {
            SecurityGroup securityGroup = (SecurityGroup) getTungstenObject(SecurityGroup.class, tungstenSecurityGroupUuid);
            if (securityGroup != null) {
                Status status = apiConnector.delete(SecurityGroup.class, tungstenSecurityGroupUuid);
                status.ifFailure(errorHandler);
                return status.isSuccess();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean addTungstenSecurityGroupRule(String tungstenSecurityGroupUuid, String securityGroupRuleUuid,
                                                String securityGroupRuleType, int startPort, int endPort,
                                                String cidr, String ipPrefix, int ipPrefixLen, String protocol) {
        try {
            SecurityGroup securityGroup = (SecurityGroup) getTungstenObject(SecurityGroup.class, tungstenSecurityGroupUuid);
            if (securityGroup == null) {
                return false;
            }
            for (ObjectReference<ApiPropertyBase> accessControl : securityGroup.getAccessControlLists()) {
                AccessControlList accessControlList = (AccessControlList) getTungstenObject(AccessControlList.class,
                        accessControl.getUuid());
                if (accessControlList.getName().equals(TungstenUtils.getTungstenAccessControl(securityGroupRuleType))) {
                    AclRuleType aclRuleType = createAclRuleType(securityGroupRuleUuid, securityGroupRuleType,
                            startPort, endPort, cidr, ipPrefix, ipPrefixLen, protocol);
                    PolicyRuleType policyRuleType = createPolicyRuleType(aclRuleType.getMatchCondition(), securityGroupRuleUuid);
                    accessControlList.getEntries().addAclRule(aclRuleType);
                    if (securityGroup.getEntries() == null) {
                        securityGroup.setEntries(new PolicyEntriesType(new ArrayList<>(Arrays.asList(policyRuleType))));
                    } else {
                        securityGroup.getEntries().addPolicyRule(policyRuleType);
                    }
                    Status accessControlListStatus = apiConnector.update(accessControlList);
                    if (!accessControlListStatus.isSuccess()) {
                        return false;
                    }
                    Status securityGroupStatus = apiConnector.update(securityGroup);
                    return securityGroupStatus.isSuccess();
                }
            }

        } catch (IOException e) {
            return false;
        }
        return false;
    }

    public boolean removeTungstenSecurityGroupRule(String tungstenSecurityGroupUuid, String securityGroupRuleUuid,
                                                   String securityGroupRuleType) {
        try {
            SecurityGroup securityGroup = (SecurityGroup) getTungstenObject(SecurityGroup.class, tungstenSecurityGroupUuid);
            if (securityGroup == null) {
                return false;
            }
            for (ObjectReference<ApiPropertyBase> accessControl : securityGroup.getAccessControlLists()) {
                AccessControlList accessControlList = (AccessControlList) getTungstenObject(AccessControlList.class,
                        accessControl.getUuid());
                if (accessControlList.getName().equals(TungstenUtils.getTungstenAccessControl(securityGroupRuleType))) {
                    List<AclRuleType> existingAclList = accessControlList.getEntries().getAclRule();
                    accessControlList.getEntries().clearAclRule();
                    for (AclRuleType aclRuleType : existingAclList) {
                        if (!aclRuleType.getRuleUuid().equals(securityGroupRuleUuid)) {
                            accessControlList.getEntries().addAclRule(aclRuleType);
                        }
                    }
                    Status status = apiConnector.update(accessControlList);
                    return status.isSuccess();
                }
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public boolean removeTungstenPolicyRule(String tungstenSecurityGroupUuid, String securityGroupRuleUuid) {
        try {
            SecurityGroup securityGroup = (SecurityGroup) getTungstenObject(SecurityGroup.class, tungstenSecurityGroupUuid);
            if (securityGroup == null) {
                return false;
            }
            List<PolicyRuleType> existingPolicyRules = securityGroup.getEntries().getPolicyRule();
            securityGroup.getEntries().clearPolicyRule();
            for (PolicyRuleType policyRule : existingPolicyRules) {
                if (!policyRule.getRuleUuid().equals(securityGroupRuleUuid)) {
                    securityGroup.getEntries().addPolicyRule(policyRule);
                }
            }
            Status status = apiConnector.update(securityGroup);
            return status.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    public AclRuleType createAclRuleType(String securityGroupRuleUuid, String securityGroupRuleType,
                                         int startPort, int endPort, String cidr, String ipPrefix,
                                         int ipPrefixLen, String protocol) {
        String tungstenProtocol = TungstenUtils.getTungstenProtocol(protocol, cidr);
        String tungstenEthertType = TungstenUtils.getEthertTypeFromCidr(cidr);
        AddressType addressType = new AddressType(new SubnetType(ipPrefix, ipPrefixLen), null,
                null, null);

        MatchConditionType matchConditionType = new MatchConditionType();
        matchConditionType.setSrcPort(new PortType(0, 65535));
        matchConditionType.setDstPort(new PortType(startPort, endPort));
        if (securityGroupRuleType.equals(TungstenUtils.INGRESS_RULE)) {
            matchConditionType.setSrcAddress(addressType);
            matchConditionType.setDstAddress(new AddressType(null, null, "local", null));
        } else if (securityGroupRuleType.equals(TungstenUtils.EGRESS_RULE)) {
            matchConditionType.setDstAddress(addressType);
            matchConditionType.setSrcAddress(new AddressType(null, null, "local", null));
        }
        matchConditionType.setProtocol(tungstenProtocol);
        matchConditionType.setEthertype(tungstenEthertType);
        AclRuleType aclRuleType = new AclRuleType(matchConditionType);
        aclRuleType.setDirection(TungstenUtils.ONE_WAY_DIRECTION);
        aclRuleType.setRuleUuid(securityGroupRuleUuid);
        return aclRuleType;
    }

    public PolicyRuleType createPolicyRuleType(MatchConditionType matchConditionType, String securityGroupRuleUuid) {
        PolicyRuleType policyRuleType = new PolicyRuleType();
        policyRuleType.setDirection(TungstenUtils.ONE_WAY_DIRECTION);
        policyRuleType.setProtocol(matchConditionType.getProtocol());
        policyRuleType.setEthertype(matchConditionType.getEthertype());
        policyRuleType.setRuleUuid(securityGroupRuleUuid);
        policyRuleType.addSrcPorts(matchConditionType.getSrcPort());
        policyRuleType.addDstPorts(matchConditionType.getDstPort());
        policyRuleType.addDstAddresses(matchConditionType.getDstAddress());
        policyRuleType.addSrcAddresses(matchConditionType.getSrcAddress());
        return policyRuleType;
    }

    public boolean addInstanceToSecurityGroup(String vmUuid, List<String> securityGroupUuidList) {
        try {
            VirtualMachine vm = (VirtualMachine) apiConnector.findById(VirtualMachine.class, vmUuid);
            if (vm == null) {
                return false;
            }

            VirtualMachineInterface vmi = null;
            for (ObjectReference obj : vm.getVirtualMachineInterfaceBackRefs()) {
                VirtualMachineInterface tungstenVmi =
                        (VirtualMachineInterface) apiConnector.findById(VirtualMachineInterface.class, obj.getUuid());
                if (tungstenVmi.getName().startsWith("vmi")) {
                    vmi = tungstenVmi;
                    break;
                }
            }

            if (vmi == null) {
                return false;
            }
            for (String securityGroupUuid : securityGroupUuidList) {
                SecurityGroup tungstenSecurityGroup = (SecurityGroup) apiConnector.findById(SecurityGroup.class, securityGroupUuid);
                if (tungstenSecurityGroup != null) {
                    vmi.addSecurityGroup(tungstenSecurityGroup);
                    apiConnector.update(vmi);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
