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
package org.apache.cloudstack.outofbandmanagement;

import com.cloud.alert.AlertManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterDetailVO;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.org.Cluster;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableMap;
import org.apache.cloudstack.api.response.OutOfBandManagementResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.outofbandmanagement.dao.OutOfBandManagementDao;
import org.apache.cloudstack.outofbandmanagement.driver.OutOfBandManagementDriverChangePasswordCommand;
import org.apache.cloudstack.outofbandmanagement.driver.OutOfBandManagementDriverPowerCommand;
import org.apache.cloudstack.outofbandmanagement.driver.OutOfBandManagementDriverResponse;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@Local(value = {OutOfBandManagementService.class})
public class OutOfBandManagementServiceImpl extends ManagerBase implements OutOfBandManagementService, Manager, Configurable {
    public static final Logger LOG = Logger.getLogger(OutOfBandManagementServiceImpl.class);

    @Inject
    private ClusterDetailsDao clusterDetailsDao;
    @Inject
    private DataCenterDetailsDao dataCenterDetailsDao;
    @Inject
    private OutOfBandManagementDao outOfBandManagementDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AlertManager alertMgr;

    private String name;
    private long serviceId;

    private List<OutOfBandManagementDriver> outOfBandManagementDrivers = new ArrayList<>();
    private Map<String, OutOfBandManagementDriver> outOfBandManagementDriversMap = new HashMap<String, OutOfBandManagementDriver>();

    private static final String OOBM_ENABLED_DETAIL = "outOfBandManagementEnabled";
    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_HOST = 120;

    private Cache<Long, Long> hostAlertCache;
    private static ExecutorService backgroundSyncExecutor;

    private String getOutOfBandManagementHostLock(long id) {
        return "oobm.host." + id;
    }

    private void initializeDriversMap() {
        if (outOfBandManagementDriversMap.isEmpty() && outOfBandManagementDrivers != null && outOfBandManagementDrivers.size() > 0) {
            for (final OutOfBandManagementDriver driver : outOfBandManagementDrivers) {
                outOfBandManagementDriversMap.put(driver.getName().toLowerCase(), driver);
            }
            LOG.debug("Discovered out-of-band management drivers configured in the OutOfBandManagementService");
        }
    }

    private OutOfBandManagementDriver getDriver(final OutOfBandManagement outOfBandManagementConfig) {
        if (!Strings.isNullOrEmpty(outOfBandManagementConfig.getDriver())) {
            final OutOfBandManagementDriver driver = outOfBandManagementDriversMap.get(outOfBandManagementConfig.getDriver());
            if (driver != null) {
                return driver;
            }
        }
        throw new CloudRuntimeException("Configured out-of-band management driver is not available. Aborting any out-of-band management action.");
    }

    protected OutOfBandManagement updateConfig(final OutOfBandManagement outOfBandManagementConfig, final ImmutableMap<OutOfBandManagement.Option, String> options) {
        if (outOfBandManagementConfig == null) {
            throw new CloudRuntimeException("Out-of-band management is not configured for the host. Aborting.");
        }
        if (options == null) {
            return outOfBandManagementConfig;
        }
        for (OutOfBandManagement.Option option: options.keySet()) {
            final String value = options.get(option);
            if (Strings.isNullOrEmpty(value)) {
                continue;
            }
            switch (option) {
                case DRIVER:
                    outOfBandManagementConfig.setDriver(value);
                    break;
                case ADDRESS:
                    outOfBandManagementConfig.setAddress(value);
                    break;
                case PORT:
                    outOfBandManagementConfig.setPort(Integer.parseInt(value));
                    break;
                case USERNAME:
                    outOfBandManagementConfig.setUsername(value);
                    break;
                case PASSWORD:
                    outOfBandManagementConfig.setPassword(value);
                    break;
            }
        }
        return outOfBandManagementConfig;
    }

