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
package com.cloud.hypervisor.kvm.resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.hypervisor.HypervisorUtils;
import org.apache.cloudstack.utils.linux.CPUStat;
import org.apache.cloudstack.utils.linux.MemStat;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainBlockStats;
import org.libvirt.DomainInfo;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.DomainInterfaceStats;
import org.libvirt.LibvirtException;
import org.libvirt.NodeInfo;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.HostVmStateReportEntry;
import com.cloud.agent.api.PingCommand;
import com.cloud.agent.api.PingRoutingCommand;
import com.cloud.agent.api.PingRoutingWithNwGroupsCommand;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.StartupStorageCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.routing.IpAssocCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.resource.virtualnetwork.VRScripts;
import com.cloud.agent.resource.virtualnetwork.VirtualRouterDeployer;
import com.cloud.agent.resource.virtualnetwork.VirtualRoutingResource;
import com.cloud.dc.Vlan;
import com.cloud.exception.InternalErrorException;
import com.cloud.host.Host.Type;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.ClockDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.ConsoleDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.CpuModeDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.CpuTuneDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DevicesDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DeviceType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskProtocol;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.FeaturesDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.FilesystemDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GraphicDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GuestDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.GuestResourceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InputDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef.GuestNetType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.SerialDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.TermPolicy;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.VideoDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.VirtioSerialDef;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtRequestWrapper;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtUtilitiesHelper;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.KVMStorageProcessor;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.RouterPrivateIpStrategy;
import com.cloud.network.Networks.TrafficType;
import com.cloud.resource.ServerResource;
import com.cloud.resource.ServerResourceBase;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.storage.resource.StorageSubsystemCommandHandler;
import com.cloud.storage.resource.StorageSubsystemCommandHandlerBase;
import com.cloud.utils.ExecutionResult;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.OutputInterpreter.AllLinesParser;
import com.cloud.utils.script.Script;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.PowerState;

/**
 * LibvirtComputingResource execute requests on the computing/routing host using
 * the libvirt API
 *
 * @config {@table || Param Name | Description | Values | Default || ||
 *         hypervisor.type | type of local hypervisor | string | kvm || ||
 *         hypervisor.uri | local hypervisor to connect to | URI |
 *         qemu:///system || || domr.arch | instruction set for domr template |
 *         string | i686 || || private.bridge.name | private bridge where the
 *         domrs have their private interface | string | vmops0 || ||
 *         public.bridge.name | public bridge where the domrs have their public
 *         interface | string | br0 || || private.network.name | name of the
 *         network where the domrs have their private interface | string |
 *         vmops-private || || private.ipaddr.start | start of the range of
 *         private ip addresses for domrs | ip address | 192.168.166.128 || ||
 *         private.ipaddr.end | end of the range of private ip addresses for
 *         domrs | ip address | start + 126 || || private.macaddr.start | start
 *         of the range of private mac addresses for domrs | mac address |
 *         00:16:3e:77:e2:a0 || || private.macaddr.end | end of the range of
 *         private mac addresses for domrs | mac address | start + 126 || ||
 *         pool | the parent of the storage pool hierarchy * }
 **/
@Local(value = {ServerResource.class})
public class LibvirtComputingResource extends ServerResourceBase implements ServerResource, VirtualRouterDeployer {
    private static final Logger s_logger = Logger.getLogger(LibvirtComputingResource.class);

    private String _modifyVlanPath;
    private String _versionstringpath;
    private String _patchViaSocketPath;
    private String _createvmPath;
    private String _manageSnapshotPath;
    private String _resizeVolumePath;
    private String _createTmplPath;
    private String _heartBeatPath;
    private String _securityGroupPath;
    private String _ovsPvlanDhcpHostPath;
    private String _ovsPvlanVmPath;
    private String _routerProxyPath;
    private String _ovsTunnelPath;
    private String _host;
    private String _dcId;
    private String _pod;
    private String _clusterId;

    private long _hvVersion;
    private long _kernelVersion;
    private int _timeout;

    private KVMHAMonitor _monitor;
    public static final String SSHKEYSPATH = "/root/.ssh";
    public static final String SSHPRVKEYPATH = SSHKEYSPATH + File.separator + "id_rsa.cloud";
    public static final String SSHPUBKEYPATH = SSHKEYSPATH + File.separator + "id_rsa.pub.cloud";

    public static final String BASH_SCRIPT_PATH = "/bin/bash";

    private String _mountPoint = "/mnt";
    private StorageLayer _storage;
    private KVMStoragePoolManager _storagePoolMgr;

    private VifDriver _defaultVifDriver;
    private Map<TrafficType, VifDriver> _trafficTypeVifDrivers;

    protected static final String DEFAULT_OVS_VIF_DRIVER_CLASS_NAME = "com.cloud.hypervisor.kvm.resource.OvsVifDriver";
    protected static final String DEFAULT_BRIDGE_VIF_DRIVER_CLASS_NAME = "com.cloud.hypervisor.kvm.resource.BridgeVifDriver";

    protected HypervisorType _hypervisorType;
    protected String _hypervisorURI;
    protected long _hypervisorLibvirtVersion;
    protected long _hypervisorQemuVersion;
    protected String _hypervisorPath;
    protected String _networkDirectSourceMode;
    protected String _networkDirectDevice;
    protected String _sysvmISOPath;
    protected String _privNwName;
    protected String _privBridgeName;
    protected String _linkLocalBridgeName;
    protected String _publicBridgeName;
    protected String _guestBridgeName;
    protected String _privateIp;
    protected String _pool;
    protected String _localGateway;
    private boolean _canBridgeFirewall;
    protected String _localStoragePath;
    protected String _localStorageUUID;
    protected boolean _noMemBalloon = false;
    protected String _guestCpuMode;
    protected String _guestCpuModel;
    protected boolean _noKvmClock;
    protected String _videoHw;
    protected int _videoRam;
    protected Pair<Integer,Integer> hostOsVersion;
    protected int _migrateSpeed;
    protected int _migrateDowntime;
    protected int _migratePauseAfter;
    protected boolean _diskActivityCheckEnabled;
    protected long _diskActivityCheckFileSizeMin = 10485760; // 10MB
    protected int _diskActivityCheckTimeoutSeconds = 120; // 120s
    protected long _diskActivityInactiveThresholdMilliseconds = 30000; // 30s

    private final Map <String, String> _pifs = new HashMap<String, String>();
    private final Map<String, VmStats> _vmStats = new ConcurrentHashMap<String, VmStats>();

