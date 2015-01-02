package com.cloud.hypervisor.ovm3.resources.helpers;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.log4j.Logger;
import org.apache.xmlrpc.XmlRpcException;

import com.cloud.agent.api.AttachVolumeAnswer;
import com.cloud.agent.api.AttachVolumeCommand;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.ovm3.objects.CloudStackPlugin;
import com.cloud.hypervisor.ovm3.objects.Connection;
import com.cloud.hypervisor.ovm3.objects.Ovm3ResourceException;
import com.cloud.hypervisor.ovm3.objects.OvmObject;
import com.cloud.hypervisor.ovm3.objects.Xen;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;

public class Ovm3VmSupport {
    private final Logger LOGGER = Logger.getLogger(Ovm3VmSupport.class);
    private OvmObject ovmObject = new OvmObject();
    private ResourceManager resourceMgr;
    private Connection c;
    private Ovm3HypervisorNetwork network;
    private Ovm3Configuration config;
    private Ovm3HypervisorSupport hypervisor;
    private Ovm3StoragePool pool;
    private final Map<String, Map<String, String>> vmStats = new ConcurrentHashMap<String, Map<String, String>>();
    public Ovm3VmSupport(Connection conn,
            Ovm3Configuration ovm3config,
            Ovm3HypervisorSupport ovm3hyper,
            Ovm3StoragePool ovm3sp,
            Ovm3HypervisorNetwork ovm3hvn) {
        c = conn;
        config = ovm3config;
        hypervisor = ovm3hyper;
        pool = ovm3sp;
        network = ovm3hvn;
    }
    public Boolean createVifs(Xen.Vm vm, VirtualMachineTO spec)
            throws Ovm3ResourceException {
        if (spec.getNics() != null) {
            NicTO[] nics = spec.getNics();
            return createVifs(vm, nics);
        } else {
            LOGGER.info("No nics for vm " + spec.getName());
            return false;
        }
    }

    private Boolean createVifs(Xen.Vm vm, NicTO[] nics)
            throws Ovm3ResourceException {
        for (NicTO nic : nics) {
            if (!createVif(vm, nic)) {
                return false;
            }
        }
        return true;
    }

    /* should add bitrates and latency... */
    private Boolean createVif(Xen.Vm vm, NicTO nic)
            throws Ovm3ResourceException {
        try {
            if (network.getNetwork(nic) != null) {
                LOGGER.debug("Adding vif " + nic.getDeviceId() + " " + " "
                        + nic.getMac() + " " + network.getNetwork(nic) + " to "
                        + vm.getVmName());
                vm.addVif(nic.getDeviceId(), network.getNetwork(nic), nic.getMac());
            } else {
                LOGGER.debug("Unable to add vif " + nic.getDeviceId()
                        + " no network for " + vm.getVmName());
                return false;
            }
        } catch (Exception e) {
            String msg = "Unable to add vif " + nic.getType() + " for "
                    + vm.getVmName() + " " + e.getMessage();
            LOGGER.debug(msg);
            throw new Ovm3ResourceException(msg);
        }
        return true;
    }
    /* TODO: Hot plugging harddisks... */
    public AttachVolumeAnswer execute(AttachVolumeCommand cmd) {
        return new AttachVolumeAnswer(cmd, "You must stop " + cmd.getVmName()
                + " first, Ovm3 doesn't support hotplug datadisk");
    }



