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
package org.apache.cloudstack.backup.backroll;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.backup.Backup.Metric;
import org.apache.cloudstack.backup.backroll.model.BackrollBackup;
import org.apache.cloudstack.backup.backroll.model.BackrollBackupMetrics;
import org.apache.cloudstack.backup.backroll.model.BackrollOffering;
import org.apache.cloudstack.backup.backroll.model.BackrollTaskStatus;
import org.apache.cloudstack.backup.backroll.model.BackrollVmBackup;
import org.apache.cloudstack.backup.backroll.model.response.BackrollTaskRequestResponse;
import org.apache.cloudstack.backup.backroll.model.response.TaskState;
import org.apache.cloudstack.backup.backroll.model.response.TaskStateResponse;
import org.apache.cloudstack.backup.backroll.model.response.api.LoginApiResponse;
import org.apache.cloudstack.backup.backroll.model.response.archive.BackrollArchiveResponse;
import org.apache.cloudstack.backup.backroll.model.response.archive.BackrollBackupsFromVMResponse;
import org.apache.cloudstack.backup.backroll.model.response.backup.BackrollBackupStatusResponse;
import org.apache.cloudstack.backup.backroll.model.response.backup.BackrollBackupStatusSuccessResponse;
import org.apache.cloudstack.backup.backroll.model.response.metrics.backup.BackrollBackupMetricsResponse;
import org.apache.cloudstack.backup.backroll.model.response.metrics.virtualMachine.BackrollVmMetricsResponse;
import org.apache.cloudstack.backup.backroll.model.response.metrics.virtualMachine.CacheStats;
import org.apache.cloudstack.backup.backroll.model.response.metrics.virtualMachineBackups.BackupInfos;
import org.apache.cloudstack.backup.backroll.model.response.metrics.virtualMachineBackups.VirtualMachineBackupsResponse;
import org.apache.cloudstack.backup.backroll.model.response.policy.BackrollBackupPolicyResponse;
import org.apache.cloudstack.backup.backroll.model.response.policy.BackupPoliciesResponse;
import org.apache.cloudstack.utils.security.SSLUtils;

import org.apache.commons.lang3.StringUtils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joda.time.DateTime;

