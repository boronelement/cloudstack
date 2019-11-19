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

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "backup_offering")
public class BackupOfferingVO implements BackupOffering {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "zone_id")
    private long zoneId;

    @Column(name = "created")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date created;

    @Column(name = "removed")
    @Temporal(value = TemporalType.TIMESTAMP)
    private Date removed;

    public BackupOfferingVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public BackupOfferingVO(final long zoneId, final String externalId, final String name, final String description) {
        this();
        this.zoneId = zoneId;
        this.name = name;
        this.description = description;
        this.externalId = externalId;
        this.created = new Date();
    }

    public BackupOfferingVO(final String externalId, final String name, final String description) {
        this.name = name;
        this.description = description;
        this.externalId = externalId;
        this.created = new Date();
    }

    public String getUuid() {
        return uuid;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getExternalId() {
        return externalId;
    }

    @Override
    public boolean isImported() {
        return true;
    }

    @Override
    public long getZoneId() {
        return zoneId;
    }

    public String getDescription() {
        return description;
    }
}