    /* Migration should make sure both HVs are the same ? */
    public PrepareForMigrationAnswer execute(PrepareForMigrationCommand cmd) {
        VirtualMachineTO vm = cmd.getVirtualMachine();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Preparing host for migrating " + vm.getName());
        }
        NicTO[] nics = vm.getNics();
        try {
            for (NicTO nic : nics) {
                network.getNetwork(nic);
            }
            hypervisor.setVmState(vm.getName(), State.Migrating);
            LOGGER.debug("VM " + vm.getName() + " is in Migrating state");
            return new PrepareForMigrationAnswer(cmd);
        } catch (Ovm3ResourceException e) {
            LOGGER.error("Catch Exception " + e.getClass().getName()
                    + " prepare for migration failed due to: " + e.getMessage());
            return new PrepareForMigrationAnswer(cmd, e);
        }
    }

    /* do migrations of VMs in a simple way just inside a cluster for now */
    public MigrateAnswer execute(final MigrateCommand cmd) {
        final String vmName = cmd.getVmName();
        String destUuid = cmd.getHostGuid();
        String destIp = cmd.getDestinationIp();
        State state = State.Error;
        /*
         * TODO: figure out non pooled migration, works from CLI but not from
         * the agent... perhaps pause the VM and then migrate it ? for now just
         * stop the VM.
         */
        String msg = "Migrating " + vmName + " to " + destIp;
        LOGGER.info(msg);
        if (!config.getAgentInOvm3Cluster() && !config.getAgentInOvm3Pool()) {
            try {
                Xen xen = new Xen(c);
                Xen.Vm vm = xen.getRunningVmConfig(vmName);
                HostVO destHost = resourceMgr.findHostByGuid(destUuid);
                if (destHost == null) {
                    msg = "Unable to find migration target host in DB "
                            + destUuid + " with ip " + destIp;
                    LOGGER.info(msg);
                    return new MigrateAnswer(cmd, false, msg, null);
                }
                xen.stopVm(ovmObject.deDash(vm.getVmRootDiskPoolId()),
                        vm.getVmUuid());
                msg = destHost.toString();
                state = State.Stopping;
                return new MigrateAnswer(cmd, false, msg, null);
            } catch (Ovm3ResourceException e) {
                msg = "Unpooled VM Migrate of " + vmName + " to " + destUuid
                        + " failed due to: " + e.getMessage();
                LOGGER.debug(msg, e);
                return new MigrateAnswer(cmd, false, msg, null);
            } finally {
                /* shouldn't we just reinitialize completely as a last resort ? */
                hypervisor.setVmState(vmName, state);
            }
        } else {
            try {
                Xen xen = new Xen(c);
                Xen.Vm vm = xen.getRunningVmConfig(vmName);
                if (vm == null) {
                    state = State.Stopped;
                    msg = vmName + " is no running on " + config.getAgentHostname();
                    return new MigrateAnswer(cmd, false, msg, null);
                }
                /* not a storage migration!!! */
                xen.migrateVm(ovmObject.deDash(vm.getVmRootDiskPoolId()),
                        vm.getVmUuid(), destIp);
                state = State.Stopping;
                msg = "Migration of " + vmName + " successfull";
                return new MigrateAnswer(cmd, true, msg, null);
            } catch (Ovm3ResourceException e) {
                msg = "Pooled VM Migrate" + ": Migration of " + vmName + " to "
                        + destIp + " failed due to " + e.getMessage();
                LOGGER.debug(msg, e);
                return new MigrateAnswer(cmd, false, msg, null);
            } finally {
                hypervisor.setVmState(vmName, state);
            }
        }
    }



    /*
     */
    public GetVncPortAnswer execute(GetVncPortCommand cmd) {
        try {
            Xen host = new Xen(c);
            Xen.Vm vm = host.getRunningVmConfig(cmd.getName());
            Integer vncPort = vm.getVncPort();
            LOGGER.debug("get vnc port for " + cmd.getName() + ": " + vncPort);
            return new GetVncPortAnswer(cmd, c.getIp(), vncPort);
        } catch (Ovm3ResourceException e) {
            LOGGER.debug("get vnc port for " + cmd.getName() + " failed", e);
            return new GetVncPortAnswer(cmd, e.getMessage());
        }
    }

    private VmStatsEntry getVmStat(String vmName) {
        CloudStackPlugin cSp = new CloudStackPlugin(c);
        Map<String, String> oleStats = vmStats.get(vmName);
        VmStatsEntry stats = new VmStatsEntry();
        Map<String, String> newStats;
        try {
            newStats = cSp.ovsDomUStats(vmName);
        } catch (Ovm3ResourceException e) {
            LOGGER.info("Unable to retrieve stats from " + vmName, e);
            return stats;
        }
        if (oleStats == null) {
            stats.setNumCPUs(1);
            stats.setNetworkReadKBs(0);
            stats.setNetworkWriteKBs(0);
            stats.setDiskReadKBs(0);
            stats.setDiskWriteKBs(0);
            stats.setDiskReadIOs(0);
            stats.setDiskWriteIOs(0);
            stats.setCPUUtilization(0);
            stats.setEntityType("vm");
        } else {
            /* beware of negatives ? */
            Integer cpus = Integer.parseInt(newStats.get("vcpus"));
            stats.setNumCPUs(Integer.parseInt(newStats.get(cpus)));
            stats.setNetworkReadKBs(Double.parseDouble(newStats.get("rx_bytes"))
                    - Double.parseDouble(oleStats.get("rx_bytes")));
            stats.setNetworkWriteKBs(Double.parseDouble(newStats
                    .get("tx_bytes"))
                    - Double.parseDouble(oleStats.get("tx_bytes")));
            stats.setDiskReadKBs(Double.parseDouble(newStats.get("rd_bytes"))
                    - Double.parseDouble(oleStats.get("rd_bytes")));
            stats.setDiskWriteKBs(Double.parseDouble(newStats.get("rw_bytes"))
                    - Double.parseDouble(oleStats.get("rw_bytes")));
            stats.setDiskReadIOs(Double.parseDouble(newStats.get("rd_ops"))
                    - Double.parseDouble(oleStats.get("rd_ops")));
            stats.setDiskWriteIOs(Double.parseDouble(newStats.get("rw_ops"))
                    - Double.parseDouble(oleStats.get("rw_ops")));
            Double dCpu = Double.parseDouble(newStats.get("cputime"))
                    - Double.parseDouble(oleStats.get("cputime"));
            Double dTime = Double.parseDouble(newStats.get("uptime"))
                    - Double.parseDouble(oleStats.get("uptime"));
            Double cpupct = dCpu / dTime * 100 * cpus;
            stats.setCPUUtilization(cpupct);
            stats.setEntityType("vm");
        }
        ((ConcurrentHashMap<String, Map<String, String>>) vmStats).replace(
                vmName, newStats);
        return stats;
    }

    public GetVmStatsAnswer execute(GetVmStatsCommand cmd) {
        List<String> vmNames = cmd.getVmNames();
        Map<String, VmStatsEntry> vmStatsNameMap = new HashMap<String, VmStatsEntry>();
        for (String vmName : vmNames) {
            VmStatsEntry e = getVmStat(vmName);
            vmStatsNameMap.put(vmName, e);
        }
        return new GetVmStatsAnswer(cmd,
                (HashMap<String, VmStatsEntry>) vmStatsNameMap);
    }
    /* This is not create for us, but really start */
    public boolean xstartVm(String repoId, String vmId) throws XmlRpcException {
        Xen host = new Xen(c);
        try {
            if (host.getRunningVmConfig(vmId) == null) {
                LOGGER.error("Create VM " + vmId + " first on " + c.getIp());
                return false;
            } else {
                LOGGER.info("VM " + vmId + " exists on " + c.getIp());
            }
            host.startVm(repoId, vmId);
        } catch (Exception e) {
            LOGGER.error("Failed to start VM " + vmId + " on " + c.getIp()
                    + " " + e.getMessage());
            return false;
        }
        return true;
    }

    /*
     * TODO: OVM already cleans stuff up, just not the extra bridges which we
     * don't want right now, as we'd have to keep a state table of which vlans
     * need to stay on the host!? A map with vlanid -> list-o-hosts
     */
    private void cleanupNetwork(List<String> vifs) throws XmlRpcException {
        /* peel out vif info for vlan stuff */
    }

    public void cleanup(Xen.Vm vm) {
        try {
            cleanupNetwork(vm.getVmVifs());
        } catch (XmlRpcException e) {
            LOGGER.info("Clean up network for " + vm.getVmName() + " failed", e);
        }
        String vmName = vm.getVmName();
        /* should become a single entity */
        vmStats.remove(vmName);
    }

    /*
     * Add rootdisk, datadisk and iso's
     */
    public Boolean createVbds(Xen.Vm vm, VirtualMachineTO spec) {
        if (spec.getDisks() == null) {
            LOGGER.info("No disks defined for " + vm.getVmName());
            return false;
        }
        for (DiskTO disk : spec.getDisks()) {
            try {
                if (disk.getType() == Volume.Type.ROOT) {
                    VolumeObjectTO vol = (VolumeObjectTO) disk.getData();
                    DataStoreTO ds = vol.getDataStore();
                    String dsk = vol.getPath() + "/" + vol.getUuid() + ".raw";
                    vm.addRootDisk(dsk);
                    /* TODO: needs to be replaced by rootdiskuuid? */
                    vm.setPrimaryPoolUuid(ds.getUuid());
                    LOGGER.debug("Adding root disk: " + dsk);
                } else if (disk.getType() == Volume.Type.ISO) {
                    DataTO isoTO = disk.getData();
                    if (isoTO.getPath() != null) {
                        TemplateObjectTO template = (TemplateObjectTO) isoTO;
                        DataStoreTO store = template.getDataStore();
                        if (!(store instanceof NfsTO)) {
                            throw new CloudRuntimeException(
                                    "unsupported protocol");
                        }
                        NfsTO nfsStore = (NfsTO) store;
                        String secPoolUuid = pool.setupSecondaryStorage(nfsStore
                                .getUrl());
                        String isoPath = config.getAgentSecStoragePath() + File.separator
                                + secPoolUuid + File.separator
                                + template.getPath();
                        vm.addIso(isoPath);
                        /* check if secondary storage is mounted */
                        LOGGER.debug("Adding ISO: " + isoPath);
                    }
                } else if (disk.getType() == Volume.Type.DATADISK) {
                    vm.addDataDisk(disk.getData().getPath());
                    LOGGER.debug("Adding data disk: "
                            + disk.getData().getPath());
                } else {
                    throw new CloudRuntimeException("Unknown disk type: "
                            + disk.getType());
                }
            } catch (Exception e) {
                LOGGER.debug("CreateVbds failed", e);
                throw new CloudRuntimeException("Exception" + e.getMessage(), e);
            }
        }
        return true;
    }

    /**
     * PlugNicAnswer: plug a network interface into a VM
     * @param cmd
     * @return
     */
    public PlugNicAnswer execute(PlugNicCommand cmd) {
        String vmName = cmd.getVmName();
        NicTO nic = cmd.getNic();
        try {
            Xen xen = new Xen(c);
            Xen.Vm vm = xen.getVmConfig(vmName);
            /* check running */
            if (vm == null) {
                return new PlugNicAnswer(cmd, false,
                        "Unable to execute PlugNicCommand due to missing VM");
            }
            // setup the NIC in the VM config.
            createVif(vm, nic);
            vm.setupVifs();

            // execute the change
            xen.configureVm(ovmObject.deDash(vm.getPrimaryPoolUuid()),
                    vm.getVmUuid());
        } catch (Ovm3ResourceException e) {
            return new PlugNicAnswer(cmd, false,
                    "Unable to execute PlugNicCommand due to " + e.toString());
        }
        return new PlugNicAnswer(cmd, true, "success");
    }

    /**
     * UnPlugNicAnswer: remove a nic from a VM
     * @param cmd
     * @return
     */
    public UnPlugNicAnswer execute(UnPlugNicCommand cmd) {
        return new UnPlugNicAnswer(cmd, true, "");
    }
}