    protected static final HashMap<DomainState, PowerState> s_powerStatesTable;
    static {
        s_powerStatesTable = new HashMap<DomainState, PowerState>();
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_SHUTOFF, PowerState.PowerOff);
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_PAUSED, PowerState.PowerOn);
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_RUNNING, PowerState.PowerOn);
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_BLOCKED, PowerState.PowerOn);
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_NOSTATE, PowerState.PowerUnknown);
        s_powerStatesTable.put(DomainState.VIR_DOMAIN_SHUTDOWN, PowerState.PowerOff);
    }

    protected List<String> _vmsKilled = new ArrayList<String>();

    private VirtualRoutingResource _virtRouterResource;

    private String _pingTestPath;

    private String _updateHostPasswdPath;

    private long _dom0MinMem;

    protected boolean _disconnected = true;
    protected int _cmdsTimeout;
    protected int _stopTimeout;
    protected CPUStat _cpuStat = new CPUStat();
    protected MemStat _memStat = new MemStat();

    private final LibvirtUtilitiesHelper libvirtUtilitiesHelper = new LibvirtUtilitiesHelper();

    @Override
    public ExecutionResult executeInVR(final String routerIp, final String script, final String args) {
        return executeInVR(routerIp, script, args, _timeout / 1000);
    }

    @Override
    public ExecutionResult executeInVR(final String routerIp, final String script, final String args, final int timeout) {
        final Script command = new Script(_routerProxyPath, timeout * 1000, s_logger);
        final AllLinesParser parser = new AllLinesParser();
        command.add(script);
        command.add(routerIp);
        if (args != null) {
            command.add(args);
        }
        String details = command.execute(parser);
        if (details == null) {
            details = parser.getLines();
        }
        return new ExecutionResult(command.getExitValue() == 0, details);
    }

    @Override
    public ExecutionResult createFileInVR(final String routerIp, final String path, final String filename, final String content) {
        final File permKey = new File("/root/.ssh/id_rsa.cloud");
        String error = null;

        try {
            SshHelper.scpTo(routerIp, 3922, "root", permKey, null, path, content.getBytes(), filename, null);
        } catch (final Exception e) {
            s_logger.warn("Fail to create file " + path + filename + " in VR " + routerIp, e);
            error = e.getMessage();
        }
        return new ExecutionResult(error == null, error);
    }

    @Override
    public ExecutionResult prepareCommand(final NetworkElementCommand cmd) {
        //Update IP used to access router
        cmd.setRouterAccessIp(cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP));
        assert cmd.getRouterAccessIp() != null;

        if (cmd instanceof IpAssocVpcCommand) {
            return prepareNetworkElementCommand((IpAssocVpcCommand)cmd);
        } else if (cmd instanceof IpAssocCommand) {
            return prepareNetworkElementCommand((IpAssocCommand)cmd);
        } else if (cmd instanceof SetupGuestNetworkCommand) {
            return prepareNetworkElementCommand((SetupGuestNetworkCommand)cmd);
        } else if (cmd instanceof SetSourceNatCommand) {
            return prepareNetworkElementCommand((SetSourceNatCommand)cmd);
        }
        return new ExecutionResult(true, null);
    }

    @Override
    public ExecutionResult cleanupCommand(final NetworkElementCommand cmd) {
        if (cmd instanceof IpAssocCommand && !(cmd instanceof IpAssocVpcCommand)) {
            return cleanupNetworkElementCommand((IpAssocCommand)cmd);
        }
        return new ExecutionResult(true, null);
    }

    public LibvirtUtilitiesHelper getLibvirtUtilitiesHelper() {
        return libvirtUtilitiesHelper;
    }

    public CPUStat getCPUStat() {
        return _cpuStat;
    }

    public MemStat getMemStat() {
        return _memStat;
    }

    public VirtualRoutingResource getVirtRouterResource() {
        return _virtRouterResource;
    }

    public String getPublicBridgeName() {
        return _publicBridgeName;
    }

    public KVMStoragePoolManager getStoragePoolMgr() {
        return _storagePoolMgr;
    }

    public String getPrivateIp() {
        return _privateIp;
    }

    public int getMigrateDowntime() {
        return _migrateDowntime;
    }

    public int getMigratePauseAfter() {
        return _migratePauseAfter;
    }

    public int getMigrateSpeed() {
        return _migrateSpeed;
    }

    public String getPingTestPath() {
        return _pingTestPath;
    }

    public String getUpdateHostPasswdPath() {
        return _updateHostPasswdPath;
    }

    public int getTimeout() {
        return _timeout;
    }

    public String getOvsTunnelPath() {
        return _ovsTunnelPath;
    }

    public KVMHAMonitor getMonitor() {
        return _monitor;
    }

    public StorageLayer getStorage() {
        return _storage;
    }

    public String createTmplPath() {
        return _createTmplPath;
    }

    public int getCmdsTimeout() {
        return _cmdsTimeout;
    }

    public String manageSnapshotPath() {
        return _manageSnapshotPath;
    }

    public String getGuestBridgeName() {
        return _guestBridgeName;
    }

    public String getOvsPvlanDhcpHostPath() {
        return _ovsPvlanDhcpHostPath;
    }

    public String getOvsPvlanVmPath() {
        return _ovsPvlanVmPath;
    }

    public String getResizeVolumePath() {
        return _resizeVolumePath;
    }

    public StorageSubsystemCommandHandler getStorageHandler() {
        return storageHandler;
    }

    private static final class KeyValueInterpreter extends OutputInterpreter {
        private final Map<String, String> map = new HashMap<String, String>();

        @Override
        public String interpret(final BufferedReader reader) throws IOException {
            String line = null;
            int numLines = 0;
            while ((line = reader.readLine()) != null) {
                final String[] toks = line.trim().split("=");
                if (toks.length < 2) {
                    s_logger.warn("Failed to parse Script output: " + line);
                } else {
                    map.put(toks[0].trim(), toks[1].trim());
                }
                numLines++;
            }
            if (numLines == 0) {
                s_logger.warn("KeyValueInterpreter: no output lines?");
            }
            return null;
        }

        public Map<String, String> getKeyValues() {
            return map;
        }
    }

    @Override
    protected String getDefaultScriptsDir() {
        return null;
    }

    protected List<String> _cpuFeatures;

    protected enum BridgeType {
        NATIVE, OPENVSWITCH
    }

    protected BridgeType _bridgeType;

    protected StorageSubsystemCommandHandler storageHandler;

    private String getEndIpFromStartIp(final String startIp, final int numIps) {
        final String[] tokens = startIp.split("[.]");
        assert tokens.length == 4;
        int lastbyte = Integer.parseInt(tokens[3]);
        lastbyte = lastbyte + numIps;
        tokens[3] = Integer.toString(lastbyte);
        final StringBuilder end = new StringBuilder(15);
        end.append(tokens[0]).append(".").append(tokens[1]).append(".").append(tokens[2]).append(".").append(tokens[3]);
        return end.toString();
    }

    private Map<String, Object> getDeveloperProperties() throws ConfigurationException {

        final File file = PropertiesUtil.findConfigFile("developer.properties");
        if (file == null) {
            throw new ConfigurationException("Unable to find developer.properties.");
        }

        s_logger.info("developer.properties found at " + file.getAbsolutePath());
        try {
            final Properties properties = PropertiesUtil.loadFromFile(file);

            final String startMac = (String)properties.get("private.macaddr.start");
            if (startMac == null) {
                throw new ConfigurationException("Developers must specify start mac for private ip range");
            }

            final String startIp = (String)properties.get("private.ipaddr.start");
            if (startIp == null) {
                throw new ConfigurationException("Developers must specify start ip for private ip range");
            }
            final Map<String, Object> params = PropertiesUtil.toMap(properties);

            String endIp = (String)properties.get("private.ipaddr.end");
            if (endIp == null) {
                endIp = getEndIpFromStartIp(startIp, 16);
                params.put("private.ipaddr.end", endIp);
            }
            return params;
        } catch (final FileNotFoundException ex) {
            throw new CloudRuntimeException("Cannot find the file: " + file.getAbsolutePath(), ex);
        } catch (final IOException ex) {
            throw new CloudRuntimeException("IOException in reading " + file.getAbsolutePath(), ex);
        }
    }

    protected String getDefaultNetworkScriptsDir() {
        return "scripts/vm/network/vnet";
    }

    protected String getDefaultStorageScriptsDir() {
        return "scripts/storage/qcow2";
    }

    protected String getDefaultHypervisorScriptsDir() {
        return "scripts/vm/hypervisor";
    }

    protected String getDefaultKvmScriptsDir() {
        return "scripts/vm/hypervisor/kvm";
    }

    protected String getDefaultDomrScriptsDir() {
        return "scripts/network/domr";
    }

    protected String getNetworkDirectSourceMode() {
        return _networkDirectSourceMode;
    }

    protected String getNetworkDirectDevice() {
        return _networkDirectDevice;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        boolean success = super.configure(name, params);
        if (!success) {
            return false;
        }

        _storage = new JavaStorageLayer();
        _storage.configure("StorageLayer", params);

        String domrScriptsDir = (String)params.get("domr.scripts.dir");
        if (domrScriptsDir == null) {
            domrScriptsDir = getDefaultDomrScriptsDir();
        }

        String hypervisorScriptsDir = (String)params.get("hypervisor.scripts.dir");
        if (hypervisorScriptsDir == null) {
            hypervisorScriptsDir = getDefaultHypervisorScriptsDir();
        }

        String kvmScriptsDir = (String)params.get("kvm.scripts.dir");
        if (kvmScriptsDir == null) {
            kvmScriptsDir = getDefaultKvmScriptsDir();
        }

        String networkScriptsDir = (String)params.get("network.scripts.dir");
        if (networkScriptsDir == null) {
            networkScriptsDir = getDefaultNetworkScriptsDir();
        }

        String storageScriptsDir = (String)params.get("storage.scripts.dir");
        if (storageScriptsDir == null) {
            storageScriptsDir = getDefaultStorageScriptsDir();
        }

        final String bridgeType = (String)params.get("network.bridge.type");
        if (bridgeType == null) {
            _bridgeType = BridgeType.NATIVE;
        } else {
            _bridgeType = BridgeType.valueOf(bridgeType.toUpperCase());
        }

        params.put("domr.scripts.dir", domrScriptsDir);

        _virtRouterResource = new VirtualRoutingResource(this);
        success = _virtRouterResource.configure(name, params);

        if (!success) {
            return false;
        }

        _host = (String)params.get("host");
        if (_host == null) {
            _host = "localhost";
        }

        _dcId = (String)params.get("zone");
        if (_dcId == null) {
            _dcId = "default";
        }

        _pod = (String)params.get("pod");
        if (_pod == null) {
            _pod = "default";
        }

        _clusterId = (String)params.get("cluster");

        _updateHostPasswdPath = Script.findScript(hypervisorScriptsDir, VRScripts.UPDATE_HOST_PASSWD);
        if (_updateHostPasswdPath == null) {
            throw new ConfigurationException("Unable to find update_host_passwd.sh");
        }

        _modifyVlanPath = Script.findScript(networkScriptsDir, "modifyvlan.sh");
        if (_modifyVlanPath == null) {
            throw new ConfigurationException("Unable to find modifyvlan.sh");
        }

        _versionstringpath = Script.findScript(kvmScriptsDir, "versions.sh");
        if (_versionstringpath == null) {
            throw new ConfigurationException("Unable to find versions.sh");
        }

        _patchViaSocketPath = Script.findScript(kvmScriptsDir + "/patch/", "patchviasocket.py");
        if (_patchViaSocketPath == null) {
            throw new ConfigurationException("Unable to find patchviasocket.py");
        }

        _heartBeatPath = Script.findScript(kvmScriptsDir, "kvmheartbeat.sh");
        if (_heartBeatPath == null) {
            throw new ConfigurationException("Unable to find kvmheartbeat.sh");
        }

        _createvmPath = Script.findScript(storageScriptsDir, "createvm.sh");
        if (_createvmPath == null) {
            throw new ConfigurationException("Unable to find the createvm.sh");
        }

        _manageSnapshotPath = Script.findScript(storageScriptsDir, "managesnapshot.sh");
        if (_manageSnapshotPath == null) {
            throw new ConfigurationException("Unable to find the managesnapshot.sh");
        }

        _resizeVolumePath = Script.findScript(storageScriptsDir, "resizevolume.sh");
        if (_resizeVolumePath == null) {
            throw new ConfigurationException("Unable to find the resizevolume.sh");
        }

        _createTmplPath = Script.findScript(storageScriptsDir, "createtmplt.sh");
        if (_createTmplPath == null) {
            throw new ConfigurationException("Unable to find the createtmplt.sh");
        }

        _securityGroupPath = Script.findScript(networkScriptsDir, "security_group.py");
        if (_securityGroupPath == null) {
            throw new ConfigurationException("Unable to find the security_group.py");
        }

        _ovsTunnelPath = Script.findScript(networkScriptsDir, "ovstunnel.py");
        if (_ovsTunnelPath == null) {
            throw new ConfigurationException("Unable to find the ovstunnel.py");
        }

        _routerProxyPath = Script.findScript("scripts/network/domr/", "router_proxy.sh");
        if (_routerProxyPath == null) {
            throw new ConfigurationException("Unable to find the router_proxy.sh");
        }

        _ovsPvlanDhcpHostPath = Script.findScript(networkScriptsDir, "ovs-pvlan-dhcp-host.sh");
        if (_ovsPvlanDhcpHostPath == null) {
            throw new ConfigurationException("Unable to find the ovs-pvlan-dhcp-host.sh");
        }

        _ovsPvlanVmPath = Script.findScript(networkScriptsDir, "ovs-pvlan-vm.sh");
        if (_ovsPvlanVmPath == null) {
            throw new ConfigurationException("Unable to find the ovs-pvlan-vm.sh");
        }

        String value = (String)params.get("developer");
        final boolean isDeveloper = Boolean.parseBoolean(value);

        if (isDeveloper) {
            params.putAll(getDeveloperProperties());
        }

        _pool = (String)params.get("pool");
        if (_pool == null) {
            _pool = "/root";
        }

        final String instance = (String)params.get("instance");

        _hypervisorType = HypervisorType.getType((String)params.get("hypervisor.type"));
        if (_hypervisorType == HypervisorType.None) {
            _hypervisorType = HypervisorType.KVM;
        }

        _hypervisorURI = (String)params.get("hypervisor.uri");
        if (_hypervisorURI == null) {
            _hypervisorURI = LibvirtConnection.getHypervisorURI(_hypervisorType.toString());
        }

        _networkDirectSourceMode = (String)params.get("network.direct.source.mode");
        _networkDirectDevice = (String)params.get("network.direct.device");

        String startMac = (String)params.get("private.macaddr.start");
        if (startMac == null) {
            startMac = "00:16:3e:77:e2:a0";
        }

        String startIp = (String)params.get("private.ipaddr.start");
        if (startIp == null) {
            startIp = "192.168.166.128";
        }

        _pingTestPath = Script.findScript(kvmScriptsDir, "pingtest.sh");
        if (_pingTestPath == null) {
            throw new ConfigurationException("Unable to find the pingtest.sh");
        }

        _linkLocalBridgeName = (String)params.get("private.bridge.name");
        if (_linkLocalBridgeName == null) {
            if (isDeveloper) {
                _linkLocalBridgeName = "cloud-" + instance + "-0";
            } else {
                _linkLocalBridgeName = "cloud0";
            }
        }

        _publicBridgeName = (String)params.get("public.network.device");
        if (_publicBridgeName == null) {
            _publicBridgeName = "cloudbr0";
        }

        _privBridgeName = (String)params.get("private.network.device");
        if (_privBridgeName == null) {
            _privBridgeName = "cloudbr1";
        }

        _guestBridgeName = (String)params.get("guest.network.device");
        if (_guestBridgeName == null) {
            _guestBridgeName = _privBridgeName;
        }

        _privNwName = (String)params.get("private.network.name");
        if (_privNwName == null) {
            if (isDeveloper) {
                _privNwName = "cloud-" + instance + "-private";
            } else {
                _privNwName = "cloud-private";
            }
        }

        _localStoragePath = (String)params.get("local.storage.path");
        if (_localStoragePath == null) {
            _localStoragePath = "/var/lib/libvirt/images/";
        }

        final File storagePath = new File(_localStoragePath);
        _localStoragePath = storagePath.getAbsolutePath();

        _localStorageUUID = (String)params.get("local.storage.uuid");
        if (_localStorageUUID == null) {
            _localStorageUUID = UUID.randomUUID().toString();
        }

        value = (String)params.get("scripts.timeout");
        _timeout = NumbersUtil.parseInt(value, 30 * 60) * 1000;

        value = (String)params.get("stop.script.timeout");
        _stopTimeout = NumbersUtil.parseInt(value, 120) * 1000;

        value = (String)params.get("cmds.timeout");
        _cmdsTimeout = NumbersUtil.parseInt(value, 7200) * 1000;

        value = (String) params.get("vm.memballoon.disable");
        if (Boolean.parseBoolean(value)) {
            _noMemBalloon = true;
        }

        _videoHw = (String) params.get("vm.video.hardware");
        value = (String) params.get("vm.video.ram");
        _videoRam = NumbersUtil.parseInt(value, 0);

        value = (String)params.get("host.reserved.mem.mb");
        // Reserve 1GB unless admin overrides
        _dom0MinMem = NumbersUtil.parseInt(value, 1024) * 1024 * 1024L;

        value = (String) params.get("kvmclock.disable");
        if (Boolean.parseBoolean(value)) {
            _noKvmClock = true;
        }

        LibvirtConnection.initialize(_hypervisorURI);
        Connect conn = null;
        try {
            conn = LibvirtConnection.getConnection();

            if (_bridgeType == BridgeType.OPENVSWITCH) {
                if (conn.getLibVirVersion() < 10 * 1000 + 0) {
                    throw new ConfigurationException("Libvirt version 0.10.0 required for openvswitch support, but version " + conn.getLibVirVersion() + " detected");
                }
            }
        } catch (final LibvirtException e) {
            throw new CloudRuntimeException(e.getMessage());
        }

        if (HypervisorType.KVM == _hypervisorType) {
            /* Does node support HVM guest? If not, exit */
            if (!IsHVMEnabled(conn)) {
                throw new ConfigurationException("NO HVM support on this machine, please make sure: " + "1. VT/SVM is supported by your CPU, or is enabled in BIOS. "
                        + "2. kvm modules are loaded (kvm, kvm_amd|kvm_intel)");
            }
        }

        _hypervisorPath = getHypervisorPath(conn);
        try {
            _hvVersion = conn.getVersion();
            _hvVersion = _hvVersion % 1000000 / 1000;
            _hypervisorLibvirtVersion = conn.getLibVirVersion();
            _hypervisorQemuVersion = conn.getVersion();
        } catch (final LibvirtException e) {
            s_logger.trace("Ignoring libvirt error.", e);
        }

        _guestCpuMode = (String)params.get("guest.cpu.mode");
        if (_guestCpuMode != null) {
            _guestCpuModel = (String)params.get("guest.cpu.model");

            if (_hypervisorLibvirtVersion < 9 * 1000 + 10) {
                s_logger.warn("Libvirt version 0.9.10 required for guest cpu mode, but version " + prettyVersion(_hypervisorLibvirtVersion) +
                        " detected, so it will be disabled");
                _guestCpuMode = "";
                _guestCpuModel = "";
            }
            params.put("guest.cpu.mode", _guestCpuMode);
            params.put("guest.cpu.model", _guestCpuModel);
        }

        final String cpuFeatures = (String)params.get("guest.cpu.features");
        if (cpuFeatures != null) {
            _cpuFeatures = new ArrayList<String>();
            for (final String feature: cpuFeatures.split(" ")) {
                if (!feature.isEmpty()) {
                    _cpuFeatures.add(feature);
                }
            }
        }

        final String[] info = NetUtils.getNetworkParams(_privateNic);

        _monitor = new KVMHAMonitor(null, info[0], _heartBeatPath);
        final Thread ha = new Thread(_monitor);
        ha.start();

        _storagePoolMgr = new KVMStoragePoolManager(_storage, _monitor);

        _sysvmISOPath = (String)params.get("systemvm.iso.path");
        if (_sysvmISOPath == null) {
            final String[] isoPaths = {"/usr/share/cloudstack-common/vms/systemvm.iso"};
            for (final String isoPath : isoPaths) {
                if (_storage.exists(isoPath)) {
                    _sysvmISOPath = isoPath;
                    break;
                }
            }
            if (_sysvmISOPath == null) {
                s_logger.debug("Can't find system vm ISO");
            }
        }

        switch (_bridgeType) {
        case OPENVSWITCH:
            getOvsPifs();
            break;
        case NATIVE:
        default:
            getPifs();
            break;
        }

        if (_pifs.get("private") == null) {
            s_logger.debug("Failed to get private nic name");
            throw new ConfigurationException("Failed to get private nic name");
        }

        if (_pifs.get("public") == null) {
            s_logger.debug("Failed to get public nic name");
            throw new ConfigurationException("Failed to get public nic name");
        }
        s_logger.debug("Found pif: " + _pifs.get("private") + " on " + _privBridgeName + ", pif: " + _pifs.get("public") + " on " + _publicBridgeName);

        _canBridgeFirewall = canBridgeFirewall(_pifs.get("public"));

        _localGateway = Script.runSimpleBashScript("ip route |grep default|awk '{print $3}'");
        if (_localGateway == null) {
            s_logger.debug("Failed to found the local gateway");
        }

        _mountPoint = (String)params.get("mount.path");
        if (_mountPoint == null) {
            _mountPoint = "/mnt";
        }

        value = (String) params.get("vm.migrate.downtime");
        _migrateDowntime = NumbersUtil.parseInt(value, -1);

        value = (String) params.get("vm.migrate.pauseafter");
        _migratePauseAfter = NumbersUtil.parseInt(value, -1);

        value = (String)params.get("vm.migrate.speed");
        _migrateSpeed = NumbersUtil.parseInt(value, -1);
        if (_migrateSpeed == -1) {
            //get guest network device speed
            _migrateSpeed = 0;
            final String speed = Script.runSimpleBashScript("ethtool " + _pifs.get("public") + " |grep Speed | cut -d \\  -f 2");
            if (speed != null) {
                final String[] tokens = speed.split("M");
                if (tokens.length == 2) {
                    try {
                        _migrateSpeed = Integer.parseInt(tokens[0]);
                    } catch (final NumberFormatException e) {
                        s_logger.trace("Ignoring migrateSpeed extraction error.", e);
                    }
                    s_logger.debug("device " + _pifs.get("public") + " has speed: " + String.valueOf(_migrateSpeed));
                }
            }
            params.put("vm.migrate.speed", String.valueOf(_migrateSpeed));
        }

        final Map<String, String> bridges = new HashMap<String, String>();
        bridges.put("linklocal", _linkLocalBridgeName);
        bridges.put("public", _publicBridgeName);
        bridges.put("private", _privBridgeName);
        bridges.put("guest", _guestBridgeName);

        params.put("libvirt.host.bridges", bridges);
        params.put("libvirt.host.pifs", _pifs);

        params.put("libvirt.computing.resource", this);
        params.put("libvirtVersion", _hypervisorLibvirtVersion);

        configureVifDrivers(params);
        configureDiskActivityChecks(params);

        final KVMStorageProcessor storageProcessor = new KVMStorageProcessor(_storagePoolMgr, this);
        storageProcessor.configure(name, params);
        storageHandler = new StorageSubsystemCommandHandlerBase(storageProcessor);

        final String unameKernelVersion = Script.runSimpleBashScript("uname -r");
        final String[] kernelVersions = unameKernelVersion.split("[\\.\\-]");
        _kernelVersion = Integer.parseInt(kernelVersions[0]) * 1000 * 1000 + (long)Integer.parseInt(kernelVersions[1]) * 1000 + Integer.parseInt(kernelVersions[2]);

        /* Disable this, the code using this is pretty bad and non portable
         * getOsVersion();
         */
        return true;
    }

    protected void configureDiskActivityChecks(final Map<String, Object> params) {
        _diskActivityCheckEnabled = Boolean.parseBoolean((String)params.get("vm.diskactivity.checkenabled"));
        if (_diskActivityCheckEnabled) {
            final int timeout = NumbersUtil.parseInt((String)params.get("vm.diskactivity.checktimeout_s"), 0);
            if (timeout > 0) {
                _diskActivityCheckTimeoutSeconds = timeout;
            }
            final long inactiveTime = NumbersUtil.parseLong((String)params.get("vm.diskactivity.inactivetime_ms"), 0L);
            if (inactiveTime > 0) {
                _diskActivityInactiveThresholdMilliseconds = inactiveTime;
            }
        }
    }

    protected void configureVifDrivers(final Map<String, Object> params) throws ConfigurationException {
        final String LIBVIRT_VIF_DRIVER = "libvirt.vif.driver";

        _trafficTypeVifDrivers = new HashMap<TrafficType, VifDriver>();

        // Load the default vif driver
        String defaultVifDriverName = (String)params.get(LIBVIRT_VIF_DRIVER);
        if (defaultVifDriverName == null) {
            if (_bridgeType == BridgeType.OPENVSWITCH) {
                s_logger.info("No libvirt.vif.driver specified. Defaults to OvsVifDriver.");
                defaultVifDriverName = DEFAULT_OVS_VIF_DRIVER_CLASS_NAME;
            } else {
                s_logger.info("No libvirt.vif.driver specified. Defaults to BridgeVifDriver.");
                defaultVifDriverName = DEFAULT_BRIDGE_VIF_DRIVER_CLASS_NAME;
            }
        }
        _defaultVifDriver = getVifDriverClass(defaultVifDriverName, params);

        // Load any per-traffic-type vif drivers
        for (final Map.Entry<String, Object> entry : params.entrySet()) {
            final String k = entry.getKey();
            final String vifDriverPrefix = LIBVIRT_VIF_DRIVER + ".";

            if (k.startsWith(vifDriverPrefix)) {
                // Get trafficType
                final String trafficTypeSuffix = k.substring(vifDriverPrefix.length());

                // Does this suffix match a real traffic type?
                final TrafficType trafficType = TrafficType.getTrafficType(trafficTypeSuffix);
                if (!trafficType.equals(TrafficType.None)) {
                    // Get vif driver class name
                    final String vifDriverClassName = (String)entry.getValue();
                    // if value is null, ignore
                    if (vifDriverClassName != null) {
                        // add traffic type to vif driver mapping to Map
                        _trafficTypeVifDrivers.put(trafficType, getVifDriverClass(vifDriverClassName, params));
                    }
                }
            }
        }
    }

    protected VifDriver getVifDriverClass(final String vifDriverClassName, final Map<String, Object> params) throws ConfigurationException {
        VifDriver vifDriver;

        try {
            final Class<?> clazz = Class.forName(vifDriverClassName);
            vifDriver = (VifDriver)clazz.newInstance();
            vifDriver.configure(params);
        } catch (final ClassNotFoundException e) {
            throw new ConfigurationException("Unable to find class for libvirt.vif.driver " + e);
        } catch (final InstantiationException e) {
            throw new ConfigurationException("Unable to instantiate class for libvirt.vif.driver " + e);
        } catch (final IllegalAccessException e) {
            throw new ConfigurationException("Unable to instantiate class for libvirt.vif.driver " + e);
        }
        return vifDriver;
    }

    public VifDriver getVifDriver(final TrafficType trafficType) {
        VifDriver vifDriver = _trafficTypeVifDrivers.get(trafficType);

        if (vifDriver == null) {
            vifDriver = _defaultVifDriver;
        }

        return vifDriver;
    }

    public List<VifDriver> getAllVifDrivers() {
        final Set<VifDriver> vifDrivers = new HashSet<VifDriver>();

        vifDrivers.add(_defaultVifDriver);
        vifDrivers.addAll(_trafficTypeVifDrivers.values());

        final ArrayList<VifDriver> vifDriverList = new ArrayList<VifDriver>(vifDrivers);

        return vifDriverList;
    }

    private void getPifs() {
        final File dir = new File("/sys/devices/virtual/net");
        final File[] netdevs = dir.listFiles();
        final List<String> bridges = new ArrayList<String>();
        for (int i = 0; i < netdevs.length; i++) {
            final File isbridge = new File(netdevs[i].getAbsolutePath() + "/bridge");
            final String netdevName = netdevs[i].getName();
            s_logger.debug("looking in file " + netdevs[i].getAbsolutePath() + "/bridge");
            if (isbridge.exists()) {
                s_logger.debug("Found bridge " + netdevName);
                bridges.add(netdevName);
            }
        }

        for (final String bridge : bridges) {
            s_logger.debug("looking for pif for bridge " + bridge);
            final String pif = getPif(bridge);
            if (_publicBridgeName != null && bridge.equals(_publicBridgeName)) {
                _pifs.put("public", pif);
            }
            if (_guestBridgeName != null && bridge.equals(_guestBridgeName)) {
                _pifs.put("private", pif);
            }
            _pifs.put(bridge, pif);
        }

        // guest(private) creates bridges on a pif, if private bridge not found try pif direct
        // This addresses the unnecessary requirement of someone to create an unused bridge just for traffic label
        if (_pifs.get("private") == null) {
            s_logger.debug("guest(private) traffic label '" + _guestBridgeName + "' not found as bridge, looking for physical interface");
            final File dev = new File("/sys/class/net/" + _guestBridgeName);
            if (dev.exists()) {
                s_logger.debug("guest(private) traffic label '" + _guestBridgeName + "' found as a physical device");
                _pifs.put("private", _guestBridgeName);
            }
        }

        // public creates bridges on a pif, if private bridge not found try pif direct
        // This addresses the unnecessary requirement of someone to create an unused bridge just for traffic label
        if (_pifs.get("public") == null) {
            s_logger.debug("public traffic label '" + _publicBridgeName+ "' not found as bridge, looking for physical interface");
            final File dev = new File("/sys/class/net/" + _publicBridgeName);
            if (dev.exists()) {
                s_logger.debug("public traffic label '" + _publicBridgeName + "' found as a physical device");
                _pifs.put("public", _publicBridgeName);
            }
        }

        s_logger.debug("done looking for pifs, no more bridges");
    }

    private void getOvsPifs() {
        final String cmdout = Script.runSimpleBashScript("ovs-vsctl list-br | sed '{:q;N;s/\\n/%/g;t q}'");
        s_logger.debug("cmdout was " + cmdout);
        final List<String> bridges = Arrays.asList(cmdout.split("%"));
        for (final String bridge : bridges) {
            s_logger.debug("looking for pif for bridge " + bridge);
            // String pif = getOvsPif(bridge);
            // Not really interested in the pif name at this point for ovs
            // bridges
            final String pif = bridge;
            if (_publicBridgeName != null && bridge.equals(_publicBridgeName)) {
                _pifs.put("public", pif);
            }
            if (_guestBridgeName != null && bridge.equals(_guestBridgeName)) {
                _pifs.put("private", pif);
            }
            _pifs.put(bridge, pif);
        }
        s_logger.debug("done looking for pifs, no more bridges");
    }

    private String getPif(final String bridge) {
        String pif = matchPifFileInDirectory(bridge);
        final File vlanfile = new File("/proc/net/vlan/" + pif);

        if (vlanfile.isFile()) {
            pif = Script.runSimpleBashScript("grep ^Device\\: /proc/net/vlan/" + pif + " | awk {'print $2'}");
        }

        return pif;
    }

    private String matchPifFileInDirectory(final String bridgeName) {
        final File brif = new File("/sys/devices/virtual/net/" + bridgeName + "/brif");

        if (!brif.isDirectory()) {
            final File pif = new File("/sys/class/net/" + bridgeName);
            if (pif.isDirectory()) {
                // if bridgeName already refers to a pif, return it as-is
                return bridgeName;
            }
            s_logger.debug("failing to get physical interface from bridge " + bridgeName + ", does " + brif.getAbsolutePath() + "exist?");
            return "";
        }

        final File[] interfaces = brif.listFiles();

        for (int i = 0; i < interfaces.length; i++) {
            final String fname = interfaces[i].getName();
            s_logger.debug("matchPifFileInDirectory: file name '" + fname + "'");
            if (isInterface(fname)) {
                return fname;
            }
        }

        s_logger.debug("failing to get physical interface from bridge " + bridgeName + ", did not find an eth*, bond*, team*, vlan*, em*, p*p*, ens*, eno*, enp*, or enx* in " + brif.getAbsolutePath());
        return "";
    }

    String [] _ifNamePatterns = {
            "^eth",
            "^bond",
            "^vlan",
            "^vx",
            "^em",
            "^ens",
            "^eno",
            "^enp",
            "^team",
            "^enx",
            "^p\\d+p\\d+"
    };
    /**
     * @param fname
     * @return
     */
    boolean isInterface(final String fname) {
        StringBuffer commonPattern = new StringBuffer();
        for (final String ifNamePattern : _ifNamePatterns) {
            commonPattern.append("|(").append(ifNamePattern).append(".*)");
        }
        if(fname.matches(commonPattern.toString())) {
            return true;
        }
        return false;
    }

    public boolean checkNetwork(final String networkName) {
        if (networkName == null) {
            return true;
        }

        if (_bridgeType == BridgeType.OPENVSWITCH) {
            return checkOvsNetwork(networkName);
        } else {
            return checkBridgeNetwork(networkName);
        }
    }

    private boolean checkBridgeNetwork(final String networkName) {
        if (networkName == null) {
            return true;
        }

        final String name = matchPifFileInDirectory(networkName);

        if (name == null || name.isEmpty()) {
            return false;
        } else {
            return true;
        }
    }

    private boolean checkOvsNetwork(final String networkName) {
        s_logger.debug("Checking if network " + networkName + " exists as openvswitch bridge");
        if (networkName == null) {
            return true;
        }

        final Script command = new Script("/bin/sh", _timeout);
        command.add("-c");
        command.add("ovs-vsctl br-exists " + networkName);
        return "0".equals(command.execute(null));
    }

    public boolean passCmdLine(final String vmName, final String cmdLine) throws InternalErrorException {
        final Script command = new Script(_patchViaSocketPath, 5 * 1000, s_logger);
        String result;
        command.add("-n", vmName);
        command.add("-p", cmdLine.replaceAll(" ", "%"));
        result = command.execute();
        if (result != null) {
            s_logger.debug("passcmd failed:" + result);
            return false;
        }
        return true;
    }

    boolean isDirectAttachedNetwork(final String type) {
        if ("untagged".equalsIgnoreCase(type)) {
            return true;
        } else {
            try {
                Long.valueOf(type);
            } catch (final NumberFormatException e) {
                return true;
            }
            return false;
        }
    }

    public String startVM(final Connect conn, final String vmName, final String domainXML) throws LibvirtException, InternalErrorException {
        try {
            /*
                We create a transient domain here. When this method gets
                called we receive a full XML specification of the guest,
                so no need to define it persistent.

                This also makes sure we never have any old "garbage" defined
                in libvirt which might haunt us.
             */

            // check for existing inactive vm definition and remove it
            // this can sometimes happen during crashes, etc
            Domain dm = null;
            try {
                dm = conn.domainLookupByName(vmName);
                if (dm != null && dm.isPersistent() == 1) {
                    // this is safe because it doesn't stop running VMs
                    dm.undefine();
                }
            } catch (final LibvirtException e) {
                // this is what we want, no domain found
            } finally {
                if (dm != null) {
                    dm.free();
                }
            }

            conn.domainCreateXML(domainXML, 0);
        } catch (final LibvirtException e) {
            throw e;
        }
        return null;
    }

    @Override
    public boolean stop() {
        try {
            final Connect conn = LibvirtConnection.getConnection();
            conn.close();
        } catch (final LibvirtException e) {
            s_logger.trace("Ignoring libvirt error.", e);
        }

        return true;
    }

    @Override
    public Answer executeRequest(final Command cmd) {

        final LibvirtRequestWrapper wrapper = LibvirtRequestWrapper.getInstance();
        try {
            return wrapper.execute(cmd, this);
        } catch (final Exception e) {
            return Answer.createUnsupportedCommandAnswer(cmd);
        }
    }

    public synchronized boolean destroyTunnelNetwork(final String bridge) {
        findOrCreateTunnelNetwork(bridge);

        final Script cmd = new Script(_ovsTunnelPath, _timeout, s_logger);
        cmd.add("destroy_ovs_bridge");
        cmd.add("--bridge", bridge);

        final String result = cmd.execute();

        if (result != null) {
            s_logger.debug("OVS Bridge could not be destroyed due to error ==> " + result);
            return false;
        }
        return true;
    }

    public synchronized boolean findOrCreateTunnelNetwork(final String nwName) {
        try {
            if (checkNetwork(nwName)) {
                return true;
            }
            // if not found, create a new one
            final Map<String, String> otherConfig = new HashMap<String, String>();
            otherConfig.put("ovs-host-setup", "");
            Script.runSimpleBashScript("ovs-vsctl -- --may-exist add-br "
                    + nwName + " -- set bridge " + nwName
                    + " other_config:ovs-host-setup='-1'");
            s_logger.debug("### KVM network for tunnels created:" + nwName);
        } catch (final Exception e) {
            s_logger.warn("createTunnelNetwork failed", e);
            return false;
        }
        return true;
    }

    public synchronized boolean configureTunnelNetwork(final long networkId,
            final long hostId, final String nwName) {
        try {
            final boolean findResult = findOrCreateTunnelNetwork(nwName);
            if (!findResult) {
                s_logger.warn("LibvirtComputingResource.findOrCreateTunnelNetwork() failed! Cannot proceed creating the tunnel.");
                return false;
            }
            final String configuredHosts = Script
                    .runSimpleBashScript("ovs-vsctl get bridge " + nwName
                            + " other_config:ovs-host-setup");
            boolean configured = false;
            if (configuredHosts != null) {
                final String hostIdsStr[] = configuredHosts.split(",");
                for (final String hostIdStr : hostIdsStr) {
                    if (hostIdStr.equals(((Long)hostId).toString())) {
                        configured = true;
                        break;
                    }
                }
            }
            if (!configured) {
                final Script cmd = new Script(_ovsTunnelPath, _timeout, s_logger);
                cmd.add("setup_ovs_bridge");
                cmd.add("--key", nwName);
                cmd.add("--cs_host_id", ((Long)hostId).toString());
                cmd.add("--bridge", nwName);
                final String result = cmd.execute();
                if (result != null) {
                    throw new CloudRuntimeException(
                            "Unable to pre-configure OVS bridge " + nwName
                            + " for network ID:" + networkId);
                }
            }
        } catch (final Exception e) {
            s_logger.warn("createandConfigureTunnelNetwork failed", e);
            return false;
        }
        return true;
    }

    protected Storage.StorageResourceType getStorageResourceType() {
        return Storage.StorageResourceType.STORAGE_POOL;
    }

    // this is much like PrimaryStorageDownloadCommand, but keeping it separate
    public KVMPhysicalDisk templateToPrimaryDownload(final String templateUrl, final KVMStoragePool primaryPool, final String volUuid) {
        final int index = templateUrl.lastIndexOf("/");
        final String mountpoint = templateUrl.substring(0, index);
        String templateName = null;
        if (index < templateUrl.length() - 1) {
            templateName = templateUrl.substring(index + 1);
        }

        KVMPhysicalDisk templateVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = _storagePoolMgr.getStoragePoolByURI(mountpoint);
            /* Get template vol */
            if (templateName == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    s_logger.error("Failed to get volumes from pool: " + secondaryPool.getUuid());
                    return null;
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        templateVol = disk;
                        break;
                    }
                }
                if (templateVol == null) {
                    s_logger.error("Failed to get template from pool: " + secondaryPool.getUuid());
                    return null;
                }
            } else {
                templateVol = secondaryPool.getPhysicalDisk(templateName);
            }

            /* Copy volume to primary storage */

            final KVMPhysicalDisk primaryVol = _storagePoolMgr.copyPhysicalDisk(templateVol, volUuid, primaryPool, 0);
            return primaryVol;
        } catch (final CloudRuntimeException e) {
            s_logger.error("Failed to download template to primary storage", e);
            return null;
        } finally {
            if (secondaryPool != null) {
                _storagePoolMgr.deleteStoragePool(secondaryPool.getType(), secondaryPool.getUuid());
            }
        }
    }

    public String getResizeScriptType(final KVMStoragePool pool, final KVMPhysicalDisk vol) {
        final StoragePoolType poolType = pool.getType();
        final PhysicalDiskFormat volFormat = vol.getFormat();

        if (pool.getType() == StoragePoolType.CLVM && volFormat == PhysicalDiskFormat.RAW) {
            return "CLVM";
        } else if ((poolType == StoragePoolType.NetworkFilesystem
                || poolType == StoragePoolType.SharedMountPoint
                || poolType == StoragePoolType.Filesystem
                || poolType == StoragePoolType.Gluster)
                && volFormat == PhysicalDiskFormat.QCOW2 ) {
            return "QCOW2";
        }
        throw new CloudRuntimeException("Cannot determine resize type from pool type " + pool.getType());
    }

    private String getBroadcastUriFromBridge(final String brName) {
        final String pif = matchPifFileInDirectory(brName);
        final Pattern pattern = Pattern.compile("(\\D+)(\\d+)(\\D*)(\\d*)");
        final Matcher matcher = pattern.matcher(pif);
        s_logger.debug("getting broadcast uri for pif " + pif + " and bridge " + brName);
        if(matcher.find()) {
            if (brName.startsWith("brvx")){
                return BroadcastDomainType.Vxlan.toUri(matcher.group(2)).toString();
            }
            else{
                if (!matcher.group(4).isEmpty()) {
                    return BroadcastDomainType.Vlan.toUri(matcher.group(4)).toString();
                } else {
                    //untagged or not matching (eth|bond|team)#.#
                    s_logger.debug("failed to get vNet id from bridge " + brName
                            + "attached to physical interface" + pif + ", perhaps untagged interface");
                    return "";
                }
            }
        } else {
            s_logger.debug("failed to get vNet id from bridge " + brName + "attached to physical interface" + pif);
            return "";
        }
    }

    private void VifHotPlug(final Connect conn, final String vmName, final String broadcastUri, final String macAddr) throws InternalErrorException, LibvirtException {
        final NicTO nicTO = new NicTO();
        nicTO.setMac(macAddr);
        nicTO.setType(TrafficType.Public);
        if (broadcastUri == null) {
            nicTO.setBroadcastType(BroadcastDomainType.Native);
        } else {
            final URI uri = BroadcastDomainType.fromString(broadcastUri);
            nicTO.setBroadcastType(BroadcastDomainType.getSchemeValue(uri));
            nicTO.setBroadcastUri(uri);
        }

        final Domain vm = getDomain(conn, vmName);
        vm.attachDevice(getVifDriver(nicTO.getType()).plug(nicTO, "Other PV", "").toString());
    }


    private void vifHotUnPlug (final Connect conn, final String vmName, final String macAddr) throws InternalErrorException, LibvirtException {

        Domain vm = null;
        vm = getDomain(conn, vmName);
        final List<InterfaceDef> pluggedNics = getInterfaces(conn, vmName);
        for (final InterfaceDef pluggedNic : pluggedNics) {
            if (pluggedNic.getMacAddress().equalsIgnoreCase(macAddr)) {
                vm.detachDevice(pluggedNic.toString());
                // We don't know which "traffic type" is associated with
                // each interface at this point, so inform all vif drivers
                for (final VifDriver vifDriver : getAllVifDrivers()) {
                    vifDriver.unplug(pluggedNic);
                }
            }
        }
    }

    private ExecutionResult prepareNetworkElementCommand(final SetupGuestNetworkCommand cmd) {
        Connect conn;
        final NicTO nic = cmd.getNic();
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);

        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);
            InterfaceDef routerNic = null;

            for (final InterfaceDef pluggedNic : pluggedNics) {
                if (pluggedNic.getMacAddress().equalsIgnoreCase(nic.getMac())) {
                    routerNic = pluggedNic;
                    break;
                }
            }

            if (routerNic == null) {
                return new ExecutionResult(false, "Can not find nic with mac " + nic.getMac() + " for VM " + routerName);
            }

            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            final String msg = "Creating guest network failed due to " + e.toString();
            s_logger.warn(msg, e);
            return new ExecutionResult(false, msg);
        }
    }

    protected ExecutionResult prepareNetworkElementCommand(final SetSourceNatCommand cmd) {
        Connect conn;
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        final IpAddressTO pubIP = cmd.getIpAddress();

        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            Integer devNum = 0;
            final String pubVlan = pubIP.getBroadcastUri();
            final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);

            for (final InterfaceDef pluggedNic : pluggedNics) {
                final String pluggedVlanBr = pluggedNic.getBrName();
                final String pluggedVlanId = getBroadcastUriFromBridge(pluggedVlanBr);
                if (pubVlan.equalsIgnoreCase(Vlan.UNTAGGED) && pluggedVlanBr.equalsIgnoreCase(_publicBridgeName)) {
                    break;
                } else if (pluggedVlanBr.equalsIgnoreCase(_linkLocalBridgeName)) {
                    /*skip over, no physical bridge device exists*/
                } else if (pluggedVlanId == null) {
                    /*this should only be true in the case of link local bridge*/
                    return new ExecutionResult(false, "unable to find the vlan id for bridge " + pluggedVlanBr + " when attempting to set up" + pubVlan +
                            " on router " + routerName);
                } else if (pluggedVlanId.equals(pubVlan)) {
                    break;
                }
                devNum++;
            }

            pubIP.setNicDevId(devNum);

            return new ExecutionResult(true, "success");
        } catch (final LibvirtException e) {
            final String msg = "Ip SNAT failure due to " + e.toString();
            s_logger.error(msg, e);
            return new ExecutionResult(false, msg);
        }
    }

    protected ExecutionResult prepareNetworkElementCommand(final IpAssocVpcCommand cmd) {
        Connect conn;
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);

        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            final IpAddressTO[] ips = cmd.getIpAddresses();
            Integer devNum = 0;
            final Map<String, Integer> broadcastUriToNicNum = new HashMap<String, Integer>();
            final List<InterfaceDef> pluggedNics = getInterfaces(conn, routerName);

            for (final InterfaceDef pluggedNic : pluggedNics) {
                final String pluggedVlan = pluggedNic.getBrName();
                if (pluggedVlan.equalsIgnoreCase(_linkLocalBridgeName)) {
                    broadcastUriToNicNum.put("LinkLocal", devNum);
                } else if (pluggedVlan.equalsIgnoreCase(_publicBridgeName) || pluggedVlan.equalsIgnoreCase(_privBridgeName) ||
                        pluggedVlan.equalsIgnoreCase(_guestBridgeName)) {
                    broadcastUriToNicNum.put(BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED).toString(), devNum);
                } else {
                    broadcastUriToNicNum.put(getBroadcastUriFromBridge(pluggedVlan), devNum);
                }
                devNum++;
            }

            for (final IpAddressTO ip : ips) {
                ip.setNicDevId(broadcastUriToNicNum.get(ip.getBroadcastUri()));
            }

            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            s_logger.error("Ip Assoc failure on applying one ip due to exception:  ", e);
            return new ExecutionResult(false, e.getMessage());
        }
    }

    public ExecutionResult prepareNetworkElementCommand(final IpAssocCommand cmd) {
        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        final String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        Connect conn;
        try {
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            final List<InterfaceDef> nics = getInterfaces(conn, routerName);
            final Map<String, Integer> broadcastUriAllocatedToVM = new HashMap<String, Integer>();
            Integer nicPos = 0;
            for (final InterfaceDef nic : nics) {
                if (nic.getBrName().equalsIgnoreCase(_linkLocalBridgeName)) {
                    broadcastUriAllocatedToVM.put("LinkLocal", nicPos);
                } else {
                    if (nic.getBrName().equalsIgnoreCase(_publicBridgeName) || nic.getBrName().equalsIgnoreCase(_privBridgeName) ||
                            nic.getBrName().equalsIgnoreCase(_guestBridgeName)) {
                        broadcastUriAllocatedToVM.put(BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED).toString(), nicPos);
                    } else {
                        final String broadcastUri = getBroadcastUriFromBridge(nic.getBrName());
                        broadcastUriAllocatedToVM.put(broadcastUri, nicPos);
                    }
                }
                nicPos++;
            }
            final IpAddressTO[] ips = cmd.getIpAddresses();
            int nicNum = 0;
            for (final IpAddressTO ip : ips) {
                boolean newNic = false;
                if (!broadcastUriAllocatedToVM.containsKey(ip.getBroadcastUri())) {
                    /* plug a vif into router */
                    VifHotPlug(conn, routerName, ip.getBroadcastUri(), ip.getVifMacAddress());
                    broadcastUriAllocatedToVM.put(ip.getBroadcastUri(), nicPos++);
                    newNic = true;
                }
                nicNum = broadcastUriAllocatedToVM.get(ip.getBroadcastUri());
                networkUsage(routerIp, "addVif", "eth" + nicNum);

                ip.setNicDevId(nicNum);
                ip.setNewNic(newNic);
            }
            return new ExecutionResult(true, null);
        } catch (final LibvirtException e) {
            s_logger.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        } catch (final InternalErrorException e) {
            s_logger.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        }
    }

    protected ExecutionResult cleanupNetworkElementCommand(final IpAssocCommand cmd) {

        final String routerName = cmd.getAccessDetail(NetworkElementCommand.ROUTER_NAME);
        final String routerIp = cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP);
        Connect conn;


        try{
            conn = LibvirtConnection.getConnectionByVmName(routerName);
            final List<InterfaceDef> nics = getInterfaces(conn, routerName);
            final Map<String, Integer> broadcastUriAllocatedToVM = new HashMap<String, Integer>();

            Integer nicPos = 0;
            for (final InterfaceDef nic : nics) {
                if (nic.getBrName().equalsIgnoreCase(_linkLocalBridgeName)) {
                    broadcastUriAllocatedToVM.put("LinkLocal", nicPos);
                } else {
                    if (nic.getBrName().equalsIgnoreCase(_publicBridgeName) || nic.getBrName().equalsIgnoreCase(_privBridgeName) ||
                            nic.getBrName().equalsIgnoreCase(_guestBridgeName)) {
                        broadcastUriAllocatedToVM.put(BroadcastDomainType.Vlan.toUri(Vlan.UNTAGGED).toString(), nicPos);
                    } else {
                        final String broadcastUri = getBroadcastUriFromBridge(nic.getBrName());
                        broadcastUriAllocatedToVM.put(broadcastUri, nicPos);
                    }
                }
                nicPos++;
            }

            final IpAddressTO[] ips = cmd.getIpAddresses();
            final int numOfIps = ips.length;
            int nicNum = 0;
            for (final IpAddressTO ip : ips) {

                if (!broadcastUriAllocatedToVM.containsKey(ip.getBroadcastUri())) {
                    /* plug a vif into router */
                    VifHotPlug(conn, routerName, ip.getBroadcastUri(), ip.getVifMacAddress());
                    broadcastUriAllocatedToVM.put(ip.getBroadcastUri(), nicPos++);
                }
                nicNum = broadcastUriAllocatedToVM.get(ip.getBroadcastUri());

                if (numOfIps == 1 && !ip.isAdd()) {
                    vifHotUnPlug(conn, routerName, ip.getVifMacAddress());
                    networkUsage(routerIp, "deleteVif", "eth" + nicNum);
                }
            }

        } catch (final LibvirtException e) {
            s_logger.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        } catch (final InternalErrorException e) {
            s_logger.error("ipassoccmd failed", e);
            return new ExecutionResult(false, e.getMessage());
        }

        return new ExecutionResult(true, null);
    }

    protected PowerState convertToPowerState(final DomainState ps) {
        final PowerState state = s_powerStatesTable.get(ps);
        return state == null ? PowerState.PowerUnknown : state;
    }

    public PowerState getVmState(final Connect conn, final String vmName) {
        int retry = 3;
        Domain vms = null;
        while (retry-- > 0) {
            try {
                vms = conn.domainLookupByName(vmName);
                final PowerState s = convertToPowerState(vms.getInfo().state);
                return s;
            } catch (final LibvirtException e) {
                s_logger.warn("Can't get vm state " + vmName + e.getMessage() + "retry:" + retry);
            } finally {
                try {
                    if (vms != null) {
                        vms.free();
                    }
                } catch (final LibvirtException l) {
                    s_logger.trace("Ignoring libvirt error.", l);
                }
            }
        }
        return PowerState.PowerOff;
    }

    public String networkUsage(final String privateIpAddress, final String option, final String vif) {
        final Script getUsage = new Script(_routerProxyPath, s_logger);
        getUsage.add("netusage.sh");
        getUsage.add(privateIpAddress);
        if (option.equals("get")) {
            getUsage.add("-g");
        } else if (option.equals("create")) {
            getUsage.add("-c");
        } else if (option.equals("reset")) {
            getUsage.add("-r");
        } else if (option.equals("addVif")) {
            getUsage.add("-a", vif);
        } else if (option.equals("deleteVif")) {
            getUsage.add("-d", vif);
        }

        final OutputInterpreter.OneLineParser usageParser = new OutputInterpreter.OneLineParser();
        final String result = getUsage.execute(usageParser);
        if (result != null) {
            s_logger.debug("Failed to execute networkUsage:" + result);
            return null;
        }
        return usageParser.getLine();
    }

    public long[] getNetworkStats(final String privateIP) {
        final String result = networkUsage(privateIP, "get", null);
        final long[] stats = new long[2];
        if (result != null) {
            final String[] splitResult = result.split(":");
            int i = 0;
            while (i < splitResult.length - 1) {
                stats[0] += Long.parseLong(splitResult[i++]);
                stats[1] += Long.parseLong(splitResult[i++]);
            }
        }
        return stats;
    }

    public String configureVPCNetworkUsage(final String privateIpAddress, final String publicIp, final String option, final String vpcCIDR) {
        final Script getUsage = new Script(_routerProxyPath, s_logger);
        getUsage.add("vpc_netusage.sh");
        getUsage.add(privateIpAddress);
        getUsage.add("-l", publicIp);

        if (option.equals("get")) {
            getUsage.add("-g");
        } else if (option.equals("create")) {
            getUsage.add("-c");
            getUsage.add("-v", vpcCIDR);
        } else if (option.equals("reset")) {
            getUsage.add("-r");
        } else if (option.equals("vpn")) {
            getUsage.add("-n");
        } else if (option.equals("remove")) {
            getUsage.add("-d");
        }

        final OutputInterpreter.OneLineParser usageParser = new OutputInterpreter.OneLineParser();
        final String result = getUsage.execute(usageParser);
        if (result != null) {
            s_logger.debug("Failed to execute VPCNetworkUsage:" + result);
            return null;
        }
        return usageParser.getLine();
    }

    public long[] getVPCNetworkStats(final String privateIP, final String publicIp, final String option) {
        final String result = configureVPCNetworkUsage(privateIP, publicIp, option, null);
        final long[] stats = new long[2];
        if (result != null) {
            final String[] splitResult = result.split(":");
            int i = 0;
            while (i < splitResult.length - 1) {
                stats[0] += Long.parseLong(splitResult[i++]);
                stats[1] += Long.parseLong(splitResult[i++]);
            }
        }
        return stats;
    }

    public void handleVmStartFailure(final Connect conn, final String vmName, final LibvirtVMDef vm) {
        if (vm != null && vm.getDevices() != null) {
            cleanupVMNetworks(conn, vm.getDevices().getInterfaces());
        }
    }

    protected String getUuid(String uuid) {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        } else {
            try {
                final UUID uuid2 = UUID.fromString(uuid);
                final String uuid3 = uuid2.toString();
                if (!uuid3.equals(uuid)) {
                    uuid = UUID.randomUUID().toString();
                }
            } catch (final IllegalArgumentException e) {
                uuid = UUID.randomUUID().toString();
            }
        }
        return uuid;
    }

    public LibvirtVMDef createVMFromSpec(final VirtualMachineTO vmTO) {
        final LibvirtVMDef vm = new LibvirtVMDef();
        vm.setDomainName(vmTO.getName());
        String uuid = vmTO.getUuid();
        uuid = getUuid(uuid);
        vm.setDomUUID(uuid);
        vm.setDomDescription(vmTO.getOs());
        vm.setPlatformEmulator(vmTO.getPlatformEmulator());

        final GuestDef guest = new GuestDef();

        if (HypervisorType.LXC == _hypervisorType && VirtualMachine.Type.User == vmTO.getType()) {
            // LXC domain is only valid for user VMs. Use KVM for system VMs.
            guest.setGuestType(GuestDef.GuestType.LXC);
            vm.setHvsType(HypervisorType.LXC.toString().toLowerCase());
        } else {
            guest.setGuestType(GuestDef.GuestType.KVM);
            vm.setHvsType(HypervisorType.KVM.toString().toLowerCase());
            vm.setLibvirtVersion(_hypervisorLibvirtVersion);
            vm.setQemuVersion(_hypervisorQemuVersion);
        }
        guest.setGuestArch(vmTO.getArch());
        guest.setMachineType("pc");
        guest.setUuid(uuid);
        guest.setBootOrder(GuestDef.BootOrder.CDROM);
        guest.setBootOrder(GuestDef.BootOrder.HARDISK);

        vm.addComp(guest);

        final GuestResourceDef grd = new GuestResourceDef();

        if (vmTO.getMinRam() != vmTO.getMaxRam() && !_noMemBalloon) {
            grd.setMemBalloning(true);
            grd.setCurrentMem(vmTO.getMinRam() / 1024);
            grd.setMemorySize(vmTO.getMaxRam() / 1024);
        } else {
            grd.setMemorySize(vmTO.getMaxRam() / 1024);
        }
        final int vcpus = vmTO.getCpus();
        grd.setVcpuNum(vcpus);
        vm.addComp(grd);

        final CpuModeDef cmd = new CpuModeDef();
        cmd.setMode(_guestCpuMode);
        cmd.setModel(_guestCpuModel);
        if (vmTO.getType() == VirtualMachine.Type.User) {
            cmd.setFeatures(_cpuFeatures);
        }
        // multi cores per socket, for larger core configs
        if (vcpus % 6 == 0) {
            final int sockets = vcpus / 6;
            cmd.setTopology(6, sockets);
        } else if (vcpus % 4 == 0) {
            final int sockets = vcpus / 4;
            cmd.setTopology(4, sockets);
        }
        vm.addComp(cmd);

        if (_hypervisorLibvirtVersion >= 9000) {
            final CpuTuneDef ctd = new CpuTuneDef();
            /**
             A 4.0.X/4.1.X management server doesn't send the correct JSON
             command for getMinSpeed, it only sends a 'speed' field.

             So if getMinSpeed() returns null we fall back to getSpeed().

             This way a >4.1 agent can work communicate a <=4.1 management server

             This change is due to the overcommit feature in 4.2
             */
            if (vmTO.getMinSpeed() != null) {
                ctd.setShares(vmTO.getCpus() * vmTO.getMinSpeed());
            } else {
                ctd.setShares(vmTO.getCpus() * vmTO.getSpeed());
            }
            vm.addComp(ctd);
        }

        final FeaturesDef features = new FeaturesDef();
        features.addFeatures("pae");
        features.addFeatures("apic");
        features.addFeatures("acpi");
        //for rhel 6.5 and above, hyperv enlightment feature is added
        /*
         * if (vmTO.getOs().contains("Windows Server 2008") && hostOsVersion != null && ((hostOsVersion.first() == 6 && hostOsVersion.second() >= 5) || (hostOsVersion.first() >= 7))) {
         *    LibvirtVMDef.HyperVEnlightenmentFeatureDef hyv = new LibvirtVMDef.HyperVEnlightenmentFeatureDef();
         *    hyv.setRelaxed(true);
         *    features.addHyperVFeature(hyv);
         * }
         */
        vm.addComp(features);

        final TermPolicy term = new TermPolicy();
        term.setCrashPolicy("destroy");
        term.setPowerOffPolicy("destroy");
        term.setRebootPolicy("restart");
        vm.addComp(term);

        final ClockDef clock = new ClockDef();
        if (vmTO.getOs().startsWith("Windows")) {
            clock.setClockOffset(ClockDef.ClockOffset.LOCALTIME);
            clock.setTimer("rtc", "catchup", null);
        } else if (vmTO.getType() != VirtualMachine.Type.User || isGuestPVEnabled(vmTO.getOs())) {
            if (_hypervisorLibvirtVersion >= 9 * 1000 + 10) {
                clock.setTimer("kvmclock", null, null, _noKvmClock);
            }
        }

        vm.addComp(clock);

        final DevicesDef devices = new DevicesDef();
        devices.setEmulatorPath(_hypervisorPath);
        devices.setGuestType(guest.getGuestType());

        final SerialDef serial = new SerialDef("pty", null, (short)0);
        devices.addDevice(serial);

        if (vmTO.getType() != VirtualMachine.Type.User) {
            final VirtioSerialDef vserial = new VirtioSerialDef(vmTO.getName(), null);
            devices.addDevice(vserial);
        }

        final VideoDef videoCard = new VideoDef(_videoHw, _videoRam);
        devices.addDevice(videoCard);

        final ConsoleDef console = new ConsoleDef("pty", null, null, (short)0);
        devices.addDevice(console);

        //add the VNC port passwd here, get the passwd from the vmInstance.
        final String passwd = vmTO.getVncPassword();
        final GraphicDef grap = new GraphicDef("vnc", (short)0, true, vmTO.getVncAddr(), passwd, null);
        devices.addDevice(grap);

        final InputDef input = new InputDef("tablet", "usb");
        devices.addDevice(input);

        vm.addComp(devices);

        return vm;
    }

    public void createVifs(final VirtualMachineTO vmSpec, final LibvirtVMDef vm) throws InternalErrorException, LibvirtException {
        final NicTO[] nics = vmSpec.getNics();
        final Map <String, String> params = vmSpec.getDetails();
        String nicAdapter = "";
        if (params != null && params.get("nicAdapter") != null && !params.get("nicAdapter").isEmpty()) {
            nicAdapter = params.get("nicAdapter");
        }
        for (int i = 0; i < nics.length; i++) {
            for (final NicTO nic : vmSpec.getNics()) {
                if (nic.getDeviceId() == i) {
                    createVif(vm, nic, nicAdapter);
                }
            }
        }
    }

    public String getVolumePath(final Connect conn, final DiskTO volume) throws LibvirtException, URISyntaxException {
        final DataTO data = volume.getData();
        final DataStoreTO store = data.getDataStore();

        if (volume.getType() == Volume.Type.ISO && data.getPath() != null) {
            final NfsTO nfsStore = (NfsTO)store;
            final String isoPath = nfsStore.getUrl() + File.separator + data.getPath();
            final int index = isoPath.lastIndexOf("/");
            final String path = isoPath.substring(0, index);
            final String name = isoPath.substring(index + 1);
            final KVMStoragePool secondaryPool = _storagePoolMgr.getStoragePoolByURI(path);
            final KVMPhysicalDisk isoVol = secondaryPool.getPhysicalDisk(name);
            return isoVol.getPath();
        } else {
            return data.getPath();
        }
    }

    public void createVbd(final Connect conn, final VirtualMachineTO vmSpec, final String vmName, final LibvirtVMDef vm) throws InternalErrorException, LibvirtException, URISyntaxException {
        final List<DiskTO> disks = Arrays.asList(vmSpec.getDisks());
        Collections.sort(disks, new Comparator<DiskTO>() {
            @Override
            public int compare(final DiskTO arg0, final DiskTO arg1) {
                return arg0.getDiskSeq() > arg1.getDiskSeq() ? 1 : -1;
            }
        });

        for (final DiskTO volume : disks) {
            KVMPhysicalDisk physicalDisk = null;
            KVMStoragePool pool = null;
            final DataTO data = volume.getData();
            if (volume.getType() == Volume.Type.ISO && data.getPath() != null) {
                final NfsTO nfsStore = (NfsTO)data.getDataStore();
                final String volPath = nfsStore.getUrl() + File.separator + data.getPath();
                final int index = volPath.lastIndexOf("/");
                final String volDir = volPath.substring(0, index);
                final String volName = volPath.substring(index + 1);
                final KVMStoragePool secondaryStorage = _storagePoolMgr.getStoragePoolByURI(volDir);
                physicalDisk = secondaryStorage.getPhysicalDisk(volName);
            } else if (volume.getType() != Volume.Type.ISO) {
                final PrimaryDataStoreTO store = (PrimaryDataStoreTO)data.getDataStore();
                physicalDisk = _storagePoolMgr.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                pool = physicalDisk.getPool();
            }

            String volPath = null;
            if (physicalDisk != null) {
                volPath = physicalDisk.getPath();
            }

            // check for disk activity, if detected we should exit because vm is running elsewhere
            if (_diskActivityCheckEnabled && physicalDisk != null && physicalDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                s_logger.debug("Checking physical disk file at path " + volPath + " for disk activity to ensure vm is not running elsewhere");
                try {
                    HypervisorUtils.checkVolumeFileForActivity(volPath, _diskActivityCheckTimeoutSeconds, _diskActivityInactiveThresholdMilliseconds, _diskActivityCheckFileSizeMin);
                } catch (final IOException ex) {
                    throw new CloudRuntimeException("Unable to check physical disk file for activity", ex);
                }
                s_logger.debug("Disk activity check cleared");
            }

            // if params contains a rootDiskController key, use its value (this is what other HVs are doing)
            DiskDef.DiskBus diskBusType = null;
            final Map <String, String> params = vmSpec.getDetails();
            if (params != null && params.get("rootDiskController") != null && !params.get("rootDiskController").isEmpty()) {
                final String rootDiskController = params.get("rootDiskController");
                s_logger.debug("Passed custom disk bus " + rootDiskController);
                for (final DiskDef.DiskBus bus : DiskDef.DiskBus.values()) {
                    if (bus.toString().equalsIgnoreCase(rootDiskController)) {
                        s_logger.debug("Found matching enum for disk bus " + rootDiskController);
                        diskBusType = bus;
                        break;
                    }
                }
            }

            if (diskBusType == null) {
                diskBusType = getGuestDiskModel(vmSpec.getPlatformEmulator());
            }
            final DiskDef disk = new DiskDef();
            if (volume.getType() == Volume.Type.ISO) {
                if (volPath == null) {
                    /* Add iso as placeholder */
                    disk.defISODisk(null);
                } else {
                    disk.defISODisk(volPath);
                }
            } else {
                final int devId = volume.getDiskSeq().intValue();

                if (pool.getType() == StoragePoolType.RBD) {
                    /*
                            For RBD pools we use the secret mechanism in libvirt.
                            We store the secret under the UUID of the pool, that's why
                            we pass the pool's UUID as the authSecret
                     */
                    disk.defNetworkBasedDisk(physicalDisk.getPath().replace("rbd:", ""), pool.getSourceHost(), pool.getSourcePort(), pool.getAuthUserName(),
                            pool.getUuid(), devId, diskBusType, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                } else if (pool.getType() == StoragePoolType.Gluster) {
                    final String mountpoint = pool.getLocalPath();
                    final String path = physicalDisk.getPath();
                    final String glusterVolume = pool.getSourceDir().replace("/", "");
                    disk.defNetworkBasedDisk(glusterVolume + path.replace(mountpoint, ""), pool.getSourceHost(), pool.getSourcePort(), null,
                            null, devId, diskBusType, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (pool.getType() == StoragePoolType.CLVM || physicalDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    disk.defBlockBasedDisk(physicalDisk.getPath(), devId, diskBusType);
                } else {
                    if (volume.getType() == Volume.Type.DATADISK) {
                        disk.defFileBasedDisk(physicalDisk.getPath(), devId, DiskDef.DiskBus.VIRTIO, DiskDef.DiskFmtType.QCOW2);
                    } else {
                        disk.defFileBasedDisk(physicalDisk.getPath(), devId, diskBusType, DiskDef.DiskFmtType.QCOW2);
                    }

                }

            }

            if (data instanceof VolumeObjectTO) {
                final VolumeObjectTO volumeObjectTO = (VolumeObjectTO)data;
                disk.setSerial(diskUuidToSerial(volumeObjectTO.getUuid()));
                if (volumeObjectTO.getBytesReadRate() != null && volumeObjectTO.getBytesReadRate() > 0) {
                    disk.setBytesReadRate(volumeObjectTO.getBytesReadRate());
                }
                if (volumeObjectTO.getBytesWriteRate() != null && volumeObjectTO.getBytesWriteRate() > 0) {
                    disk.setBytesWriteRate(volumeObjectTO.getBytesWriteRate());
                }
                if (volumeObjectTO.getIopsReadRate() != null && volumeObjectTO.getIopsReadRate() > 0) {
                    disk.setIopsReadRate(volumeObjectTO.getIopsReadRate());
                }
                if (volumeObjectTO.getIopsWriteRate() != null && volumeObjectTO.getIopsWriteRate() > 0) {
                    disk.setIopsWriteRate(volumeObjectTO.getIopsWriteRate());
                }
                if (volumeObjectTO.getCacheMode() != null) {
                    disk.setCacheMode(DiskDef.DiskCacheMode.valueOf(volumeObjectTO.getCacheMode().toString().toUpperCase()));
                }
            }
            vm.getDevices().addDevice(disk);
        }

        if (vmSpec.getType() != VirtualMachine.Type.User) {
            if (_sysvmISOPath != null) {
                final DiskDef iso = new DiskDef();
                iso.defISODisk(_sysvmISOPath);
                vm.getDevices().addDevice(iso);
            }
        }

        // For LXC, find and add the root filesystem, rbd data disks
        if (HypervisorType.LXC.toString().toLowerCase().equals(vm.getHvsType())) {
            for (final DiskTO volume : disks) {
                final DataTO data = volume.getData();
                final PrimaryDataStoreTO store = (PrimaryDataStoreTO)data.getDataStore();
                if (volume.getType() == Volume.Type.ROOT) {
                    final KVMPhysicalDisk physicalDisk = _storagePoolMgr.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                    final FilesystemDef rootFs = new FilesystemDef(physicalDisk.getPath(), "/");
                    vm.getDevices().addDevice(rootFs);
                } else if (volume.getType() == Volume.Type.DATADISK) {
                    final KVMPhysicalDisk physicalDisk = _storagePoolMgr.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                    final KVMStoragePool pool = physicalDisk.getPool();
                    if(StoragePoolType.RBD.equals(pool.getType())) {
                        final int devId = volume.getDiskSeq().intValue();
                        final String device = mapRbdDevice(physicalDisk);
                        if (device != null) {
                            s_logger.debug("RBD device on host is: " + device);
                            final DiskDef diskdef = new DiskDef();
                            diskdef.defBlockBasedDisk(device, devId, DiskDef.DiskBus.VIRTIO);
                            diskdef.setQemuDriver(false);
                            vm.getDevices().addDevice(diskdef);
                        } else {
                            throw new InternalErrorException("Error while mapping RBD device on host");
                        }
                    }
                }
            }
        }

    }

    private void createVif(final LibvirtVMDef vm, final NicTO nic, final String nicAdapter) throws InternalErrorException, LibvirtException {

        if (nic.getType().equals(TrafficType.Guest) && nic.getBroadcastType().equals(BroadcastDomainType.Vsp)) {
            String vrIp = nic.getBroadcastUri().getPath().substring(1);
            vm.getMetaData().getMetadataNode(LibvirtVMDef.NuageExtensionDef.class).addNuageExtension(nic.getMac(), vrIp);

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("NIC with MAC " + nic.getMac() + " and BroadcastDomainType " + nic.getBroadcastType() + " in network(" + nic.getGateway() + "/" + nic.getNetmask()
                        + ") is " + nic.getType() + " traffic type. So, vsp-vr-ip " + vrIp + " is set in the metadata");
            }
        }

        vm.getDevices().addDevice(getVifDriver(nic.getType()).plug(nic, vm.getPlatformEmulator().toString(), nicAdapter).toString());
    }

    public boolean cleanupDisk(final DiskDef disk) {
        final String path = disk.getDiskPath();

        if (path == null) {
            s_logger.debug("Unable to clean up disk with null path (perhaps empty cdrom drive):" + disk);
            return false;
        }

        if (path.endsWith("systemvm.iso")) {
            // don't need to clean up system vm ISO as it's stored in local
            return true;
        }

        return _storagePoolMgr.disconnectPhysicalDiskByPath(path);
    }

    protected KVMStoragePoolManager getPoolManager() {
        return _storagePoolMgr;
    }

    public synchronized String attachOrDetachISO(final Connect conn, final String vmName, String isoPath, final boolean isAttach) throws LibvirtException, URISyntaxException,
    InternalErrorException {
        String isoXml = null;
        if (isoPath != null && isAttach) {
            final int index = isoPath.lastIndexOf("/");
            final String path = isoPath.substring(0, index);
            final String name = isoPath.substring(index + 1);
            final KVMStoragePool secondaryPool = _storagePoolMgr.getStoragePoolByURI(path);
            final KVMPhysicalDisk isoVol = secondaryPool.getPhysicalDisk(name);
            isoPath = isoVol.getPath();

            final DiskDef iso = new DiskDef();
            iso.defISODisk(isoPath);
            isoXml = iso.toString();
        } else {
            final DiskDef iso = new DiskDef();
            iso.defISODisk(null);
            isoXml = iso.toString();
        }

        final List<DiskDef> disks = getDisks(conn, vmName);
        final String result = attachOrDetachDevice(conn, true, vmName, isoXml);
        if (result == null && !isAttach) {
            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() == DiskDef.DeviceType.CDROM) {
                    cleanupDisk(disk);
                }
            }

        }
        return result;
    }

    public synchronized String attachOrDetachDisk(final Connect conn,
            final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk,
            final int devId, final Long bytesReadRate, final Long bytesWriteRate, final Long iopsReadRate, final Long iopsWriteRate, final String cacheMode) throws LibvirtException, InternalErrorException {
        List<DiskDef> disks = null;
        Domain dm = null;
        DiskDef diskdef = null;
        final KVMStoragePool attachingPool = attachingDisk.getPool();
        try {
            if (!attach) {
                dm = conn.domainLookupByName(vmName);
                final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
                final String xml = dm.getXMLDesc(0);
                parser.parseDomainXML(xml);
                disks = parser.getDisks();

                for (final DiskDef disk : disks) {
                    final String file = disk.getDiskPath();
                    if (file != null && file.equalsIgnoreCase(attachingDisk.getPath())) {
                        diskdef = disk;
                        break;
                    }
                }
                if (diskdef == null) {
                    throw new InternalErrorException("disk: " + attachingDisk.getPath() + " is not attached before");
                }
            } else {
                diskdef = new DiskDef();
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), attachingPool.getAuthUserName(),
                            attachingPool.getUuid(), devId, DiskDef.DiskBus.VIRTIO, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                } else if (attachingPool.getType() == StoragePoolType.Gluster) {
                    diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), null,
                            null, devId, DiskDef.DiskBus.VIRTIO, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                    diskdef.defFileBasedDisk(attachingDisk.getPath(), devId, DiskDef.DiskBus.VIRTIO, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, DiskDef.DiskBus.VIRTIO);
                }
                if (bytesReadRate != null && bytesReadRate > 0) {
                    diskdef.setBytesReadRate(bytesReadRate);
                }
                if (bytesWriteRate != null && bytesWriteRate > 0) {
                    diskdef.setBytesWriteRate(bytesWriteRate);
                }
                if (iopsReadRate != null && iopsReadRate > 0) {
                    diskdef.setIopsReadRate(iopsReadRate);
                }
                if (iopsWriteRate != null && iopsWriteRate > 0) {
                    diskdef.setIopsWriteRate(iopsWriteRate);
                }

                if (cacheMode != null) {
                    diskdef.setCacheMode(DiskDef.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
                }
            }

            final String xml = diskdef.toString();
            return attachOrDetachDevice(conn, attach, vmName, xml);
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    protected synchronized String attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final String xml) throws LibvirtException, InternalErrorException {
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            if (attach) {
                s_logger.debug("Attaching device: " + xml);
                dm.attachDevice(xml);
            } else {
                s_logger.debug("Detaching device: " + xml);
                dm.detachDevice(xml);
            }
        } catch (final LibvirtException e) {
            if (attach) {
                s_logger.warn("Failed to attach device to " + vmName + ": " + e.getMessage());
            } else {
                s_logger.warn("Failed to detach device from " + vmName + ": " + e.getMessage());
            }
            throw e;
        } finally {
            if (dm != null) {
                try {
                    dm.free();
                } catch (final LibvirtException l) {
                    s_logger.trace("Ignoring libvirt error.", l);
                }
            }
        }

        return null;
    }

    @Override
    public PingCommand getCurrentStatus(final long id) {

        if (!_canBridgeFirewall) {
            return new PingRoutingCommand(com.cloud.host.Host.Type.Routing, id, this.getHostVmStateReport());
        } else {
            final HashMap<String, Pair<Long, Long>> nwGrpStates = syncNetworkGroups(id);
            return new PingRoutingWithNwGroupsCommand(getType(), id, this.getHostVmStateReport(), nwGrpStates);
        }
    }

    @Override
    public Type getType() {
        return Type.Routing;
    }

    private Map<String, String> getVersionStrings() {
        final Script command = new Script(_versionstringpath, _timeout, s_logger);
        final KeyValueInterpreter kvi = new KeyValueInterpreter();
        final String result = command.execute(kvi);
        if (result == null) {
            return kvi.getKeyValues();
        } else {
            return new HashMap<String, String>(1);
        }
    }

    @Override
    public StartupCommand[] initialize() {

        final List<Object> info = getHostInfo();

        final StartupRoutingCommand cmd =
                new StartupRoutingCommand((Integer)info.get(0), (Long)info.get(1), (Long)info.get(2), (Long)info.get(4), (String)info.get(3), _hypervisorType,
                        RouterPrivateIpStrategy.HostLocal);
        cmd.setCpuSockets((Integer)info.get(5));
        fillNetworkInformation(cmd);
        _privateIp = cmd.getPrivateIpAddress();
        cmd.getHostDetails().putAll(getVersionStrings());
        cmd.setPool(_pool);
        cmd.setCluster(_clusterId);
        cmd.setGatewayIpAddress(_localGateway);
        cmd.setIqn(getIqn());

        StartupStorageCommand sscmd = null;
        try {

            final KVMStoragePool localStoragePool = _storagePoolMgr.createStoragePool(_localStorageUUID, "localhost", -1, _localStoragePath, "", StoragePoolType.Filesystem);
            final com.cloud.agent.api.StoragePoolInfo pi =
                    new com.cloud.agent.api.StoragePoolInfo(localStoragePool.getUuid(), cmd.getPrivateIpAddress(), _localStoragePath, _localStoragePath,
                            StoragePoolType.Filesystem, localStoragePool.getCapacity(), localStoragePool.getAvailable());

            sscmd = new StartupStorageCommand();
            sscmd.setPoolInfo(pi);
            sscmd.setGuid(pi.getUuid());
            sscmd.setDataCenter(_dcId);
            sscmd.setResourceType(Storage.StorageResourceType.STORAGE_POOL);
        } catch (final CloudRuntimeException e) {
            s_logger.debug("Unable to initialize local storage pool: " + e);
        }

        if (sscmd != null) {
            return new StartupCommand[] {cmd, sscmd};
        } else {
            return new StartupCommand[] {cmd};
        }
    }

    public String diskUuidToSerial(String uuid) {
        String uuidWithoutHyphen = uuid.replace("-","");
        return uuidWithoutHyphen.substring(0, Math.min(uuidWithoutHyphen.length(), 20));
    }

    private String getIqn() {
        try {
            final String textToFind = "InitiatorName=";

            final Script iScsiAdmCmd = new Script(true, "grep", 0, s_logger);

            iScsiAdmCmd.add(textToFind);
            iScsiAdmCmd.add("/etc/iscsi/initiatorname.iscsi");

            final OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();

            final String result = iScsiAdmCmd.execute(parser);

            if (result != null) {
                return null;
            }

            final String textFound = parser.getLine().trim();

            return textFound.substring(textToFind.length());
        }
        catch (final Exception ex) {
            return null;
        }
    }

    protected List<String> getAllVmNames(final Connect conn) {
        final ArrayList<String> la = new ArrayList<String>();
        try {
            final String names[] = conn.listDefinedDomains();
            for (int i = 0; i < names.length; i++) {
                la.add(names[i]);
            }
        } catch (final LibvirtException e) {
            s_logger.warn("Failed to list Defined domains", e);
        }

        int[] ids = null;
        try {
            ids = conn.listDomains();
        } catch (final LibvirtException e) {
            s_logger.warn("Failed to list domains", e);
            return la;
        }

        Domain dm = null;
        for (int i = 0; i < ids.length; i++) {
            try {
                dm = conn.domainLookupByID(ids[i]);
                la.add(dm.getName());
            } catch (final LibvirtException e) {
                s_logger.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    s_logger.trace("Ignoring libvirt error.", e);
                }
            }
        }

        return la;
    }

    private HashMap<String, HostVmStateReportEntry> getHostVmStateReport() {
        final HashMap<String, HostVmStateReportEntry> vmStates = new HashMap<String, HostVmStateReportEntry>();
        Connect conn = null;

        if (_hypervisorType == HypervisorType.LXC) {
            try {
                conn = LibvirtConnection.getConnectionByType(HypervisorType.LXC.toString());
                vmStates.putAll(getHostVmStateReport(conn));
                conn = LibvirtConnection.getConnectionByType(HypervisorType.KVM.toString());
                vmStates.putAll(getHostVmStateReport(conn));
            } catch (final LibvirtException e) {
                s_logger.debug("Failed to get connection: " + e.getMessage());
            }
        }

        if (_hypervisorType == HypervisorType.KVM) {
            try {
                conn = LibvirtConnection.getConnectionByType(HypervisorType.KVM.toString());
                vmStates.putAll(getHostVmStateReport(conn));
            } catch (final LibvirtException e) {
                s_logger.debug("Failed to get connection: " + e.getMessage());
            }
        }

        return vmStates;
    }

    private HashMap<String, HostVmStateReportEntry> getHostVmStateReport(final Connect conn) {
        final HashMap<String, HostVmStateReportEntry> vmStates = new HashMap<String, HostVmStateReportEntry>();

        String[] vms = null;
        int[] ids = null;

        try {
            ids = conn.listDomains();
        } catch (final LibvirtException e) {
            s_logger.warn("Unable to listDomains", e);
            return null;
        }
        try {
            vms = conn.listDefinedDomains();
        } catch (final LibvirtException e) {
            s_logger.warn("Unable to listDomains", e);
            return null;
        }

        Domain dm = null;
        for (int i = 0; i < ids.length; i++) {
            try {
                dm = conn.domainLookupByID(ids[i]);

                final DomainState ps = dm.getInfo().state;

                final PowerState state = convertToPowerState(ps);

                s_logger.trace("VM " + dm.getName() + ": powerstate = " + ps + "; vm state=" + state.toString());
                final String vmName = dm.getName();

                // TODO : for XS/KVM (host-based resource), we require to remove
                // VM completely from host, for some reason, KVM seems to still keep
                // Stopped VM around, to work-around that, reporting only powered-on VM
                //
                if (state == PowerState.PowerOn) {
                    vmStates.put(vmName, new HostVmStateReportEntry(state, conn.getHostName()));
                }
            } catch (final LibvirtException e) {
                s_logger.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    s_logger.trace("Ignoring libvirt error.", e);
                }
            }
        }

        for (int i = 0; i < vms.length; i++) {
            try {

                dm = conn.domainLookupByName(vms[i]);

                final DomainState ps = dm.getInfo().state;
                final PowerState state = convertToPowerState(ps);
                final String vmName = dm.getName();
                s_logger.trace("VM " + vmName + ": powerstate = " + ps + "; vm state=" + state.toString());

                // TODO : for XS/KVM (host-based resource), we require to remove
                // VM completely from host, for some reason, KVM seems to still keep
                // Stopped VM around, to work-around that, reporting only powered-on VM
                //
                if (state == PowerState.PowerOn) {
                    vmStates.put(vmName, new HostVmStateReportEntry(state, conn.getHostName()));
                }
            } catch (final LibvirtException e) {
                s_logger.warn("Unable to get vms", e);
            } finally {
                try {
                    if (dm != null) {
                        dm.free();
                    }
                } catch (final LibvirtException e) {
                    s_logger.trace("Ignoring libvirt error.", e);
                }
            }
        }

        return vmStates;
    }

    protected List<Object> getHostInfo() {
        final ArrayList<Object> info = new ArrayList<Object>();
        long speed = 0;
        long cpus = 0;
        long ram = 0;
        int cpuSockets = 0;
        String cap = null;
        try {
            final Connect conn = LibvirtConnection.getConnection();
            final NodeInfo hosts = conn.nodeInfo();
            speed = getCpuSpeed(hosts);

            cpuSockets = hosts.sockets;
            cpus = hosts.cpus;
            ram = hosts.memory * 1024L;
            final LibvirtCapXMLParser parser = new LibvirtCapXMLParser();
            parser.parseCapabilitiesXML(conn.getCapabilities());
            final ArrayList<String> oss = parser.getGuestOsType();
            for (final String s : oss) {
                /*
                 * Even host supports guest os type more than hvm, we only
                 * report hvm to management server
                 */
                if (s.equalsIgnoreCase("hvm")) {
                    cap = "hvm";
                }
            }
        } catch (final LibvirtException e) {
            s_logger.trace("Ignoring libvirt error.", e);
        }

        if (isSnapshotSupported()) {
            cap = cap + ",snapshot";
        }

        info.add((int)cpus);
        info.add(speed);
        // Report system's RAM as actual RAM minus host OS reserved RAM
        ram = ram - _dom0MinMem;
        info.add(ram);
        info.add(cap);
        info.add(_dom0MinMem);
        info.add(cpuSockets);
        s_logger.debug("cpus=" + cpus + ", speed=" + speed + ", ram=" + ram + ", _dom0MinMem=" + _dom0MinMem + ", cpu sockets=" + cpuSockets);

        return info;
    }

    protected static long getCpuSpeed(final NodeInfo nodeInfo) {
        try (final Reader reader = new FileReader(
                "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")) {
            return Long.parseLong(IOUtils.toString(reader).trim()) / 1000;
        } catch (IOException | NumberFormatException e) {
            s_logger.warn("Could not read cpuinfo_max_freq");
            return nodeInfo.mhz;
        }
    }

    public String rebootVM(final Connect conn, final String vmName) {
        Domain dm = null;
        String msg = null;
        try {
            dm = conn.domainLookupByName(vmName);
            // Get XML Dump including the secure information such as VNC password
            // By passing 1, or VIR_DOMAIN_XML_SECURE flag
            // https://libvirt.org/html/libvirt-libvirt-domain.html#virDomainXMLFlags
            String vmDef = dm.getXMLDesc(1);
            final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
            parser.parseDomainXML(vmDef);
            for (final InterfaceDef nic : parser.getInterfaces()) {
                if (nic.getNetType() == GuestNetType.BRIDGE && nic.getBrName().startsWith("cloudVirBr")) {
                    try {
                        final int vnetId = Integer.parseInt(nic.getBrName().replaceFirst("cloudVirBr", ""));
                        final String pifName = getPif(_guestBridgeName);
                        final String newBrName = "br" + pifName + "-" + vnetId;
                        vmDef = vmDef.replaceAll("'" + nic.getBrName() + "'", "'" + newBrName + "'");
                        s_logger.debug("VM bridge name is changed from " + nic.getBrName() + " to " + newBrName);
                    } catch (final NumberFormatException e) {
                        continue;
                    }
                }
            }
            s_logger.debug(vmDef);
            msg = stopVM(conn, vmName);
            msg = startVM(conn, vmName, vmDef);
            return null;
        } catch (final LibvirtException e) {
            s_logger.warn("Failed to create vm", e);
            msg = e.getMessage();
        } catch (final InternalErrorException e) {
            s_logger.warn("Failed to create vm", e);
            msg = e.getMessage();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                s_logger.trace("Ignoring libvirt error.", e);
            }
        }

        return msg;
    }

    public String stopVM(final Connect conn, final String vmName) {
        DomainState state = null;
        Domain dm = null;

        s_logger.debug("Try to stop the vm at first");
        String ret = stopVM(conn, vmName, false);
        if (ret == Script.ERR_TIMEOUT) {
            ret = stopVM(conn, vmName, true);
        } else if (ret != null) {
            /*
             * There is a race condition between libvirt and qemu: libvirt
             * listens on qemu's monitor fd. If qemu is shutdown, while libvirt
             * is reading on the fd, then libvirt will report an error.
             */
            /* Retry 3 times, to make sure we can get the vm's status */
            for (int i = 0; i < 3; i++) {
                try {
                    dm = conn.domainLookupByName(vmName);
                    state = dm.getInfo().state;
                    break;
                } catch (final LibvirtException e) {
                    s_logger.debug("Failed to get vm status:" + e.getMessage());
                } finally {
                    try {
                        if (dm != null) {
                            dm.free();
                        }
                    } catch (final LibvirtException l) {
                        s_logger.trace("Ignoring libvirt error.", l);
                    }
                }
            }

            if (state == null) {
                s_logger.debug("Can't get vm's status, assume it's dead already");
                return null;
            }

            if (state != DomainState.VIR_DOMAIN_SHUTOFF) {
                s_logger.debug("Try to destroy the vm");
                ret = stopVM(conn, vmName, true);
                if (ret != null) {
                    return ret;
                }
            }
        }

        return null;
    }

    protected String stopVM(final Connect conn, final String vmName, final boolean force) {
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            final int persist = dm.isPersistent();
            if (force) {
                if (dm.isActive() == 1) {
                    dm.destroy();
                    if (persist == 1) {
                        dm.undefine();
                    }
                }
            } else {
                if (dm.isActive() == 0) {
                    return null;
                }
                dm.shutdown();
                int retry = _stopTimeout / 2000;
                /* Wait for the domain gets into shutoff state. When it does
                   the dm object will no longer work, so we need to catch it. */
                try {
                    while (dm.isActive() == 1 && retry >= 0) {
                        Thread.sleep(2000);
                        retry--;
                    }
                } catch (final LibvirtException e) {
                    final String error = e.toString();
                    if (error.contains("Domain not found")) {
                        s_logger.debug("successfully shut down vm " + vmName);
                    } else {
                        s_logger.debug("Error in waiting for vm shutdown:" + error);
                    }
                }
                if (retry < 0) {
                    s_logger.warn("Timed out waiting for domain " + vmName + " to shutdown gracefully");
                    return Script.ERR_TIMEOUT;
                } else {
                    if (persist == 1) {
                        dm.undefine();
                    }
                }
            }
        } catch (final LibvirtException e) {
            if (e.getMessage().contains("Domain not found")) {
                s_logger.debug("VM " + vmName + " doesn't exist, no need to stop it");
                return null;
            }
            s_logger.debug("Failed to stop VM :" + vmName + " :", e);
            return e.getMessage();
        } catch (final InterruptedException ie) {
            s_logger.debug("Interrupted sleep");
            return ie.getMessage();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                s_logger.trace("Ignoring libvirt error.", e);
            }
        }

        return null;
    }

    public Integer getVncPort(final Connect conn, final String vmName) throws LibvirtException {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            final String xmlDesc = dm.getXMLDesc(0);
            parser.parseDomainXML(xmlDesc);
            return parser.getVncPort();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException l) {
                s_logger.trace("Ignoring libvirt error.", l);
            }
        }
    }

    private boolean IsHVMEnabled(final Connect conn) {
        final LibvirtCapXMLParser parser = new LibvirtCapXMLParser();
        try {
            parser.parseCapabilitiesXML(conn.getCapabilities());
            final ArrayList<String> osTypes = parser.getGuestOsType();
            for (final String o : osTypes) {
                if (o.equalsIgnoreCase("hvm")) {
                    return true;
                }
            }
        } catch (final LibvirtException e) {
            s_logger.trace("Ignoring libvirt error.", e);
        }
        return false;
    }

    private String getHypervisorPath(final Connect conn) {
        final LibvirtCapXMLParser parser = new LibvirtCapXMLParser();
        try {
            parser.parseCapabilitiesXML(conn.getCapabilities());
        } catch (final LibvirtException e) {
            s_logger.debug(e.getMessage());
        }
        return parser.getEmulator();
    }

    boolean isGuestPVEnabled(final String guestOSName) {
        if (guestOSName == null) {
            return false;
        }
        if (guestOSName.startsWith("Ubuntu") || guestOSName.startsWith("Fedora 13") || guestOSName.startsWith("Fedora 12") || guestOSName.startsWith("Fedora 11") ||
                guestOSName.startsWith("Fedora 10") || guestOSName.startsWith("Fedora 9") || guestOSName.startsWith("CentOS 5.3") || guestOSName.startsWith("CentOS 5.4") ||
                guestOSName.startsWith("CentOS 5.5") || guestOSName.startsWith("CentOS") || guestOSName.startsWith("Fedora") ||
                guestOSName.startsWith("Red Hat Enterprise Linux 5.3") || guestOSName.startsWith("Red Hat Enterprise Linux 5.4") ||
                guestOSName.startsWith("Red Hat Enterprise Linux 5.5") || guestOSName.startsWith("Red Hat Enterprise Linux 6") || guestOSName.startsWith("Debian GNU/Linux") ||
                guestOSName.startsWith("FreeBSD 10") || guestOSName.startsWith("Oracle") || guestOSName.startsWith("Other PV")) {
            return true;
        } else {
            return false;
        }
    }

    public boolean isCentosHost() {
        if (_hvVersion <= 9) {
            return true;
        } else {
            return false;
        }
    }

    private DiskDef.DiskBus getGuestDiskModel(final String platformEmulator) {
        if (isGuestPVEnabled(platformEmulator)) {
            return DiskDef.DiskBus.VIRTIO;
        } else {
            return DiskDef.DiskBus.IDE;
        }
    }

    private void cleanupVMNetworks(final Connect conn, final List<InterfaceDef> nics) {
        if (nics != null) {
            for (final InterfaceDef nic : nics) {
                for (final VifDriver vifDriver : getAllVifDrivers()) {
                    vifDriver.unplug(nic);
                }
            }
        }
    }

    public Domain getDomain(final Connect conn, final String vmName) throws LibvirtException {
        return conn.domainLookupByName(vmName);
    }

    public List<InterfaceDef> getInterfaces(final Connect conn, final String vmName) {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            parser.parseDomainXML(dm.getXMLDesc(0));
            return parser.getInterfaces();

        } catch (final LibvirtException e) {
            s_logger.debug("Failed to get dom xml: " + e.toString());
            return new ArrayList<InterfaceDef>();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                s_logger.trace("Ignoring libvirt error.", e);
            }
        }
    }

    public List<DiskDef> getDisks(final Connect conn, final String vmName) {
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        Domain dm = null;
        try {
            dm = conn.domainLookupByName(vmName);
            parser.parseDomainXML(dm.getXMLDesc(0));
            return parser.getDisks();

        } catch (final LibvirtException e) {
            s_logger.debug("Failed to get dom xml: " + e.toString());
            return new ArrayList<DiskDef>();
        } finally {
            try {
                if (dm != null) {
                    dm.free();
                }
            } catch (final LibvirtException e) {
                s_logger.trace("Ignoring libvirt error.", e);
            }
        }
    }

    private String executeBashScript(final String script) {
        final Script command = new Script("/bin/bash", _timeout, s_logger);
        command.add("-c");
        command.add(script);
        return command.execute();
    }

    public List<VmDiskStatsEntry> getVmDiskStat(final Connect conn, final String vmName) throws LibvirtException {
        Domain dm = null;
        try {
            dm = getDomain(conn, vmName);

            final List<VmDiskStatsEntry> stats = new ArrayList<VmDiskStatsEntry>();

            final List<DiskDef> disks = getDisks(conn, vmName);

            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() != DeviceType.DISK) {
                    break;
                }
                final DomainBlockStats blockStats = dm.blockStats(disk.getDiskLabel());
                final String path = disk.getDiskPath(); // for example, path = /mnt/pool_uuid/disk_path/
                String diskPath = null;
                if (path != null) {
                    final String[] token = path.split("/");
                    if (token.length > 3) {
                        diskPath = token[3];
                        final VmDiskStatsEntry stat = new VmDiskStatsEntry(vmName, diskPath, blockStats.wr_req, blockStats.rd_req, blockStats.wr_bytes, blockStats.rd_bytes);
                        stats.add(stat);
                    }
                }
            }

            return stats;
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    private class VmStats {
        long _usedTime;
        long _tx;
        long _rx;
        long _ioRead;
        long _ioWrote;
        long _bytesRead;
        long _bytesWrote;
        Calendar _timestamp;
    }

    public VmStatsEntry getVmStat(final Connect conn, final String vmName) throws LibvirtException {
        Domain dm = null;
        try {
            dm = getDomain(conn, vmName);
            final DomainInfo info = dm.getInfo();

            final VmStatsEntry stats = new VmStatsEntry();
            stats.setNumCPUs(info.nrVirtCpu);
            stats.setEntityType("vm");

            /* get cpu utilization */
            VmStats oldStats = null;

            final Calendar now = Calendar.getInstance();

            oldStats = _vmStats.get(vmName);

            long elapsedTime = 0;
            if (oldStats != null) {
                elapsedTime = now.getTimeInMillis() - oldStats._timestamp.getTimeInMillis();
                double utilization = (info.cpuTime - oldStats._usedTime) / ((double)elapsedTime * 1000000);

                final NodeInfo node = conn.nodeInfo();
                stats.setHypervisorCPUs(node.cpus);
                utilization = utilization / node.cpus;
                if (utilization > 0) {
                    stats.setCPUUtilization(utilization * 100);
                }
            }

            /* get network stats */

            final List<InterfaceDef> vifs = getInterfaces(conn, vmName);
            long rx = 0;
            long tx = 0;
            for (final InterfaceDef vif : vifs) {
                final DomainInterfaceStats ifStats = dm.interfaceStats(vif.getDevName());
                rx += ifStats.rx_bytes;
                tx += ifStats.tx_bytes;
            }

            if (oldStats != null) {
                final double deltarx = rx - oldStats._rx;
                if (deltarx > 0) {
                    stats.setNetworkReadKBs(deltarx / 1024);
                }
                final double deltatx = tx - oldStats._tx;
                if (deltatx > 0) {
                    stats.setNetworkWriteKBs(deltatx / 1024);
                }
            }

            /* get disk stats */
            final List<DiskDef> disks = getDisks(conn, vmName);
            long io_rd = 0;
            long io_wr = 0;
            long bytes_rd = 0;
            long bytes_wr = 0;
            for (final DiskDef disk : disks) {
                final DomainBlockStats blockStats = dm.blockStats(disk.getDiskLabel());
                io_rd += blockStats.rd_req;
                io_wr += blockStats.wr_req;
                bytes_rd += blockStats.rd_bytes;
                bytes_wr += blockStats.wr_bytes;
            }

            if (oldStats != null) {
                final long deltaiord = io_rd - oldStats._ioRead;
                if (deltaiord > 0) {
                    stats.setDiskReadIOs(deltaiord);
                }
                final long deltaiowr = io_wr - oldStats._ioWrote;
                if (deltaiowr > 0) {
                    stats.setDiskWriteIOs(deltaiowr);
                }
                final double deltabytesrd = bytes_rd - oldStats._bytesRead;
                if (deltabytesrd > 0) {
                    stats.setDiskReadKBs(deltabytesrd / 1024);
                }
                final double deltabyteswr = bytes_wr - oldStats._bytesWrote;
                if (deltabyteswr > 0) {
                    stats.setDiskWriteKBs(deltabyteswr / 1024);
                }
            }

            /* save to Hashmap */
            final VmStats newStat = new VmStats();
            newStat._usedTime = info.cpuTime;
            newStat._rx = rx;
            newStat._tx = tx;
            newStat._ioRead = io_rd;
            newStat._ioWrote = io_wr;
            newStat._bytesRead = bytes_rd;
            newStat._bytesWrote = bytes_wr;
            newStat._timestamp = now;
            _vmStats.put(vmName, newStat);
            return stats;
        } finally {
            if (dm != null) {
                dm.free();
            }
        }
    }

    private boolean canBridgeFirewall(final String prvNic) {
        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("can_bridge_firewall");
        cmd.add(prvNic);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean destroyNetworkRulesForVM(final Connect conn, final String vmName) {
        if (!_canBridgeFirewall) {
            return false;
        }
        String vif = null;
        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() > 0) {
            final InterfaceDef intf = intfs.get(0);
            vif = intf.getDevName();
        }
        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("destroy_network_rules_for_vm");
        cmd.add("--vmname", vmName);
        if (vif != null) {
            cmd.add("--vif", vif);
        }
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean defaultNetworkRules(final Connect conn, final String vmName, final NicTO nic, final Long vmId, final String secIpStr) {
        if (!_canBridgeFirewall) {
            return false;
        }

        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() == 0 || intfs.size() < nic.getDeviceId()) {
            return false;
        }

        final InterfaceDef intf = intfs.get(nic.getDeviceId());
        final String brname = intf.getBrName();
        final String vif = intf.getDevName();

        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("default_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId.toString());
        if (nic.getIp() != null) {
            cmd.add("--vmip", nic.getIp());
        }
        cmd.add("--vmmac", nic.getMac());
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        cmd.add("--nicsecips", secIpStr);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    protected boolean post_default_network_rules(final Connect conn, final String vmName, final NicTO nic, final Long vmId, final InetAddress dhcpServerIp, final String hostIp, final String hostMacAddr) {
        if (!_canBridgeFirewall) {
            return false;
        }

        final List<InterfaceDef> intfs = getInterfaces(conn, vmName);
        if (intfs.size() < nic.getDeviceId()) {
            return false;
        }

        final InterfaceDef intf = intfs.get(nic.getDeviceId());
        final String brname = intf.getBrName();
        final String vif = intf.getDevName();

        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("post_default_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId.toString());
        cmd.add("--vmip", nic.getIp());
        cmd.add("--vmmac", nic.getMac());
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        if (dhcpServerIp != null) {
            cmd.add("--dhcpSvr", dhcpServerIp.getHostAddress());
        }

        cmd.add("--hostIp", hostIp);
        cmd.add("--hostMacAddr", hostMacAddr);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean configureDefaultNetworkRulesForSystemVm(final Connect conn, final String vmName) {
        if (!_canBridgeFirewall) {
            return false;
        }

        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("default_network_rules_systemvm");
        cmd.add("--vmname", vmName);
        cmd.add("--localbrname", _linkLocalBridgeName);
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean addNetworkRules(final String vmName, final String vmId, final String guestIP, final String sig, final String seq, final String mac, final String rules, final String vif, final String brname,
            final String secIps) {
        if (!_canBridgeFirewall) {
            return false;
        }

        final String newRules = rules.replace(" ", ";");
        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("add_network_rules");
        cmd.add("--vmname", vmName);
        cmd.add("--vmid", vmId);
        cmd.add("--vmip", guestIP);
        cmd.add("--sig", sig);
        cmd.add("--seq", seq);
        cmd.add("--vmmac", mac);
        cmd.add("--vif", vif);
        cmd.add("--brname", brname);
        cmd.add("--nicsecips", secIps);
        if (newRules != null && !newRules.isEmpty()) {
            cmd.add("--rules", newRules);
        }
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean configureNetworkRulesVMSecondaryIP(final Connect conn, final String vmName, final String secIp, final String action) {

        if (!_canBridgeFirewall) {
            return false;
        }

        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("network_rules_vmSecondaryIp");
        cmd.add("--vmname", vmName);
        cmd.add("--nicsecips", secIp);
        cmd.add("--action", action);

        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public boolean cleanupRules() {
        if (!_canBridgeFirewall) {
            return false;
        }
        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("cleanup_rules");
        final String result = cmd.execute();
        if (result != null) {
            return false;
        }
        return true;
    }

    public String getRuleLogsForVms() {
        final Script cmd = new Script(_securityGroupPath, _timeout, s_logger);
        cmd.add("get_rule_logs_for_vms");
        final OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        final String result = cmd.execute(parser);
        if (result == null) {
            return parser.getLine();
        }
        return null;
    }

    private HashMap<String, Pair<Long, Long>> syncNetworkGroups(final long id) {
        final HashMap<String, Pair<Long, Long>> states = new HashMap<String, Pair<Long, Long>>();

        final String result = getRuleLogsForVms();
        s_logger.trace("syncNetworkGroups: id=" + id + " got: " + result);
        final String[] rulelogs = result != null ? result.split(";") : new String[0];
        for (final String rulesforvm : rulelogs) {
            final String[] log = rulesforvm.split(",");
            if (log.length != 6) {
                continue;
            }
            try {
                states.put(log[0], new Pair<Long, Long>(Long.parseLong(log[1]), Long.parseLong(log[5])));
            } catch (final NumberFormatException nfe) {
                states.put(log[0], new Pair<Long, Long>(-1L, -1L));
            }
        }
        return states;
    }

    /* online snapshot supported by enhanced qemu-kvm */
    private boolean isSnapshotSupported() {
        final String result = executeBashScript("qemu-img --help|grep convert");
        if (result != null) {
            return false;
        } else {
            return true;
        }
    }

    public Pair<Double, Double> getNicStats(final String nicName) {
        return new Pair<Double, Double>(readDouble(nicName, "rx_bytes"), readDouble(nicName, "tx_bytes"));
    }

    static double readDouble(final String nicName, final String fileName) {
        final String path = "/sys/class/net/" + nicName + "/statistics/" + fileName;
        try {
            return Double.parseDouble(FileUtils.readFileToString(new File(path)));
        } catch (final IOException ioe) {
            s_logger.warn("Failed to read the " + fileName + " for " + nicName + " from " + path, ioe);
            return 0.0;
        }
    }

    private String prettyVersion(final long version) {
        final long major = version / 1000000;
        final long minor = version % 1000000 / 1000;
        final long release = version % 1000000 % 1000;
        return major + "." + minor + "." + release;
    }

    @Override
    public void setName(final String name) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setConfigParams(final Map<String, Object> params) {
        // TODO Auto-generated method stub
    }

    @Override
    public Map<String, Object> getConfigParams() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getRunLevel() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setRunLevel(final int level) {
        // TODO Auto-generated method stub
    }

    public HypervisorType getHypervisorType(){
        return _hypervisorType;
    }

    public String mapRbdDevice(final KVMPhysicalDisk disk){
        final KVMStoragePool pool = disk.getPool();
        //Check if rbd image is already mapped
        final String[] splitPoolImage = disk.getPath().split("/");
        String device = Script.runSimpleBashScript("rbd showmapped | grep \""+splitPoolImage[0]+"[ ]*"+splitPoolImage[1]+"\" | grep -o \"[^ ]*[ ]*$\"");
        if(device == null) {
            //If not mapped, map and return mapped device
            Script.runSimpleBashScript("rbd map " + disk.getPath() + " --id " + pool.getAuthUserName());
            device = Script.runSimpleBashScript("rbd showmapped | grep \""+splitPoolImage[0]+"[ ]*"+splitPoolImage[1]+"\" | grep -o \"[^ ]*[ ]*$\"");
        }
        return device;
    }
}