    protected ImmutableMap<OutOfBandManagement.Option, String> getOptions(final OutOfBandManagement outOfBandManagementConfig) {
        final ImmutableMap.Builder<OutOfBandManagement.Option, String> optionsBuilder = ImmutableMap.builder();
        if (outOfBandManagementConfig == null) {
            throw new CloudRuntimeException("Out-of-band management is not configured for the host. Aborting.");
        }
        for (OutOfBandManagement.Option option: OutOfBandManagement.Option.values()) {
            String value = null;
            switch (option) {
                case DRIVER:
                    value = outOfBandManagementConfig.getDriver();
                    break;
                case ADDRESS:
                    value = outOfBandManagementConfig.getAddress();
                    break;
                case PORT:
                    if (outOfBandManagementConfig.getPort() != null) {
                        value = String.valueOf(outOfBandManagementConfig.getPort());
                    }
                    break;
                case USERNAME:
                    value = outOfBandManagementConfig.getUsername();
                    break;
                case PASSWORD:
                    value = outOfBandManagementConfig.getPassword();
                    break;
            }
            if (value != null) {
                optionsBuilder.put(option, value);
            }
        }
        return optionsBuilder.build();
    }

    private void sendAuthError(final Host host, final String message) {
        try {
            hostAlertCache.asMap().putIfAbsent(host.getId(), 0L);
            Long sentCount = hostAlertCache.asMap().get(host.getId());
            if (sentCount != null && sentCount <= 0) {
                boolean concurrentUpdateResult = hostAlertCache.asMap().replace(host.getId(), sentCount, sentCount+1L);
                if (concurrentUpdateResult) {
                    final String subject = String.format("Out-of-band management auth-error detected for host:%d in cluster:%d, zone:%d", host.getId(), host.getClusterId(), host.getDataCenterId());
                    LOG.error(subject + ": " + message);
                    alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_OOBM_AUTH_ERROR, host.getDataCenterId(), host.getPodId(), subject, message);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean transitionPowerState(OutOfBandManagement.PowerState.Event event, OutOfBandManagement outOfBandManagementHost) {
        if (outOfBandManagementHost == null) {
            return false;
        }
        OutOfBandManagement.PowerState currentPowerState = outOfBandManagementHost.getPowerState();
        try {
            OutOfBandManagement.PowerState newPowerState = OutOfBandManagement.PowerState.getStateMachine().getNextState(currentPowerState, event);
            boolean result = outOfBandManagementDao.updateState(currentPowerState, event, newPowerState, outOfBandManagementHost, null);
            if (result) {
                final String message = String.format("Transitioned out-of-band management power state from:%s to:%s due to event:%s for the host id:%d", currentPowerState, newPowerState, event, outOfBandManagementHost.getHostId());
                LOG.debug(message);
                ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), Domain.ROOT_DOMAIN,
                        EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_POWERSTATE_TRANSITION, message);
            }
            return result;
        } catch (NoTransitionException ignored) {
            LOG.trace(String.format("Unable to transition out-of-band management power state for host id=%s for the event=%s and current power state=%s", outOfBandManagementHost.getHostId(), event, currentPowerState));
        }
        return false;
    }

    private boolean isOutOfBandManagementEnabledForZone(Long zoneId) {
        if (zoneId == null) {
            return true;
        }
        final DataCenterDetailVO zoneDetails = dataCenterDetailsDao.findDetail(zoneId, OOBM_ENABLED_DETAIL);
        if (zoneDetails != null && !Strings.isNullOrEmpty(zoneDetails.getValue()) && !Boolean.valueOf(zoneDetails.getValue())) {
            return false;
        }
        return true;
    }

    private boolean isOutOfBandManagementEnabledForCluster(Long clusterId) {
        if (clusterId == null) {
            return true;
        }
        final ClusterDetailsVO clusterDetails = clusterDetailsDao.findDetail(clusterId, OOBM_ENABLED_DETAIL);
        if (clusterDetails != null && !Strings.isNullOrEmpty(clusterDetails.getValue()) && !Boolean.valueOf(clusterDetails.getValue())) {
            return false;
        }
        return true;
    }

    private boolean isOutOfBandManagementEnabledForHost(Long hostId) {
        if (hostId == null) {
            return false;
        }
        final OutOfBandManagement outOfBandManagementConfig = outOfBandManagementDao.findByHost(hostId);
        if (outOfBandManagementConfig == null || !outOfBandManagementConfig.isEnabled()) {
            return false;
        }
        return true;
    }

    private void checkOutOfBandManagementEnabledByZoneClusterHost(final Host host) {
        if (!isOutOfBandManagementEnabledForZone(host.getDataCenterId())) {
            throw new CloudRuntimeException("Out-of-band management is disabled for the host's zone. Aborting Operation.");
        }
        if (!isOutOfBandManagementEnabledForCluster(host.getClusterId())) {
            throw new CloudRuntimeException("Out-of-band management is disabled for the host's cluster. Aborting Operation.");
        }
        if (!isOutOfBandManagementEnabledForHost(host.getId())) {
            throw new CloudRuntimeException("Out-of-band management is disabled or not configured for the host. Aborting Operation.");
        }
    }

    public boolean isOutOfBandManagementEnabled(final Host host) {
        return isOutOfBandManagementEnabledForZone(host.getDataCenterId())
                && isOutOfBandManagementEnabledForCluster(host.getClusterId())
                && isOutOfBandManagementEnabledForHost(host.getId());
    }

    public boolean transitionPowerStateToDisabled(List<? extends Host> hosts) {
        boolean result = true;
        for (Host host : hosts) {
            result = result && transitionPowerState(OutOfBandManagement.PowerState.Event.Disabled,
                    outOfBandManagementDao.findByHost(host.getId()));
        }
        return result;
    }

    public void submitBackgroundPowerSyncTask(final Host host) {
        if (host != null) {
            // Note: This is a blocking queue based executor
            backgroundSyncExecutor.submit(new OutOfBandManagementBackgroundTask(this, host, OutOfBandManagement.PowerOperation.STATUS));
        }
    }

    private OutOfBandManagementResponse buildEnableDisableResponse(final boolean enabled) {
        final OutOfBandManagementResponse response = new OutOfBandManagementResponse();
        response.setEnabled(enabled);
        response.setSuccess(true);
        return response;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_ENABLEDISABLE, eventDescription = "enabling/disabling out-of-band management on a zone")
    public OutOfBandManagementResponse enableDisableOutOfBandManagement(final DataCenter zone, final boolean enabled) {
        dataCenterDetailsDao.persist(zone.getId(), OOBM_ENABLED_DETAIL, String.valueOf(enabled));
        if (!enabled) {
            transitionPowerStateToDisabled(hostDao.findByDataCenterId(zone.getId()));
        }
        return buildEnableDisableResponse(enabled);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_ENABLEDISABLE, eventDescription = "enabling/disabling out-of-band management on a cluster")
    public OutOfBandManagementResponse enableDisableOutOfBandManagement(final Cluster cluster, final boolean enabled) {
        clusterDetailsDao.persist(cluster.getId(), OOBM_ENABLED_DETAIL, String.valueOf(enabled));
        if (!enabled) {
            transitionPowerStateToDisabled(hostDao.findByClusterId(cluster.getId()));
        }
        return buildEnableDisableResponse(enabled);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_ENABLEDISABLE, eventDescription = "enabling/disabling out-of-band management on a host")
    public OutOfBandManagementResponse enableDisableOutOfBandManagement(final Host host, final boolean enabled) {
        final OutOfBandManagement outOfBandManagementConfig = outOfBandManagementDao.findByHost(host.getId());
        if (outOfBandManagementConfig == null) {
            final OutOfBandManagementResponse response = new OutOfBandManagementResponse(null);
            response.setSuccess(false);
            response.setResultDescription("Out-of-band management is not configured for the host. Please configure the host before enabling/disabling it.");
            return response;
        }
        hostAlertCache.invalidate(host.getId());
        outOfBandManagementConfig.setEnabled(enabled);
        boolean updateResult = outOfBandManagementDao.update(outOfBandManagementConfig.getId(), (OutOfBandManagementVO) outOfBandManagementConfig);
        if (updateResult && !enabled) {
            transitionPowerStateToDisabled(Collections.singletonList(host));
        }
        return buildEnableDisableResponse(enabled && updateResult);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_CONFIGURE, eventDescription = "updating out-of-band management configuration")
    public OutOfBandManagementResponse configureOutOfBandManagement(final Host host, final ImmutableMap<OutOfBandManagement.Option, String> options) {
        OutOfBandManagement outOfBandManagementConfig = outOfBandManagementDao.findByHost(host.getId());
        if (outOfBandManagementConfig == null) {
            outOfBandManagementConfig = outOfBandManagementDao.persist(new OutOfBandManagementVO(host.getId()));
        }
        outOfBandManagementConfig = updateConfig(outOfBandManagementConfig, options);
        if (Strings.isNullOrEmpty(outOfBandManagementConfig.getDriver()) || !outOfBandManagementDriversMap.containsKey(outOfBandManagementConfig.getDriver().toLowerCase())) {
            throw new CloudRuntimeException("Out-of-band management driver is not available. Please provide a valid driver name.");
        }

        boolean updatedConfig = outOfBandManagementDao.update(outOfBandManagementConfig.getId(), (OutOfBandManagementVO) outOfBandManagementConfig);
        CallContext.current().setEventDetails("host id:" + host.getId() + " configuration:" + outOfBandManagementConfig.getAddress() + ":" + outOfBandManagementConfig.getPort());

        if (!updatedConfig) {
            throw new CloudRuntimeException("Failed to update out-of-band management config for the host in the database.");
        }

        String result = "Out-of-band management successfully configured for the host";
        LOG.debug(result);

        final OutOfBandManagementResponse response = new OutOfBandManagementResponse(outOfBandManagementDao.findByHost(host.getId()));
        response.setResultDescription(result);
        response.setSuccess(true);
        return response;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_ACTION, eventDescription = "issuing Host out-of-band management action", async = true)
    public OutOfBandManagementResponse executeOutOfBandManagementPowerOperation(final Host host, final OutOfBandManagement.PowerOperation powerOperation, final Long timeout) {
        checkOutOfBandManagementEnabledByZoneClusterHost(host);
        final OutOfBandManagement outOfBandManagementConfig = outOfBandManagementDao.findByHost(host.getId());
        final ImmutableMap<OutOfBandManagement.Option, String> options = getOptions(outOfBandManagementConfig);
        final OutOfBandManagementDriver driver = getDriver(outOfBandManagementConfig);

        Long actionTimeOut = timeout;
        if (actionTimeOut == null) {
            actionTimeOut = OutOfBandManagementActionTimeout.valueIn(host.getClusterId());
        }

        final OutOfBandManagementDriverPowerCommand cmd = new OutOfBandManagementDriverPowerCommand(options, actionTimeOut, powerOperation);
        final OutOfBandManagementDriverResponse driverResponse = driver.execute(cmd);

        if (driverResponse == null) {
            throw new CloudRuntimeException(String.format("Out-of-band Management action (%s) on host (%s) failed due to no response from the driver", powerOperation, host.getUuid()));
        }

        if (powerOperation.equals(OutOfBandManagement.PowerOperation.STATUS)) {
            transitionPowerState(driverResponse.toEvent(), outOfBandManagementConfig);
        }

        if (!driverResponse.isSuccess()) {
            String errorMessage = String.format("Out-of-band Management action (%s) on host (%s) failed with error: %s", powerOperation, host.getUuid(), driverResponse.getError());
            if (driverResponse.hasAuthFailure()) {
                errorMessage = String.format("Out-of-band Management action (%s) on host (%s) failed due to authentication error: %s. Please check configured credentials.", powerOperation, host.getUuid(), driverResponse.getError());
                sendAuthError(host, errorMessage);
            }
            if (!powerOperation.equals(OutOfBandManagement.PowerOperation.STATUS)) {
                LOG.debug(errorMessage);
            }
            throw new CloudRuntimeException(errorMessage);
        }

        final OutOfBandManagementResponse response = new OutOfBandManagementResponse(outOfBandManagementDao.findByHost(host.getId()));
        response.setSuccess(driverResponse.isSuccess());
        response.setResultDescription(driverResponse.getResult());
        response.setId(host.getUuid());
        response.setOutOfBandManagementAction(powerOperation.toString());
        return response;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_HOST_OUTOFBAND_MANAGEMENT_CHANGE_PASSWORD, eventDescription = "updating out-of-band management password")
    public OutOfBandManagementResponse changeOutOfBandManagementPassword(final Host host, final String newPassword) {
        checkOutOfBandManagementEnabledByZoneClusterHost(host);
        if (Strings.isNullOrEmpty(newPassword)) {
            throw new CloudRuntimeException(String.format("Cannot change out-of-band management password as provided new-password is null or empty for the host %s.", host.getUuid()));
        }
        GlobalLock outOfBandManagementHostLock = GlobalLock.getInternLock(getOutOfBandManagementHostLock(host.getId()));
        try {
            if (outOfBandManagementHostLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_HOST)) {
                try {
                    final OutOfBandManagement outOfBandManagementConfig = outOfBandManagementDao.findByHost(host.getId());

                    final ImmutableMap<OutOfBandManagement.Option, String> options = getOptions(outOfBandManagementConfig);
                    if (!(options.containsKey(OutOfBandManagement.Option.PASSWORD) && !Strings.isNullOrEmpty(options.get(OutOfBandManagement.Option.PASSWORD)))) {
                        throw new CloudRuntimeException(String.format("Cannot change out-of-band management password as we've no previously configured password for the host %s.", host.getUuid()));
                    }
                    final OutOfBandManagementDriver driver = getDriver(outOfBandManagementConfig);

                    final OutOfBandManagementDriverChangePasswordCommand cmd = new OutOfBandManagementDriverChangePasswordCommand(options, OutOfBandManagementActionTimeout.valueIn(host.getClusterId()), newPassword);
                    final OutOfBandManagementDriverResponse driverResponse;
                    try {
                        driverResponse = driver.execute(cmd);
                    } catch (Exception e) {
                        LOG.error("Out-of-band management change password failed due to driver error: " + e.getMessage());
                        throw new CloudRuntimeException(String.format("Failed to change out-of-band management password for host (%s) due to driver error: %s", host.getUuid(), e.getMessage()));
                    }

                    if (!driverResponse.isSuccess()) {
                        throw new CloudRuntimeException(String.format("Failed to change out-of-band management password for host (%s) with error: %s", host.getUuid(), driverResponse.getError()));
                    }

                    final boolean updatedConfigResult = Transaction.execute(new TransactionCallback<Boolean>() {
                        @Override
                        public Boolean doInTransaction(TransactionStatus status) {
                            OutOfBandManagement updatedOutOfBandManagementConfig = outOfBandManagementDao.findByHost(host.getId());
                            updatedOutOfBandManagementConfig.setPassword(newPassword);
                            return outOfBandManagementDao.update(updatedOutOfBandManagementConfig.getId(), (OutOfBandManagementVO) updatedOutOfBandManagementConfig);
                        }
                    });

                    if (!updatedConfigResult) {
                        LOG.error(String.format("Succeeded to change out-of-band management password but failed to updated in database the new password:%s for the host id:%d", newPassword, host.getId()));
                    }

                    final OutOfBandManagementResponse response = new OutOfBandManagementResponse();
                    response.setSuccess(updatedConfigResult && driverResponse.isSuccess());
                    response.setResultDescription(driverResponse.getResult());
                    response.setId(host.getUuid());
                    return response;
                } finally {
                    outOfBandManagementHostLock.unlock();
                }
            } else {
                LOG.error("Unable to acquire synchronization lock to change out-of-band management password for host id: " + host.getId());
                throw new CloudRuntimeException(String.format("Unable to acquire lock to change out-of-band management password for host (%s), please try after some time.", host.getUuid()));
            }
        } finally {
            outOfBandManagementHostLock.releaseRef();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getId() {
        return serviceId;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        this.name = name;
        this.serviceId = ManagementServerNode.getManagementServerId();

        final int poolSize = OutOfBandManagementSyncThreadPoolSize.value();

        hostAlertCache = CacheBuilder.newBuilder()
                .concurrencyLevel(4)
                .weakKeys()
                .maximumSize(100 * poolSize)
                .expireAfterWrite(1, TimeUnit.DAYS)
                .build();

        backgroundSyncExecutor = new ThreadPoolExecutor(poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(10 * poolSize, true), new ThreadPoolExecutor.CallerRunsPolicy());

        LOG.info("Starting out-of-band management background sync executor with thread pool-size=" + poolSize + " and background sync thread interval=" + OutOfBandManagementSyncThreadInterval.value() + "s");
        return true;
    }

    @Override
    public boolean start() {
        initializeDriversMap();
        return true;
    }

    @Override
    public boolean stop() {
        backgroundSyncExecutor.shutdown();
        outOfBandManagementDao.expireOutOfBandManagementOwnershipByServer(getId());
        return true;
    }

    @Override
    public String getConfigComponentName() {
        return OutOfBandManagementServiceImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {OutOfBandManagementActionTimeout, OutOfBandManagementSyncThreadInterval, OutOfBandManagementSyncThreadPoolSize};
    }

    public List<OutOfBandManagementDriver> getOutOfBandManagementDrivers() {
        return outOfBandManagementDrivers;
    }

    public void setOutOfBandManagementDrivers(List<OutOfBandManagementDriver> outOfBandManagementDrivers) {
        this.outOfBandManagementDrivers = outOfBandManagementDrivers;
    }
}
