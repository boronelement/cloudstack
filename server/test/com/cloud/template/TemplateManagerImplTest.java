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

package com.cloud.template;


import com.cloud.agent.AgentManager;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.projects.ProjectManager;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VMTemplateStoragePoolVO;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageCacheManager;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.test.utils.SpringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import javax.inject.Inject;
import java.io.IOException;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(loader = AnnotationConfigContextLoader.class)
public class TemplateManagerImplTest {

    @Inject
    TemplateManagerImpl templateManager = new TemplateManagerImpl();

    @Inject
    DataStoreManager dataStoreManager;

    @Inject
    VMTemplateDao vmTemplateDao;

    @Inject
    VMTemplatePoolDao vmTemplatePoolDao;

    @Inject
    TemplateDataStoreDao templateDataStoreDao;

    @Inject
    StoragePoolHostDao storagePoolHostDao;

    @Before
    public void setUp() {
        ComponentContext.initComponentsLifeCycle();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testVerifyTemplateIdOfSystemTemplate() {
        templateManager.verifyTemplateId(1L);
    }

    public void testVerifyTemplateIdOfNonSystemTemplate() {
        templateManager.verifyTemplateId(1L);
    }

    @Test
    public void testPrepareTemplateIsSeeded() {
        VMTemplateVO mockTemplate = mock(VMTemplateVO.class);
        when(mockTemplate.getId()).thenReturn(202l);

        StoragePoolVO mockPool = mock(StoragePoolVO.class);
        when(mockPool.getId()).thenReturn(2l);

        PrimaryDataStore mockPrimaryDataStore = mock(PrimaryDataStore.class);
        when(mockPrimaryDataStore.getId()).thenReturn(2l);

        VMTemplateStoragePoolVO mockTemplateStore = mock(VMTemplateStoragePoolVO.class);
        when(mockTemplateStore.getDownloadState()).thenReturn(VMTemplateStorageResourceAssoc.Status.DOWNLOADED);

        when(dataStoreManager.getPrimaryDataStore(anyLong())).thenReturn(mockPrimaryDataStore);
        when(vmTemplateDao.findById(anyLong(), anyBoolean())).thenReturn(mockTemplate);
        when(vmTemplatePoolDao.findByPoolTemplate(anyLong(), anyLong())).thenReturn(mockTemplateStore);

        doNothing().when(mockTemplateStore).setMarkedForGC(anyBoolean());

        VMTemplateStoragePoolVO returnObject = templateManager.prepareTemplateForCreate(mockTemplate, (StoragePool) mockPrimaryDataStore);
        assertTrue("Test template is already seeded", returnObject == mockTemplateStore);
    }

    @Test
    public void testPrepareTemplateNotDownloaded() {
        VMTemplateVO mockTemplate = mock(VMTemplateVO.class);
        when(mockTemplate.getId()).thenReturn(202l);

        StoragePoolVO mockPool = mock(StoragePoolVO.class);
        when(mockPool.getId()).thenReturn(2l);

        PrimaryDataStore mockPrimaryDataStore = mock(PrimaryDataStore.class);
        when(mockPrimaryDataStore.getId()).thenReturn(2l);
        when(mockPrimaryDataStore.getDataCenterId()).thenReturn(1l);

        when(dataStoreManager.getPrimaryDataStore(anyLong())).thenReturn(mockPrimaryDataStore);
        when(vmTemplateDao.findById(anyLong(), anyBoolean())).thenReturn(mockTemplate);
        when(vmTemplatePoolDao.findByPoolTemplate(anyLong(), anyLong())).thenReturn(null);
        when(templateDataStoreDao.findByTemplateZoneDownloadStatus(202l, 1l, VMTemplateStorageResourceAssoc.Status.DOWNLOADED)).thenReturn(null);

        VMTemplateStoragePoolVO returnObject = templateManager.prepareTemplateForCreate(mockTemplate, (StoragePool) mockPrimaryDataStore);
        assertTrue("Test template is not ready", returnObject == null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPrepareTemplateNoHostConnectedToPool() {
        VMTemplateVO mockTemplate = mock(VMTemplateVO.class);
        when(mockTemplate.getId()).thenReturn(202l);

        StoragePoolVO mockPool = mock(StoragePoolVO.class);
        when(mockPool.getId()).thenReturn(2l);

        PrimaryDataStore mockPrimaryDataStore = mock(PrimaryDataStore.class);
        when(mockPrimaryDataStore.getId()).thenReturn(2l);
        when(mockPrimaryDataStore.getDataCenterId()).thenReturn(1l);

        TemplateDataStoreVO mockTemplateDataStore = mock(TemplateDataStoreVO.class);

        when(dataStoreManager.getPrimaryDataStore(anyLong())).thenReturn(mockPrimaryDataStore);
        when(vmTemplateDao.findById(anyLong(), anyBoolean())).thenReturn(mockTemplate);
        when(vmTemplatePoolDao.findByPoolTemplate(anyLong(), anyLong())).thenReturn(null);
        when(templateDataStoreDao.findByTemplateZoneDownloadStatus(202l, 1l, VMTemplateStorageResourceAssoc.Status.DOWNLOADED)).thenReturn(mockTemplateDataStore);
        when(storagePoolHostDao.listByHostStatus(2l, Status.Up)).thenReturn(null);

        templateManager.prepareTemplateForCreate(mockTemplate, (StoragePool) mockPrimaryDataStore);
    }

    @Configuration
    @ComponentScan(basePackageClasses = {TemplateManagerImpl.class},
            includeFilters = {@ComponentScan.Filter(value = TestConfiguration.Library.class, type = FilterType.CUSTOM)},
            useDefaultFilters = false)
    public static class TestConfiguration extends SpringUtils.CloudStackTestConfiguration {

        @Bean
        public DataStoreManager dataStoreManager() {
            return Mockito.mock(DataStoreManager.class);
        }

        @Bean
        public VMTemplateDao vmTemplateDao() {
            return Mockito.mock(VMTemplateDao.class);
        }

        @Bean
        public VMTemplatePoolDao vmTemplatePoolDao() {
            return Mockito.mock(VMTemplatePoolDao.class);
        }

        @Bean
        public TemplateDataStoreDao templateDataStoreDao() {
            return Mockito.mock(TemplateDataStoreDao.class);
        }

        @Bean
        public VMTemplateZoneDao vmTemplateZoneDao() {
            return Mockito.mock(VMTemplateZoneDao.class);
        }

        @Bean
        public VMInstanceDao vmInstanceDao() {
            return Mockito.mock(VMInstanceDao.class);
        }

        @Bean
        public PrimaryDataStoreDao primaryDataStoreDao() {
            return Mockito.mock(PrimaryDataStoreDao.class);
        }

        @Bean
        public StoragePoolHostDao storagePoolHostDao() {
            return Mockito.mock(StoragePoolHostDao.class);
        }

        @Bean
        public AccountDao accountDao() {
            return Mockito.mock(AccountDao.class);
        }

        @Bean
        public AgentManager agentMgr() {
            return Mockito.mock(AgentManager.class);
        }

        @Bean
        public AccountManager accountManager() {
            return Mockito.mock(AccountManager.class);
        }

        @Bean
        public HostDao hostDao() {
            return Mockito.mock(HostDao.class);
        }

        @Bean
        public DataCenterDao dcDao() {
            return Mockito.mock(DataCenterDao.class);
        }

        @Bean
        public UserVmDao userVmDao() {
            return Mockito.mock(UserVmDao.class);
        }

        @Bean
        public VolumeDao volumeDao() {
            return Mockito.mock(VolumeDao.class);
        }

        @Bean
        public SnapshotDao snapshotDao() {
            return Mockito.mock(SnapshotDao.class);
        }

        @Bean
        public ConfigurationDao configDao() {
            return Mockito.mock(ConfigurationDao.class);
        }

        @Bean
        public DomainDao domainDao() {
            return Mockito.mock(DomainDao.class);
        }

        @Bean
        public GuestOSDao guestOSDao() {
            return Mockito.mock(GuestOSDao.class);
        }

        @Bean
        public StorageManager storageManager() {
            return Mockito.mock(StorageManager.class);
        }

        @Bean
        public UsageEventDao usageEventDao() {
            return Mockito.mock(UsageEventDao.class);
        }

        @Bean
        public ResourceLimitService resourceLimitMgr() {
            return Mockito.mock(ResourceLimitService.class);
        }

        @Bean
        public LaunchPermissionDao launchPermissionDao() {
            return Mockito.mock(LaunchPermissionDao.class);
        }

        @Bean
        public ProjectManager projectMgr() {
            return Mockito.mock(ProjectManager.class);
        }

        @Bean
        public VolumeDataFactory volFactory() {
            return Mockito.mock(VolumeDataFactory.class);
        }

        @Bean
        public TemplateDataFactory tmplFactory() {
            return Mockito.mock(TemplateDataFactory.class);
        }

        @Bean
        public SnapshotDataFactory snapshotFactory() {
            return Mockito.mock(SnapshotDataFactory.class);
        }

        @Bean
        public TemplateService tmpltSvr() {
            return Mockito.mock(TemplateService.class);
        }

        @Bean
        public VolumeOrchestrationService volumeMgr() {
            return Mockito.mock(VolumeOrchestrationService.class);
        }

        @Bean
        public EndPointSelector epSelector() {
            return Mockito.mock(EndPointSelector.class);
        }

        @Bean
        public UserVmJoinDao userVmJoinDao() {
            return Mockito.mock(UserVmJoinDao.class);
        }

        @Bean
        public SnapshotDataStoreDao snapshotStoreDao() {
            return Mockito.mock(SnapshotDataStoreDao.class);
        }

        @Bean
        public MessageBus messageBus() {
            return Mockito.mock(MessageBus.class);
        }

        @Bean
        public StorageCacheManager cacheMgr() {
            return Mockito.mock(StorageCacheManager.class);
        }

        @Bean
        public TemplateAdapter templateAdapter() {
            return Mockito.mock(TemplateAdapter.class);
        }

        public static class Library implements TypeFilter {
            @Override
            public boolean match(MetadataReader mdr, MetadataReaderFactory arg1) throws IOException {
                ComponentScan cs = TestConfiguration.class.getAnnotation(ComponentScan.class);
                return SpringUtils.includedInBasePackageClasses(mdr.getClassMetadata().getClassName(), cs);
            }
        }
    }
}