import org.json.JSONObject;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BackrollClient {

    private Logger logger = LogManager.getLogger(BackrollClient.class);

    private int restoreTimeout;

    private final URI apiURI;

    private final HttpClient httpClient;

    private String backrollToken = null;
    private String appname = null;
    private String password = null;

    public BackrollClient(final String url, final String appname, final String password,
            final boolean validateCertificate, final int timeout,
            final int restoreTimeout)
            throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException, ClientProtocolException,
            IOException {
        this.apiURI = new URI(url);
        this.restoreTimeout = restoreTimeout;
        this.appname = appname;
        this.password = password;

        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000)
                .setSocketTimeout(timeout * 1000)
                .build();

        if (!validateCertificate) {
            final SSLContext sslcontext = SSLUtils.getSSLContext();
            sslcontext.init(null, new X509TrustManager[] { new TrustAllManager() }, new SecureRandom());
            final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslcontext,
                    NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .setSSLSocketFactory(factory)
                    .build();
        } else {
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .build();
        }

        if (StringUtils.isEmpty(backrollToken) || !isAuthenticated()) {
            login(appname, password);
        }
    }

    public String startBackupJob(final String jobId)
            throws ClientProtocolException, IOException, ParseException, NotOkBodyException {
        logger.info("Trying to start backup for Backroll job: {}", jobId);

        ensureLoggedIn();

        return this.<BackrollTaskRequestResponse>parse(okBody(
                post(String.format("/tasks/singlebackup/%s", jobId), null))).location
                .replace("/api/v1/status/", "");
    }

    public String getBackupOfferingUrl()
            throws ClientProtocolException, IOException, ParseException, NotOkBodyException {
        logger.info("Trying to list backroll backup policies");

        ensureLoggedIn();

        return this.<BackrollTaskRequestResponse>parse(okBody(get("/backup_policies"))).location.replace("/api/v1",
                "");
    }

    public List<BackupOffering> getBackupOfferings(String idTask)
            throws ParseException, NotOkBodyException, ClientProtocolException, IOException, InterruptedException {
        logger.info("Trying to list backroll backup policies");

        ensureLoggedIn();

        BackupPoliciesResponse backupPoliciesResponse = waitGet(idTask);

        final List<BackupOffering> policies = new ArrayList<>();
        for (final BackrollBackupPolicyResponse policy : backupPoliciesResponse.backupPolicies) {
            policies.add(new BackrollOffering(policy.name, policy.id));
        }
        return policies;
    }

    public void restoreVMFromBackup(final String vmId, final String backupName) throws Exception {
        logger.info("Start restore backup with backroll with backup {} for vm {}", backupName, vmId);

        ensureLoggedIn();

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("virtual_machine_id", vmId);
        jsonBody.put("backup_name", backupName);
        jsonBody.put("storage", "");
        jsonBody.put("mode", "single");

        String urlToRequest = this
                .<BackrollTaskRequestResponse>parse(okBody(
                        post(String.format("/tasks/restore/%s", vmId), jsonBody))).location
                .replace("/api/v1", "");

        TaskStateResponse response = waitGet(urlToRequest);
        logger.debug("RESTORE {}", response.state);

        if (!response.state.equals(TaskState.SUCCESS)) {
            throw new Exception("Backroll task failed.");
        }
    }

    public BackrollTaskStatus checkBackupTaskStatus(String taskId)
            throws ParseException, IOException, NotOkBodyException {
        logger.info("Trying to get backup status for Backroll task: {}", taskId);

        ensureLoggedIn();

        BackrollTaskStatus status = new BackrollTaskStatus();

        String body = okBody(get("/status/" + taskId));

        if (body.contains(TaskState.FAILURE) || body.contains(TaskState.PENDING)) {
            BackrollBackupStatusResponse backupStatusRequestResponse = parse(body);
            status.setState(backupStatusRequestResponse.state);
        } else {
            BackrollBackupStatusSuccessResponse backupStatusSuccessRequestResponse = parse(body);
            status.setState(backupStatusSuccessRequestResponse.state);
            status.setInfo(backupStatusSuccessRequestResponse.info);
        }

        return status;
    }

    public void deleteBackup(final String vmId, final String backupName)
            throws Exception {
        logger.info("Trying to delete backup {} for vm {} using Backroll", vmId, backupName);

        ensureLoggedIn();

        String urlToRequest = this.<BackrollTaskRequestResponse>parse(okBody(delete(
                String.format("/virtualmachines/%s/backups/%s", vmId, backupName)))).location
                .replace("/api/v1", "");

        BackrollBackupsFromVMResponse backrollBackupsFromVMResponse = waitGet(urlToRequest);
        logger.debug(backrollBackupsFromVMResponse.state);
        if (!backrollBackupsFromVMResponse.state.equals(TaskState.SUCCESS)) {
            throw new Exception("Backroll task failed.");
        }
    }

    public Metric getVirtualMachineMetrics(final String vmId) throws ClientProtocolException, IOException {
        logger.info("Trying to retrieve virtual machine metric from Backroll for vm {}", vmId);

        ensureLoggedIn();

        Metric metric = new Metric(0L, 0L);

        try {

            String urlToRequest = this.<BackrollTaskRequestResponse>parse(okBody(
                    get(String.format("/virtualmachines/%s/repository", vmId)))).location.replace("/api/v1", "");
            logger.debug(urlToRequest);

            BackrollVmMetricsResponse vmMetricsResponse = waitGet(urlToRequest);

            if (vmMetricsResponse.state.equals(TaskState.SUCCESS)) {
                logger.debug("SUCCESS ok");
                CacheStats stats = null;
                try {
                    stats = vmMetricsResponse.infos.cache.stats;
                } catch (NullPointerException e) {
                }
                if (stats != null) {
                    long size = Long.parseLong(stats.totalSize);
                    return new Metric(size, size);
                }
            }
        } catch (final Exception e) {
            logger.error("Failed to retrieve virtual machine metrics with Backroll due to: {}", e.getMessage());
        }

        return metric;
    }

    public BackrollBackupMetrics getBackupMetrics(String vmId, String backupId) throws JsonMappingException,
            JsonProcessingException, ParseException, IOException, NotOkBodyException, InterruptedException {
        logger.info("Trying to get backup metrics for VM: {}, and backup: {}", vmId, backupId);

        ensureLoggedIn();

        String urlToRequest = this.<BackrollTaskRequestResponse>parse(okBody(get(
                String.format("/virtualmachines/%s/backups/%s", vmId, backupId)))).location.replace("/api/v1", "");
        logger.debug(urlToRequest);

        BackrollBackupMetricsResponse metrics = waitGet(urlToRequest);
        if (metrics.info != null) {
            return new BackrollBackupMetrics(Long.parseLong(metrics.info.originalSize),
                    Long.parseLong(metrics.info.deduplicatedSize));
        }

        throw new CloudRuntimeException("Backup %s of VM %s has null info in its metrics.");
    }

    public List<BackrollVmBackup> getAllBackupsfromVirtualMachine(String vmId) {
        logger.info("Trying to retrieve all backups for vm {}", vmId);

        List<BackrollVmBackup> backups = new ArrayList<BackrollVmBackup>();

        try {

            String urlToRequest = this
                    .<BackrollTaskRequestResponse>parse(okBody(
                            get(String.format("/virtualmachines/%s/backups", vmId)))).location
                    .replace("/api/v1", "");
            logger.debug(urlToRequest);

            VirtualMachineBackupsResponse response = waitGet(urlToRequest);

            if (response.state.equals(TaskState.SUCCESS)) {
                if (response.info.archives.size() > 0) {
                    for (BackupInfos infos : response.info.archives) {
                        var dateStart = new DateTime(infos.start);
                        backups.add(new BackrollVmBackup(infos.id, infos.name, dateStart.toDate()));
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return backups;
    }

    private HttpResponse post(final String path, final JSONObject json) throws IOException {
        String url = apiURI.toString() + path;
        final HttpPost request = new HttpPost(url);

        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + backrollToken);
        request.setHeader("Content-type", "application/json");

        if (json != null) {
            logger.debug("JSON {}", json.toString());
            request.setEntity(new StringEntity(json.toString(), ContentType.APPLICATION_JSON));
        }

        final HttpResponse response = httpClient.execute(request);

        logger.debug("Response received in POST request with body {} is: {} for URL {}.", json, response.toString(),
                url);

        return response;
    }

    protected HttpResponse get(final String path) throws IOException {
        String url = apiURI.toString() + path;
        logger.debug("Backroll URL {}", url);
        final HttpGet request = new HttpGet(url);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + backrollToken);
        request.setHeader("Content-type", "application/json");
        final HttpResponse response = httpClient.execute(request);
        logger.debug("Response received in GET request is: {} for URL: {}.", response.toString(), url);
        return response;
    }

    protected HttpResponse delete(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpDelete request = new HttpDelete(url);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + backrollToken);
        request.setHeader("Content-type", "application/json");
        final HttpResponse response = httpClient.execute(request);
        logger.debug("Response received in GET request is: {} for URL: {}.", response.toString(), url);
        return response;
    }

    private boolean isResponseOk(final HttpResponse response) {
        switch (response.getStatusLine().getStatusCode()) {
            case HttpStatus.SC_OK:
            case HttpStatus.SC_ACCEPTED:
                return true;
            default:
                return false;
        }
    }

    public class NotOkBodyException extends Exception {
        private HttpResponse response;

        public NotOkBodyException(HttpResponse response) {
            super(String.format("HTTP response is not ok: %s", response));
            this.response = response;
        }

        public HttpResponse getResponse() {
            return this.response;
        }
    }

    private String okBody(final HttpResponse response)
            throws ParseException, IOException, NotOkBodyException {
        if (isResponseOk(response)) {
            HttpEntity bodyEntity = response.getEntity();
            try {
                return EntityUtils.toString(bodyEntity);
            } finally {
                EntityUtils.consumeQuietly(bodyEntity);
            }
        }
        throw new NotOkBodyException(response);
    }

    private <T> T parse(final String json)
            throws JsonMappingException, JsonProcessingException {
        return new ObjectMapper().readValue(json, new TypeReference<T>() {
        });
    }

    private <T> T waitGet(String url) throws InterruptedException, ParseException, IOException, NotOkBodyException {
        int waitingTimeMinutes = 2;
        int refreshingPeriodSeconds = 10;

        int waitMillis = waitingTimeMinutes * 60000;

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < waitMillis) {
            String body = okBody(get(url));
            if (!body.contains(TaskState.PENDING)) {
                return parse(body);
            } else {
                TimeUnit.SECONDS
                        .sleep(refreshingPeriodSeconds);
            }
        }
        throw new CloudRuntimeException(String.format("Unable to fetch %s."));
    }

    private boolean isAuthenticated() {
        boolean result = false;
        try {
            final HttpResponse response = post("/auth", null);
            result = isResponseOk(response);
            EntityUtils.consumeQuietly(response.getEntity()); // TODO Move to a finally clause ?
        } catch (IOException e) {
            logger.error("Failed to authenticate to Backroll due to: {}", e.getMessage());
        }
        return result;
    }

    private void ensureLoggedIn() throws ClientProtocolException, IOException {
        if (!isAuthenticated()) {
            login(appname, password);
        }
    }

    private void login(final String appname, final String appsecret) throws ClientProtocolException, IOException {
        logger.debug("Backroll client -  start login");
        final HttpPost request = new HttpPost(apiURI.toString() + "/login");

        request.addHeader("content-type", "application/json");

        JSONObject jsonBody = new JSONObject();
        StringEntity params;

        jsonBody.put("app_id", appname);
        jsonBody.put("app_secret", appsecret);
        params = new StringEntity(jsonBody.toString());
        request.setEntity(params);

        final HttpResponse response = httpClient.execute(request);
        try {
            LoginApiResponse loginResponse = parse(okBody(response));
            backrollToken = loginResponse.accessToken;
            logger.debug("Backroll client -  Token : {}", backrollToken);

            if (StringUtils.isEmpty(loginResponse.accessToken)) {
                throw new CloudRuntimeException("Backroll token is not available to perform API requests");
            }
        } catch (final NotOkBodyException e) {
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
                throw new CloudRuntimeException(
                        "Failed to create and authenticate Backroll client, please check the settings.");
            } else {
                throw new ServerApiException(ApiErrorCode.UNAUTHORIZED,
                        "Backroll API call unauthorized, please ask your administrator to fix integration issues.");
            }
        }

        logger.debug("Backroll client -  end login");
    }

    private List<BackrollBackup> getBackrollBackups(final String vmId) throws JsonMappingException,
            JsonProcessingException, ParseException, IOException, NotOkBodyException, InterruptedException {

        logger.info("start to list Backroll backups for vm {}", vmId);
        String urlToRequest = this
                .<BackrollTaskRequestResponse>parse(okBody(get("/virtualmachines/" + vmId + "/backups"))).location
                .replace("/api/v1", "");
        logger.debug(urlToRequest);
        BackrollBackupsFromVMResponse backrollBackupsFromVMResponse = waitGet(urlToRequest);

        final List<BackrollBackup> backups = new ArrayList<>();
        for (final BackrollArchiveResponse archive : backrollBackupsFromVMResponse.archives.archives) {
            backups.add(new BackrollBackup(archive.name));
            logger.debug(archive.name);
        }
        return backups;
    }
}
