//
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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ConvertInstanceAnswer;
import com.cloud.agent.api.ConvertInstanceCommand;
import com.cloud.agent.api.to.RemoteInstanceTO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ResourceWrapper(handles =  ConvertInstanceCommand.class)
public class LibvirtConvertInstanceCommandWrapper extends CommandWrapper<ConvertInstanceCommand, Answer, LibvirtComputingResource> {

    private static final Logger s_logger = Logger.getLogger(LibvirtConvertInstanceCommandWrapper.class);

    private static final List<Hypervisor.HypervisorType> supportedInstanceConvertSourceHypervisors =
            List.of(Hypervisor.HypervisorType.VMware);

    @Override
    public Answer execute(ConvertInstanceCommand cmd, LibvirtComputingResource serverResource) {
        s_logger.info("Attempting to convert the instance %s from %s to KVM");

        RemoteInstanceTO sourceInstance = cmd.getSourceInstance();
        Hypervisor.HypervisorType sourceHypervisorType = sourceInstance.getHypervisorType();
        String sourceInstanceName = sourceInstance.getInstanceName();
        Hypervisor.HypervisorType destinationHypervisorType = cmd.getDestinationHypervisorType();
        List<String> destinationStoragePools = cmd.getDestinationStoragePools();
        String secondaryStorageUrl = cmd.getConversionTemporaryPath();
        int timeout = cmd.getWait() * 1000;

        if (destinationHypervisorType != Hypervisor.HypervisorType.KVM ||
                !supportedInstanceConvertSourceHypervisors.contains(sourceHypervisorType)) {
            String err = destinationHypervisorType != Hypervisor.HypervisorType.KVM ?
                    String.format("The destination hypervisor type is %s, KVM was expected, cannot handle it", destinationHypervisorType) :
                    String.format("The source hypervisor type %s is not supported for KVM conversion", sourceHypervisorType);
            s_logger.error(err);
            return new ConvertInstanceAnswer(cmd, false, err);
        }

        final KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();
        KVMStoragePool secondaryPool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl);

        s_logger.info(String.format("Attempting to convert the instance %s from %s to KVM",
                sourceInstanceName, sourceHypervisorType));
        final String convertInstanceUrl = getConvertInstanceUrl(sourceInstance);
        final String temporaryConvertUuid = UUID.randomUUID().toString();
        final String temporaryPasswordFilePath = createTemporaryPasswordFileAndRetrievePath(sourceInstance);
        final String temporaryConvertFolder = String.format("vmw-to-kvm-%s", temporaryConvertUuid);
        secondaryPool.createFolder(temporaryConvertFolder);
        final String temporaryConvertPath = String.format("%s/%s", secondaryPool.getLocalPath(), temporaryConvertFolder);

        try {
            Pair<Boolean, String> conversionResultPair = performInstanceConversion(convertInstanceUrl, sourceInstanceName, temporaryPasswordFilePath,
                    temporaryConvertPath, temporaryConvertUuid, timeout);
            boolean result = conversionResultPair.first();
            String output = conversionResultPair.second();
            if (!result) {
                String err = String.format("The virt-v2v conversion of the instance %s failed due to: %s",
                        sourceInstanceName, output);
                s_logger.error(err);
                return new ConvertInstanceAnswer(cmd, false, err);
            }
            String convertedBasePath = String.format("%s/%s", temporaryConvertPath, temporaryConvertUuid);
            LibvirtDomainXMLParser xmlParser = parseMigratedVMXmlDomain(convertedBasePath);
            List<LibvirtVMDef.DiskDef> disks = xmlParser.getDisks();
            List<LibvirtVMDef.InterfaceDef> interfaces = xmlParser.getInterfaces();
            if (disks.size() < 1) {
                String err = String.format("Cannot find any disk for the converted instance %s on path: %s",
                        sourceInstanceName, convertedBasePath);
                s_logger.error(err);
                return new ConvertInstanceAnswer(cmd, false, err);
            }
            sanitizeDisksPath(disks);

            storagePoolMgr.deleteStoragePool(secondaryPool.getType(), secondaryPool.getUuid());
            secondaryPool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl + File.separator + temporaryConvertFolder);

            List<KVMPhysicalDisk> destinationDisks = moveConvertedInstanceFromTemporaryLocaltionToDestination(disks, secondaryPool,
                    destinationStoragePools, storagePoolMgr);

