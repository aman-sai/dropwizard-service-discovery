/*
 * Copyright (c) 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.discovery.bundle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.ranger.healthcheck.HealthcheckStatus;
import com.flipkart.ranger.model.ServiceNode;
import io.appform.dropwizard.discovery.common.ShardInfo;
import io.dropwizard.Configuration;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.test.TestingCluster;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@Slf4j
public class ServiceDiscoveryBundleDwMonitorTest {

    private final HealthCheckRegistry healthChecks = new HealthCheckRegistry();
    private final JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
    private final MetricRegistry metricRegistry = mock(MetricRegistry.class);
    private final LifecycleEnvironment lifecycleEnvironment = new LifecycleEnvironment(metricRegistry);
    private final Environment environment = mock(Environment.class);
    private final Bootstrap<?> bootstrap = mock(Bootstrap.class);
    private final Configuration configuration = mock(Configuration.class);

    static {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
    }


    private final ServiceDiscoveryBundle<Configuration> bundle = new ServiceDiscoveryBundle<Configuration>() {
        @Override
        protected ServiceDiscoveryConfiguration getRangerConfiguration(Configuration configuration) {
            return serviceDiscoveryConfiguration;
        }

        @Override
        protected String getServiceName(Configuration configuration) {
            return "TestService";
        }

        @Override
        protected int getPort(Configuration configuration) {
            return 21000;
        }

        @Override
        protected String getHost() {
            return "CustomHost";
        }

    };

    private ServiceDiscoveryConfiguration serviceDiscoveryConfiguration;
    private final TestingCluster testingCluster = new TestingCluster(1);
    private final HealthcheckStatus status = HealthcheckStatus.healthy;

    @BeforeEach
    public void setup() throws Exception {
        healthChecks.register("twice-healthy-only", new HealthCheck() {
            private AtomicInteger counter = new AtomicInteger(2);
            @Override
            protected Result check() throws Exception {
                Result result = (counter.decrementAndGet() < 0) ? Result.unhealthy("unhealthy") : Result.healthy();
                log.info("Marking node as {}", result.isHealthy());
                return result;
            }
        });

        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.getObjectMapper()).thenReturn(new ObjectMapper());
        AdminEnvironment adminEnvironment = mock(AdminEnvironment.class);
        doNothing().when(adminEnvironment).addTask(any());
        when(environment.admin()).thenReturn(adminEnvironment);

        testingCluster.start();

        serviceDiscoveryConfiguration = ServiceDiscoveryConfiguration.builder()
                                                                     .zookeeper(testingCluster.getConnectString())
                                                                     .namespace("test")
                                                                     .environment("testing")
                                                                     .connectionRetryIntervalMillis(5000)
                                                                     .publishedHost("TestHost")
                                                                     .publishedPort(8021)
                                                                     .initialRotationStatus(true)
                                                                     .dropwizardCheckInterval(2)
                                                                     .dropwizardCheckStaleness(2)
                                                                     .build();
        bundle.initialize(bootstrap);
        bundle.run(configuration, environment);
        bundle.getServerStatus().markStarted();
        for (LifeCycle lifeCycle : lifecycleEnvironment.getManagedObjects()){
            lifeCycle.start();
        }
        bundle.registerHealthcheck(() -> status);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (LifeCycle lifeCycle: lifecycleEnvironment.getManagedObjects()){
            lifeCycle.stop();
        }
        testingCluster.stop();
    }

    @Test
    public void testDiscovery() throws Exception {
        Optional<ServiceNode<ShardInfo>> info = bundle.getServiceDiscoveryClient().getNode();
        Thread.sleep(1000);
        Assertions.assertTrue(info.isPresent());
        Assertions.assertEquals("testing", info.get().getNodeData().getEnvironment());
        Assertions.assertEquals("CustomHost", info.get().getHost());
        Assertions.assertEquals(21000, info.get().getPort());

        /* after 2 turns, the healthcheck will return unhealthy, and since dropwizardCheckInterval
           is 2 seconds, within 2*2=4 seconds, nodes should be absent */
        Thread.sleep(11000);
        info = bundle.getServiceDiscoveryClient().getNode();
        Assertions.assertFalse(info.isPresent());
    }
}