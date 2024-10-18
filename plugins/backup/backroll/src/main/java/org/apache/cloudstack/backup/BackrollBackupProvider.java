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
package org.apache.cloudstack.backup;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.cloudstack.backup.Backup.Metric;
import org.apache.cloudstack.backup.backroll.BackrollClient;
import org.apache.cloudstack.backup.backroll.model.BackrollBackupMetrics;
import org.apache.cloudstack.backup.backroll.model.BackrollTaskStatus;
import org.apache.cloudstack.backup.backroll.model.BackrollVmBackup;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

import org.apache.commons.collections.CollectionUtils;
import org.apache.http.client.ClientProtocolException;
import org.joda.time.DateTime;

import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

public class BackrollBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    public static final String BACKUP_IDENTIFIER = "-CSBKP-";

    public ConfigKey<String> BackrollUrlConfigKey = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.backroll.config.url",
            "http://api.backup.demo.ccc:5050/api/v1",
            "Url for backroll plugin.", true, ConfigKey.Scope.Zone);

    public ConfigKey<String> BackrollAppNameConfigKey = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.backroll.config.appname",
            "backroll_api",
            "App Name for backroll plugin.", true, ConfigKey.Scope.Zone);

    public ConfigKey<String> BackrollPasswordConfigKey = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.backroll.config.password",
            "VviX8dALauSyYJMqVYJqf3UyZOpO3joS",
            "Password for backroll plugin.", true, ConfigKey.Scope.Zone);

    private BackrollClient backrollClient;

    @Inject
    private BackupDao backupDao;
    @Inject
    private VMInstanceDao vmInstanceDao;

    @Override
    public String getName() {
        return "backroll";
    }

    @Override
    public String getDescription() {
        return "Backroll Backup Plugin";
    }

    @Override
    public List<BackupOffering> listBackupOfferings(Long zoneId) {
        logger.debug("Listing backup policies on backroll B&R Plugin");
        try {
            BackrollClient client = getClient(zoneId);
            return client.getBackupOfferings(client.getBackupOfferingUrl()); // TODO Embed URL in method ?
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Failed to list backup offerings for zone %s.", zoneId), e);
        }
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        logger.info("Checking if backup offering exists on the Backroll Backup Provider");
        return true;
    }

    @Override
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        logger.info("Creating VM backup for VM {} from backup offering {}", vm.getInstanceName(),
                backupOffering.getName());
        ((VMInstanceVO) vm).setBackupExternalId(backupOffering.getUuid());
        return true;
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        logger.debug("Restoring vm {} from backup {} on the backroll Backup Provider", vm.getUuid(), backup.getUuid());
        try {
            getClient(vm.getDataCenterId()).restoreVMFromBackup(vm.getUuid(), getBackupName(backup));
        } catch (Exception e) {
            throw new CloudRuntimeException(
                    String.format("Failed to restore backup %s to VM %s.", backup.getName(), vm.getName()), e);
        }
        return true;
    }

    @Override
    public Map<VirtualMachine, Backup.Metric> getBackupMetrics(Long zoneId, List<VirtualMachine> vms) {
        final Map<VirtualMachine, Backup.Metric> metrics = new HashMap<>();
        if (CollectionUtils.isEmpty(vms)) {
            logger.warn("Unable to get VM Backup Metrics because the list of VMs is empty.");
            return metrics;
        }

        List<String> vmUuids = vms.stream().filter(Objects::nonNull).map(VirtualMachine::getUuid)
                .collect(Collectors.toList());
        logger.debug("Get Backup Metrics for VMs: {}.", String.join(", ", vmUuids));

        for (final VirtualMachine vm : vms) {
            if (vm == null) {
                continue;
            }

            try {
                Metric metric = getClient(zoneId).getVirtualMachineMetrics(vm.getUuid());
                logger.debug("Metrics for VM [uuid: {}, name: {}] is [backup size: {}, data size: {}].", vm.getUuid(),
                        vm.getInstanceName(), metric.getBackupSize(), metric.getDataSize());
                metrics.put(vm, metric);
            } catch (Exception e) {
                logger.error("Failed to get backup metrics for VM {} due to {}.", vm.getName(), e);
            }
        }
        return metrics;
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        logger.info("Removing VM ID {} from Backrool backup offering ", vm.getUuid());

        boolean everythingIsOk = true;

        List<Backup> backupsInCs = backupDao.listByVmId(null, vm.getId());

        for (Backup backup : backupsInCs) {
            logger.debug("Trying to remove backup with id {}", backup.getId());

            try {
                getClient(backup.getZoneId()).deleteBackup(vm.getUuid(), getBackupName(backup));
                logger.info("Backup {} deleted in Backroll for virtual machine {}", backup.getId(), vm.getName());
                if (!backupDao.remove(backup.getId())) {
                    everythingIsOk = false;
                }
                logger.info("Backup {} deleted in CS for virtual machine {}", backup.getId(), vm.getName());
            } catch (Exception e) {
                logger.error("Failed to remove backup {} for VM {}.", backup.getName(), vm.getName());
                everythingIsOk = false;
            }
        }

        if (!everythingIsOk) {
            logger.info("Problems occured while removing some backups for virtual machine {}", vm.getName());
        }
        return everythingIsOk;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public boolean takeBackup(VirtualMachine vm) {
        logger.info("Starting backup for VM ID {} on backroll provider", vm.getUuid());
        try {
            final BackrollClient client = getClient(vm.getDataCenterId());
            String idBackupTask = client.startBackupJob(vm.getUuid());
            BackupVO backup = new BackupVO();
            backup.setVmId(vm.getId());
            backup.setExternalId(idBackupTask);
            backup.setType("INCREMENTAL");
            backup.setDate(new DateTime().toDate());
            backup.setSize(0L);
            backup.setProtectedSize(0L);
            backup.setStatus(Backup.Status.BackingUp);
            backup.setBackupOfferingId(vm.getBackupOfferingId());
            backup.setAccountId(vm.getAccountId());
            backup.setDomainId(vm.getDomainId());
            backup.setZoneId(vm.getDataCenterId());
            if (backupDao.persist(backup) == null) {// TODO is null a failure ?
                throw new Exception("Failed to persist backup.");
            }
            ;
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Failed to take a backup of VM %s.", vm.getName()), e);
        }
        return true;
    }

    @Override
    public void syncBackups(VirtualMachine vm, Backup.Metric metric) {
        logger.info("Starting sync backup for VM ID " + vm.getUuid() + " on backroll provider");

        final BackrollClient client;
        try {
            client = getClient(vm.getDataCenterId());
        } catch (Exception e) {
            throw new CloudRuntimeException(
                    String.format("Failed to get Backroll client while syncing backups for VM %s.", vm.getName()));
        }

        List<Backup> backupsInDb = backupDao.listByVmId(null, vm.getId());

        for (Backup backup : backupsInDb) {
            try {
                if (backup.getStatus().equals(Backup.Status.BackingUp)) {
                    BackrollTaskStatus response = client.checkBackupTaskStatus(backup.getExternalId());

                    logger.debug("backroll backup id: {}", backup.getExternalId());
                    logger.debug("backroll backup status: {}", response.getState());

                    BackupVO backupToUpdate = new BackupVO();
                    backupToUpdate.setVmId(backup.getVmId());
                    backupToUpdate.setExternalId(backup.getExternalId());
                    backupToUpdate.setType(backup.getType());
                    backupToUpdate.setDate(backup.getDate());
                    backupToUpdate.setSize(backup.getSize());
                    backupToUpdate.setProtectedSize(backup.getProtectedSize());
                    backupToUpdate.setBackupOfferingId(vm.getBackupOfferingId());
                    backupToUpdate.setAccountId(backup.getAccountId());
                    backupToUpdate.setDomainId(backup.getDomainId());
                    backupToUpdate.setZoneId(backup.getZoneId());

                    if (response.getState().equals("PENDING")) {
                        backupToUpdate.setStatus(Backup.Status.BackingUp);
                    } else if (response.getState().equals("FAILURE")) {
                        backupToUpdate.setStatus(Backup.Status.Failed);
                    } else if (response.getState().equals("SUCCESS")) {
                        backupToUpdate.setStatus(Backup.Status.BackedUp);
                        backupToUpdate.setExternalId(backup.getExternalId() + "," + response.getInfo());

                        BackrollBackupMetrics backupMetrics = client.getBackupMetrics(vm.getUuid(),
                                response.getInfo());
                        if (backupMetrics != null) {
                            backupToUpdate.setSize(backupMetrics.getDeduplicated()); // real size
                            backupToUpdate.setProtectedSize(backupMetrics.getSize()); // total size
                        }
                    } else {
                        backupToUpdate.setStatus(Backup.Status.BackingUp);
                    }

                    if (backupDao.persist(backupToUpdate) != null) {
                        logger.info("Backroll mise à jour enregistrée");
                        backupDao.remove(backup.getId());
                    }

                } else if (backup.getStatus().equals(Backup.Status.BackedUp) && backup.getSize().equals(0L)) {
                    String backupId = backup.getExternalId().contains(",") ? backup.getExternalId().split(",")[1]
                            : backup.getExternalId();

                    BackrollBackupMetrics backupMetrics = client.getBackupMetrics(vm.getUuid(), backupId);
                    BackupVO backupToUpdate = ((BackupVO) backup);
                    backupToUpdate.setSize(backupMetrics.getDeduplicated()); // real size
                    backupToUpdate.setProtectedSize(backupMetrics.getSize()); // total size
                    backupDao.persist(backupToUpdate);
                }
            } catch (Exception e) {
                logger.error("Failed to sync backup {}.", backup.getName());
            }
        }

        // Backups synchronisation between Backroll ad CS Db
        List<BackrollVmBackup> backupsFromBackroll = client.getAllBackupsfromVirtualMachine(vm.getUuid());
        backupsInDb = backupDao.listByVmId(null, vm.getId());

        // insert new backroll backup in CS
        for (BackrollVmBackup backupInBackroll : backupsFromBackroll) {
            Backup backupToFind = backupsInDb.stream()
                    .filter(backupInDb -> backupInDb.getExternalId().contains(backupInBackroll.getName()))
                    .findAny()
                    .orElse(null);

            if (backupToFind == null) {
                BackupVO backupToInsert = new BackupVO();
                backupToInsert.setVmId(vm.getId());
                backupToInsert.setExternalId(backupInBackroll.getId() + "," + backupInBackroll.getName());
                backupToInsert.setType("INCREMENTAL");
                backupToInsert.setDate(backupInBackroll.getDate());
                backupToInsert.setSize(0L);
                backupToInsert.setProtectedSize(0L);
                backupToInsert.setStatus(Backup.Status.BackedUp);
                backupToInsert.setBackupOfferingId(vm.getBackupOfferingId());
                backupToInsert.setAccountId(vm.getAccountId());
                backupToInsert.setDomainId(vm.getDomainId());
                backupToInsert.setZoneId(vm.getDataCenterId());
                backupDao.persist(backupToInsert);
            }
            if (backupToFind != null && backupToFind.getStatus() == Backup.Status.Removed) {
                BackupVO backupToUpdate = ((BackupVO) backupToFind);
                backupToUpdate.setStatus(Backup.Status.BackedUp);
                if (backupDao.persist(backupToUpdate) != null) {
                    logger.info("Backroll update saved");
                    backupDao.remove(backupToFind.getId());
                }
            }
        }

        // delete deleted backroll backup in CS
        backupsInDb = backupDao.listByVmId(null, vm.getId());
        for (Backup backup : backupsInDb) {
            String backupName = backup.getExternalId().contains(",") ? backup.getExternalId().split(",")[1]
                    : backup.getExternalId();
            BackrollVmBackup backupToFind = backupsFromBackroll.stream()
                    .filter(backupInBackroll -> backupInBackroll.getName().contains(backupName))
                    .findAny()
                    .orElse(null);

            if (backupToFind == null) {
                BackupVO backupToUpdate = ((BackupVO) backup);
                backupToUpdate.setStatus(Backup.Status.Removed);
                if (backupDao.persist(backupToUpdate) != null) {
                    logger.debug("Backroll delete saved (sync)");
                }
            }
        }
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[] {
                BackrollUrlConfigKey,
                BackrollAppNameConfigKey,
                BackrollPasswordConfigKey
        };
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        logger.info("backroll delete backup id: {}", backup.getExternalId());
        if (backup.getStatus().equals(Backup.Status.BackingUp)) {
            throw new CloudRuntimeException("You can't delete a backup while it still backing up.");
        }

        logger.debug("backroll - try delete backup");
        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());

        if (backup.getStatus().equals(Backup.Status.Removed) || backup.getStatus().equals(Backup.Status.Failed)) {
            deleteBackupInDb(backup);
            return true;
        }

        try {
            getClient(backup.getZoneId()).deleteBackup(vm.getUuid(), getBackupName(backup));
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Failed to delete backup %s", backup.getName()));
        }

        logger.debug("Backup deletion for backup {} complete on backroll side.", backup.getUuid());
        deleteBackupInDb(backup);
        return true;
    }

    private void deleteBackupInDb(Backup backup) {
        BackupVO backupToUpdate = ((BackupVO) backup);
        backupToUpdate.setStatus(Backup.Status.Removed);
        backupDao.persist(backupToUpdate); // TODO is null a failure ?
    }

    protected BackrollClient getClient(final Long zoneId) throws ClientProtocolException, IOException {
        logger.debug("Backroll Provider GetClient with zone id {}", zoneId);
        try {
            if (backrollClient == null) {
                logger.debug("backroll client null - instanciation of new one ");
                backrollClient = new BackrollClient(BackrollUrlConfigKey.valueIn(zoneId),
                        BackrollAppNameConfigKey.valueIn(zoneId), BackrollPasswordConfigKey.valueIn(zoneId), true, 300,
                        600);
            }
            return backrollClient;
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Backroll API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            logger.info("Failed to build Backroll API client due to: ", e);
        }
        throw new CloudRuntimeException("Failed to build Backroll API client");
    }

    private String getBackupName(Backup backup) {
        return backup.getExternalId().substring(backup.getExternalId().indexOf(",") + 1);
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, String volumeUuid, String hostIp,
            String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState) {
        logger.debug("Restoring volume {} from backup {} on the Backroll Backup Provider", volumeUuid,
                backup.getUuid());
        throw new CloudRuntimeException("Backroll plugin does not support this feature");
    }
}