            UnmanagedInstanceTO convertedInstanceTO = getConvertedUnmanagedInstance(temporaryConvertUuid,
                    destinationDisks, disks, interfaces, xmlParser);
            return new ConvertInstanceAnswer(cmd, convertedInstanceTO);
        } catch (Exception e) {
            String error = String.format("Error converting instance %s from %s, due to: %s",
                    sourceInstanceName, sourceHypervisorType, e.getMessage());
            s_logger.error(error, e);
            return new ConvertInstanceAnswer(cmd, false, error);
        } finally {
            Script.runSimpleBashScript(String.format("rm -rf %s", temporaryPasswordFilePath));
        }
    }

    private void sanitizeDisksPath(List<LibvirtVMDef.DiskDef> disks) {
        for (LibvirtVMDef.DiskDef disk : disks) {
            String[] diskPathParts = disk.getDiskPath().split("/");
            String relativePath = diskPathParts[diskPathParts.length - 1];
            disk.setDiskPath(relativePath);
        }
    }

    private List<KVMPhysicalDisk> moveConvertedInstanceFromTemporaryLocaltionToDestination(List<LibvirtVMDef.DiskDef> vmDisks,
                                                                          KVMStoragePool secondaryPool, List<String> destinationStoragePools,
                                                                          KVMStoragePoolManager storagePoolMgr) {
        List<KVMPhysicalDisk> targetDisks = new ArrayList<>();
        if (vmDisks.size() != destinationStoragePools.size()) {
            String warn = String.format("Discrepancy between the converted instance disks (%s) " +
                    "and the expected number of disks (%s)", vmDisks.size(), destinationStoragePools.size());
            s_logger.warn(warn);
        }
        for (int i = 0; i < vmDisks.size(); i++) {
            LibvirtVMDef.DiskDef disk = vmDisks.get(i);
            String poolPath = destinationStoragePools.get(i);
            KVMStoragePool destinationPool = storagePoolMgr.getStoragePool(Storage.StoragePoolType.NetworkFilesystem, poolPath);
            if (destinationPool == null) {
                String err = String.format("Could not find a storage pool by URI: %s", poolPath);
                s_logger.error(err);
                continue;
            }
            String msg = String.format("Trying to copy converted instance disk number %s from the temporary location %s" +
                    " to destination storage pool %s", i, secondaryPool.getUuid(), destinationPool.getUuid());
            s_logger.debug(msg);

            String relativePath = disk.getDiskPath();
            KVMPhysicalDisk sourceDisk = secondaryPool.getPhysicalDisk(relativePath);
            String destinationName = UUID.randomUUID().toString();

            KVMPhysicalDisk destinationDisk = storagePoolMgr.copyPhysicalDisk(sourceDisk, destinationName, destinationPool, 1000 * 1000);
            targetDisks.add(destinationDisk);
        }
        return targetDisks;
    }

    private UnmanagedInstanceTO getConvertedUnmanagedInstance(String baseName,
                                                              List<KVMPhysicalDisk> vmDisks,
                                                              List<LibvirtVMDef.DiskDef> diskDefs,
                                                              List<LibvirtVMDef.InterfaceDef> interfaces,
                                                              LibvirtDomainXMLParser xmlParser) {
        UnmanagedInstanceTO instanceTO = new UnmanagedInstanceTO();
        instanceTO.setName(baseName);
        instanceTO.setDisks(getUnmanagedInstanceDisks(vmDisks, diskDefs));
        instanceTO.setNics(getUnmanagedInstanceNics(interfaces));
        return instanceTO;
    }

    private List<UnmanagedInstanceTO.Nic> getUnmanagedInstanceNics(List<LibvirtVMDef.InterfaceDef> interfaces) {
        List<UnmanagedInstanceTO.Nic> nics = new ArrayList<>();
        for (LibvirtVMDef.InterfaceDef interfaceDef : interfaces) {
            UnmanagedInstanceTO.Nic nic = new UnmanagedInstanceTO.Nic();
            nic.setMacAddress(interfaceDef.getMacAddress());
            nic.setNicId(interfaceDef.getBrName());
            nic.setAdapterType(interfaceDef.getModel().toString());
            nics.add(nic);
        }
        return nics;
    }

    private List<UnmanagedInstanceTO.Disk> getUnmanagedInstanceDisks(List<KVMPhysicalDisk> vmDisks, List<LibvirtVMDef.DiskDef> diskDefs) {
        List<UnmanagedInstanceTO.Disk> instanceDisks = new ArrayList<>();
        for (int i = 0; i< vmDisks.size(); i++) {
            KVMPhysicalDisk physicalDisk = vmDisks.get(i);
            LibvirtVMDef.DiskDef diskDef = diskDefs.get(i);
            KVMStoragePool storagePool = physicalDisk.getPool();
            UnmanagedInstanceTO.Disk disk = new UnmanagedInstanceTO.Disk();
            disk.setPosition(i);
            Pair<String, String> storagePoolHostAndPath = getNfsStoragePoolHostAndPath(storagePool);
            disk.setDatastoreHost(storagePoolHostAndPath.first());
            disk.setDatastorePath(storagePoolHostAndPath.second());
            disk.setDatastoreName(storagePool.getUuid());
            disk.setDatastoreType(storagePool.getType().name());
            disk.setCapacity(physicalDisk.getVirtualSize());
            disk.setFileBaseName(physicalDisk.getName());
            disk.setController(diskDef.getBusType().toString());
            instanceDisks.add(disk);
        }
        return instanceDisks;
    }

    private Pair<String, String> getNfsStoragePoolHostAndPath(KVMStoragePool storagePool) {
        String sourceHostIp = null;
        String sourcePath = null;
        String storagePoolMountPoint = Script.runSimpleBashScript(String.format("mount | grep %s", storagePool.getLocalPath()));
        if (StringUtils.isNotEmpty(storagePoolMountPoint)) {
            String[] res = storagePoolMountPoint.strip().split(" ");
            res = res[0].split(":");
            sourceHostIp = res[0].strip();
            sourcePath = res[1].strip();
        }
        return new Pair<>(sourceHostIp, sourcePath);
    }

    private Pair<Boolean, String> performInstanceConversion(String convertInstanceUrl, String sourceInstanceName,
                                           String temporaryPasswordFilePath,
                                           String temporaryConvertFolder,
                                           String temporaryConvertUuid,
                                           int timeout) {
        Script script = new Script("virt-v2v", timeout, s_logger);
        script.add("--root", "first");
        script.add("-ic", convertInstanceUrl);
        script.add(sourceInstanceName);
        script.add("--password-file", temporaryPasswordFilePath);
        script.add("-o", "local");
        script.add("-os", temporaryConvertFolder);
        script.add("-of", "qcow2");
        script.add("-on", temporaryConvertUuid);
        String result = script.execute();
        int exitValue = script.getExitValue();
        return new Pair<>(exitValue == 0, result);
    }

    private String createTemporaryPasswordFileAndRetrievePath(RemoteInstanceTO sourceInstance) {
        String password = null;
        if (sourceInstance.getHypervisorType() == Hypervisor.HypervisorType.VMware) {
            password = sourceInstance.getVcenterPassword();
        }
        String passwordFile = String.format("/tmp/vmw-%s", UUID.randomUUID());
        Script.runSimpleBashScript(String.format("echo \"%s\" > %s", password, passwordFile));
        return passwordFile;
    }

    private String getConvertInstanceUrl(RemoteInstanceTO sourceInstance) {
        String url = null;
        if (sourceInstance.getHypervisorType() == Hypervisor.HypervisorType.VMware) {
            url = getConvertInstanceUrlFromVmware(sourceInstance);
        }
        return url;
    }

    private String getConvertInstanceUrlFromVmware(RemoteInstanceTO vmwareInstance) {
        String vcenter = vmwareInstance.getVcenterHost();
        String datacenter = vmwareInstance.getDatacenterName();
        String username = vmwareInstance.getVcenterUsername();
        String host = vmwareInstance.getHostName();
        String cluster = vmwareInstance.getClusterName();

        String encodedUsername = encodeUsername(username);
        return String.format("vpx://%s@%s/%s/%s/%s?no_verify=1",
                encodedUsername, vcenter, datacenter, cluster, host);
    }
    protected LibvirtDomainXMLParser parseMigratedVMXmlDomain(String installPath) throws IOException {
        String xmlPath = String.format("%s.xml", installPath);
        InputStream is = new BufferedInputStream(new FileInputStream(xmlPath));
        String xml = IOUtils.toString(is, Charset.defaultCharset());
        final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        parser.parseDomainXML(xml);
        return parser;
    }

    protected String encodeUsername(String username) {
        return URLEncoder.encode(username, Charset.defaultCharset());
    }
}
