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

package org.apache.cloudstack.cluster;

import com.cloud.api.query.dao.HostJoinDao;
import com.cloud.api.query.vo.HostJoinVO;
import com.cloud.host.Host;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import javax.naming.ConfigurationException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.cloudstack.cluster.ClusterDrsService.ClusterDrsMetric;
import static org.apache.cloudstack.cluster.ClusterDrsService.ClusterDrsThreshold;

@RunWith(MockitoJUnitRunner.class)
public class BalancedTest {

    @InjectMocks
    Balanced balanced;

    VirtualMachine vm1, vm2, vm3;

    Host destHost;

    HostJoinVO host1, host2;

    long clusterId = 1L;

    Map<Long, List<VirtualMachine>> hostVmMap;

    @Mock
    private ServiceOfferingDao serviceOfferingDao;

    @Mock
    private HostJoinDao hostJoinDao;

    private AutoCloseable closeable;

    @Before
    public void setUp() throws NoSuchFieldException, IllegalAccessException {
        closeable = MockitoAnnotations.openMocks(this);


        vm1 = Mockito.mock(VirtualMachine.class);
        vm2 = Mockito.mock(VirtualMachine.class);
        vm3 = Mockito.mock(VirtualMachine.class); // vm to migrate

        destHost = Mockito.mock(Host.class);
        host1 = Mockito.mock(HostJoinVO.class); // Dest host
        host2 = Mockito.mock(HostJoinVO.class);

        hostVmMap = new HashMap<>();
        hostVmMap.put(1L, Collections.singletonList(vm1));
        hostVmMap.put(2L, Arrays.asList(vm2, vm3));

        ServiceOfferingVO serviceOffering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(vm3.getHostId()).thenReturn(2L);

        Mockito.when(hostJoinDao.searchByIds(1L, 2L)).thenReturn(Arrays.asList(host1, host2));

        Mockito.when(host1.getId()).thenReturn(1L);
        Mockito.when(host1.getCpuUsedCapacity()).thenReturn(1L);
        Mockito.when(host1.getMemUsedCapacity()).thenReturn(512L);

        Mockito.when(host2.getId()).thenReturn(2L);
        Mockito.when(host2.getCpuUsedCapacity()).thenReturn(2L);
        Mockito.when(host2.getMemUsedCapacity()).thenReturn(2048L);

        Mockito.when(destHost.getId()).thenReturn(1L);
        Mockito.when(destHost.getTotalMemory()).thenReturn(8192L);

        Mockito.when(vm3.getId()).thenReturn(3L);
        Mockito.when(vm3.getHostId()).thenReturn(2L);
        Mockito.when(vm3.getServiceOfferingId()).thenReturn(1L);

        Mockito.when(serviceOffering.getCpu()).thenReturn(1);
        Mockito.when(serviceOffering.getRamSize()).thenReturn(512);

        Mockito.when(serviceOfferingDao.findByIdIncludingRemoved(3L, 1L)).thenReturn(serviceOffering);
        overrideDefaultConfigValue(ClusterDrsThreshold, "_defaultValue", "0.5");
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String name, final Object o) throws IllegalAccessException, NoSuchFieldException {
        Field f = ConfigKey.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(configKey, o);
    }

    /**
     * <b>getMetrics tests</b>
     * <p>Scenarios to test for needsDrs
     * <p>1. cluster with cpu metric
     * <p>2. cluster with memory metric
     * <p>3. cluster with "both" metric
     * <p>4. cluster with "either" metric
     * <p>5. cluster with "unknown" metric
     * <p>
     * <p>CPU imbalance = 0.333
     * <p>Memory imbalance = 0.6
     */

    /*
     1. cluster with cpu metric
     0.3333 > 0.5 -> False
    */
    @Test
    public void needsDrsWithCpu() throws ConfigurationException, NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "cpu");
//        assertFalse(balanced.needsDrs(clusterId, hostVmMap));
    }

    /*
     2. cluster with memory metric
     0.6 > 0.5 -> True
    */
    @Test
    public void needsDrsWithMemory() throws ConfigurationException, NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "memory");
//        assertTrue(balanced.needsDrs(clusterId, hostVmMap));
    }

    /*
     3. cluster with "both" metric
     0.3333 > 0.5 && 0.6 > 0.5 -> False
    */
    @Test
    public void needsDrsWithBoth() throws ConfigurationException, NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "both");
//        assertFalse(balanced.needsDrs(clusterId, hostVmMap));
    }

    /*
     4. cluster with "either" metric
     0.3333 > 0.5 || 0.6 > 0.5 -> True
    */
    @Test
    public void needsDrsWithEither() throws ConfigurationException, NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "either");
//        assertTrue(balanced.needsDrs(clusterId, hostVmMap));
    }

    /* 5. cluster with "unknown" metric */
    @Test
    public void needsDrsWithUnknown() throws ConfigurationException, NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "unknown");
//        assertThrows(ConfigurationException.class, () -> balanced.needsDrs(clusterId, hostVmMap));
    }

    /**
     * getMetrics tests
     * <p>Scenarios to test for getMetrics
     * <p>1. cluster with cpu metric
     * <p>2. cluster with memory metric
     * <p>3. cluster with default metric
     * <p>
     * <p>Pre
     * <p>CPU imbalance = 0.333333
     * <p>Memory imbalance = 0.6
     * <p>
     * <p>Post
     * <p>CPU imbalance = 0.3333
     * <p>Memory imbalance = 0.2
     * <p>
     * <p>Cost 512.0
     * <p>Benefit  (0.6-0.2) * 8192 = 3276.8
     */

    /*
     1. cluster with cpu metric
     improvement = 0.3333 - 0.3333  = 0.0
    */
    @Test
    public void getMetricsWithCpu() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "cpu");
//        Ternary<Double, Double, Double> result = balanced.getMetrics(clusterId, hostVmMap, vm3, destHost, false);
//        assertEquals(0.0, result.first(), 0.0001);
//        assertEquals(512.0, result.second(), 0.0);
//        assertEquals(3276.8, result.third(), 0.001);
    }

    /*
     2. cluster with memory metric
     improvement = 0.6 - 0.2 = 0.4
    */
    @Test
    public void getMetricsWithMemory() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "memory");
//        Ternary<Double, Double, Double> result = balanced.getMetrics(clusterId, hostVmMap, vm3, destHost, false);
//        assertEquals(0.4, result.first(), 0.01);
//        assertEquals(512.0, result.second(), 0.0);
//        assertEquals(3276.8, result.third(), 0.001);
    }

    /*
     3. cluster with default metric
     improvement = 0.3333 + 0.6 - 0.3333 - 0.2 = 0.4
    */
    @Test
    public void getMetricsWithDefault() throws NoSuchFieldException, IllegalAccessException {
        overrideDefaultConfigValue(ClusterDrsMetric, "_defaultValue", "both");
//        Ternary<Double, Double, Double> result = balanced.getMetrics(clusterId, hostVmMap, vm3, destHost, false);
//        assertEquals(0.4, result.first(), 0.001);
//        assertEquals(512.0, result.second(), 0.0);
//        assertEquals(3276.8, result.third(), 0.001);
    }
}
