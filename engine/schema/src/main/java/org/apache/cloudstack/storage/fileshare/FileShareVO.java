/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.fileshare;

import javax.persistence.Column;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "storage_fileshare")
public class FileShareVO implements FileShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "name")
    private String name;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "description")
    private String description;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "project_id")
    private long projectId;

    @Column(name = "data_center_id")
    private long dataCenterId;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    private State state;

    @Column(name = "endpoint_ip")
    private String endpointIp;

    @Column(name = "endpoint_path")
    private String endpointPath;

    @Column(name = "fs_provider_name")
    private String fsProviderName;

    @Column(name = "size")
    private Long size;

    @Column(name = "protocol")
    @Enumerated(value = EnumType.STRING)
    private Protocol protocol;

    @Column(name = "volume_id")
    private Long volumeId;

    @Column(name = "vm_id")
    private Long vmId;

    @Column(name = "mount_options")
    private String mountOptions;

    @Column(name = "fs_type")
    @Enumerated(value = EnumType.STRING)
    private FileSystemType fsType;

    @Column(name = "disk_offering_id")
    private Long diskOfferingId;

    @Column(name = "service_offering_id")
    private Long serviceOfferingId;

    public FileShareVO(String name, String description, long domainId, long accountId, long projectId, long dataCenterId, String fsProviderName,
                       Long size, Protocol protocol, String mountOptions, FileSystemType fsType, Long diskOfferingId, Long serviceOfferingId) {
        this.name = name;
        this.description = description;
        this.domainId = domainId;
        this.accountId = accountId;
        this.projectId = projectId;
        this.dataCenterId = dataCenterId;
        this.fsProviderName = fsProviderName;
        this.size = size;
        this.protocol = protocol;
        this.mountOptions = mountOptions;
        this.fsType = fsType;
        this.diskOfferingId = diskOfferingId;
        this.serviceOfferingId = serviceOfferingId;
        this.uuid = UUID.randomUUID().toString();
    }


    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Long getDomainId() {
        return domainId;
    }

    @Override
    public Long getAccountId() {
        return accountId;
    }

    @Override
    public Long getProjectId() {
        return projectId;
    }

    @Override
    public Long getdataCenterId() {
        return dataCenterId;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public String getEndpointIp() {
        return endpointIp;
    }

    @Override
    public void setEndpointIp(String endpointIp) {
        this.endpointIp = endpointIp;
    }

    @Override
    public String getEndpointPath() {
        return endpointPath;
    }

    @Override
    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    @Override
    public String getFsProviderName() {
        return fsProviderName;
    }

    @Override
    public Long getSize() {
        return size;
    }

    @Override
    public Protocol getProtocol() {
        return protocol;
    }

    @Override
    public Long getVolumeId() {
        return volumeId;
    }

    @Override
    public void setVolumeId(Long volumeId) {
        this.volumeId = volumeId;
    }

    @Override
    public Long getStorageVmId() {
        return vmId;
    }

    @Override
    public void setStorageVmId(Long vmId) {
        this.vmId = vmId;
    }

    @Override
    public String getMountOptions() {
        return mountOptions;
    }

    @Override
    public FileSystemType getFsType() {
        return fsType;
    }

    @Override
    public Long getDiskOfferingId() {
        return diskOfferingId;
    }

    @Override
    public Long getserviceOfferingId() {
        return serviceOfferingId;
    }
}
