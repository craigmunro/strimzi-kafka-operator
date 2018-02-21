/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.resources;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the topic controller deployment
 */
public class TopicController extends AbstractCluster {

    private static final String NAME_SUFFIX = "-topic-controller";

    // Topic Controller configuration
    protected TopicControllerConfig config;

    // Port configuration
    private static final int HEALTHCHECK_PORT = 8080;
    private static final String HEALTHCHECK_PORT_NAME = "hcheck-port";

    // Configuration defaults
    private static final String DEFAULT_IMAGE = "strimzi/topic-controller:latest";
    private static final int DEFAULT_REPLICAS = 1;
    private static final int DEFAULT_HEALTHCHECK_DELAY = 10;
    private static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    // TODO : we need the period for topic controller healthcheck or we are going to use a default one ?
    private static final int DEFAULT_ZOOKEEPER_PORT = 2181;
    private static final int DEFAULT_BOOTSTRAP_SERVERS_PORT = 9092;
    private static final String DEFAULT_FULL_RECONCILIATION_INTERVAL = "15 minutes";
    private static final String DEFAULT_ZOOKEEPER_SESSION_TIMEOUT = "20 seconds";

    // Configuration keys
    public static final String KEY_CONFIG = "topic-controller-config";

    // Topic Controller configuration keys
    public static final String KEY_KAFKA_BOOTSTRAP_SERVERS = "STRIMZI_KAFKA_BOOTSTRAP_SERVERS";
    public static final String KEY_ZOOKEEPER_CONNECT = "STRIMZI_ZOOKEEPER_CONNECT";
    public static final String KEY_NAMESPACE = "STRIMZI_NAMESPACE";
    public static final String KEY_FULL_RECONCILIATION_INTERVAL = "STRIMZI_FULL_RECONCILIATION_INTERVAL";
    public static final String KEY_ZOOKEEPER_SESSION_TIMEOUT = "STRIMZI_ZOOKEEPER_SESSION_TIMEOUT";

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where cluster resources are going to be created
     * @param cluster   overall cluster name
     */
    protected TopicController(String namespace, String cluster) {

        super(namespace, cluster);
        this.name = topicControllerName(cluster);
        this.image = DEFAULT_IMAGE;
        this.replicas = DEFAULT_REPLICAS;
        this.healthCheckPath = "/";
        this.healthCheckTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.healthCheckInitialDelay = DEFAULT_HEALTHCHECK_DELAY;

        // create a default configuration
        this.config = new TopicControllerConfig();
        this.config
                .withImage(DEFAULT_IMAGE)
                .withKafkaBootstrapServers(defaultBootstrapServers(cluster))
                .withZookeeperConnect(defaultZookeeperConnect(cluster))
                .withNamespace(namespace)
                .withReconciliationInterval(DEFAULT_FULL_RECONCILIATION_INTERVAL)
                .withZookeeperSessionTimeout(DEFAULT_ZOOKEEPER_SESSION_TIMEOUT);
    }

    public static String topicControllerName(String cluster) {
        return cluster + TopicController.NAME_SUFFIX;
    }

    private static String defaultZookeeperConnect(String cluster) {
        // TODO : exposing the ZookeeperCluster.NAME_SUFFIX as public
        return cluster + "-zookeeper" + ":" + DEFAULT_ZOOKEEPER_PORT;
    }

    private static String defaultBootstrapServers(String cluster) {
        // TODO : exposing the KafkaCluster.NAME_SUFFIX as public
        return cluster + "-kafka" + ":" + DEFAULT_BOOTSTRAP_SERVERS_PORT;
    }

    public void setConfig(TopicControllerConfig config) {
        this.config = config;
    }

    public TopicControllerConfig getConfig() {
        return this.config;
    }

    /**
     * Create a Topic Controller from the related ConfigMap resource
     *
     * @param cm ConfigMap with topic controller configuration
     * @return Topic Controller instance
     */
    public static TopicController fromConfigMap(ConfigMap cm) {

        TopicController topicController = null;

        String config = cm.getData().get(KEY_CONFIG);
        if (config != null) {
            topicController = new TopicController(cm.getMetadata().getNamespace(), cm.getMetadata().getName());

            TopicControllerConfig.fromJson(topicController.getConfig(), new JsonObject(config));
            topicController.setImage(topicController.getConfig().image());
        }

        return topicController;
    }

    public Deployment generateDeployment() {

        return createDeployment(
                Collections.singletonList(createContainerPort(HEALTHCHECK_PORT_NAME, HEALTHCHECK_PORT, "TCP")),
                createHttpProbe(healthCheckPath + "healthy", HEALTHCHECK_PORT_NAME, DEFAULT_HEALTHCHECK_DELAY, DEFAULT_HEALTHCHECK_TIMEOUT),
                createHttpProbe(healthCheckPath + "ready", HEALTHCHECK_PORT_NAME, DEFAULT_HEALTHCHECK_DELAY, DEFAULT_HEALTHCHECK_TIMEOUT),
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(new EnvVarBuilder().withName(KEY_KAFKA_BOOTSTRAP_SERVERS).withValue(this.config.kafkaBootstrapServers()).build());
        varList.add(new EnvVarBuilder().withName(KEY_ZOOKEEPER_CONNECT).withValue(this.config.zookeeperConnect()).build());
        varList.add(new EnvVarBuilder().withName(KEY_NAMESPACE).withValue(this.config.namespace()).build());
        varList.add(new EnvVarBuilder().withName(KEY_FULL_RECONCILIATION_INTERVAL).withValue(this.config.reconciliationInterval()).build());
        varList.add(new EnvVarBuilder().withName(KEY_ZOOKEEPER_SESSION_TIMEOUT).withValue(this.config.zookeeperSessionTimeout()).build());

        return varList;
    }

    @Override
    protected String getServiceAccountName() {
        return "strimzi-cluster-controller";
    }
}
