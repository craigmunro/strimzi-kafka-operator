/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Route;
import io.strimzi.api.kafka.KafkaAssemblyList;
import io.strimzi.api.kafka.model.CertificateAuthority;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.ExternalLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.cluster.KafkaUpgradeException;
import io.strimzi.operator.cluster.model.AbstractModel;
import io.strimzi.operator.cluster.model.ClientsCa;
import io.strimzi.operator.cluster.model.ClusterCa;
import io.strimzi.operator.cluster.model.EntityOperator;
import io.strimzi.operator.cluster.model.EntityTopicOperator;
import io.strimzi.operator.cluster.model.EntityUserOperator;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaConfiguration;
import io.strimzi.operator.cluster.model.KafkaUpgrade;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.ModelUtils;
import io.strimzi.operator.cluster.model.TopicOperator;
import io.strimzi.operator.cluster.model.ZookeeperCluster;
import io.strimzi.operator.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.cluster.operator.resource.ZookeeperSetOperator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.resource.ClusterRoleBindingOperator;
import io.strimzi.operator.common.operator.resource.ConfigMapOperator;
import io.strimzi.operator.common.operator.resource.DeploymentOperator;
import io.strimzi.operator.common.operator.resource.PvcOperator;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.common.operator.resource.RoleBindingOperator;
import io.strimzi.operator.common.operator.resource.RouteOperator;
import io.strimzi.operator.common.operator.resource.ServiceAccountOperator;
import io.strimzi.operator.common.operator.resource.ServiceOperator;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;

import static io.strimzi.operator.cluster.model.KafkaCluster.ANNO_STRIMZI_IO_FROM_VERSION;
import static io.strimzi.operator.cluster.model.KafkaCluster.ANNO_STRIMZI_IO_KAFKA_VERSION;
import static io.strimzi.operator.cluster.model.KafkaCluster.ANNO_STRIMZI_IO_TO_VERSION;
import static io.strimzi.operator.cluster.model.KafkaCluster.ENV_VAR_KAFKA_CONFIGURATION;
import static io.strimzi.operator.cluster.model.KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION;
import static io.strimzi.operator.cluster.model.KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION;
import static io.strimzi.operator.cluster.model.KafkaVersion.compareDottedVersions;

/**
 * <p>Assembly operator for a "Kafka" assembly, which manages:</p>
 * <ul>
 *     <li>A ZooKeeper cluster StatefulSet and related Services</li>
 *     <li>A Kafka cluster StatefulSet and related Services</li>
 *     <li>Optionally, a TopicOperator Deployment</li>
 * </ul>
 */
public class KafkaAssemblyOperator extends AbstractAssemblyOperator<KubernetesClient, Kafka, KafkaAssemblyList, DoneableKafka, Resource<Kafka, DoneableKafka>> {
    private static final Logger log = LogManager.getLogger(KafkaAssemblyOperator.class.getName());

    private final long operationTimeoutMs;

    private final ZookeeperSetOperator zkSetOperations;
    private final KafkaSetOperator kafkaSetOperations;
    private final ServiceOperator serviceOperations;
    private final RouteOperator routeOperations;
    private final PvcOperator pvcOperations;
    private final DeploymentOperator deploymentOperations;
    private final ConfigMapOperator configMapOperations;
    private final ServiceAccountOperator serviceAccountOperator;
    private final RoleBindingOperator roleBindingOperator;
    private final ClusterRoleBindingOperator clusterRoleBindingOperator;
    private final Map<String, String> versionMap;

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift Whether we're running with OpenShift
     */
    public KafkaAssemblyOperator(Vertx vertx, boolean isOpenShift,
                                 long operationTimeoutMs,
                                 CertManager certManager,
                                 ResourceOperatorSupplier supplier,
                                 Map<String, String> versionMap) {
        super(vertx, isOpenShift, ResourceType.KAFKA, certManager, supplier.kafkaOperator, supplier.secretOperations, supplier.networkPolicyOperator);
        this.operationTimeoutMs = operationTimeoutMs;
        this.serviceOperations = supplier.serviceOperations;
        this.routeOperations = supplier.routeOperations;
        this.zkSetOperations = supplier.zkSetOperations;
        this.kafkaSetOperations = supplier.kafkaSetOperations;
        this.configMapOperations = supplier.configMapOperations;
        this.pvcOperations = supplier.pvcOperations;
        this.deploymentOperations = supplier.deploymentOperations;
        this.serviceAccountOperator = supplier.serviceAccountOperator;
        this.roleBindingOperator = supplier.roleBindingOperator;
        this.clusterRoleBindingOperator = supplier.clusterRoleBindingOperator;
        this.versionMap = versionMap;
    }

    @Override
    public Future<Void> createOrUpdate(Reconciliation reconciliation, Kafka kafkaAssembly) {
        Future<Void> chainFuture = Future.future();
        new ReconciliationState(reconciliation, kafkaAssembly)
                .reconcileClusterCa()
                // Roll everything so the new CA is added to the trust store.
                .compose(state -> state.rollingUpdateForNewCaCert())

                .compose(state -> state.getZookeeperState())
                .compose(state -> state.zkScaleDown())
                .compose(state -> state.zkService())
                .compose(state -> state.zkHeadlessService())
                .compose(state -> state.zkAncillaryCm())
                .compose(state -> state.zkNodesSecret())
                .compose(state -> state.zkNetPolicy())
                .compose(state -> state.zkStatefulSet())
                .compose(state -> state.zkRollingUpdate())
                .compose(state -> state.zkScaleUp())
                .compose(state -> state.zkServiceEndpointReadiness())
                .compose(state -> state.zkHeadlessServiceEndpointReadiness())

                .compose(state -> state.kafkaUpgrade())
                .compose(state -> state.getKafkaClusterDescription())
                .compose(state -> state.kafkaInitServiceAccount())
                .compose(state -> state.kafkaInitClusterRoleBinding())
                .compose(state -> state.kafkaScaleDown())
                .compose(state -> state.kafkaService())
                .compose(state -> state.kafkaHeadlessService())
                .compose(state -> state.kafkaExternalBootstrapService())
                .compose(state -> state.kafkaReplicaServices())
                .compose(state -> state.kafkaBootstrapRoute())
                .compose(state -> state.kafkaReplicaRoutes())
                .compose(state -> state.kafkaExternalBootstrapServiceReady())
                .compose(state -> state.kafkaReplicaServicesReady())
                .compose(state -> state.kafkaBootstrapRouteReady())
                .compose(state -> state.kafkaReplicaRoutesReady())
                .compose(state -> state.kafkaGenerateCertificates())
                .compose(state -> state.kafkaAncillaryCm())
                .compose(state -> state.kafkaClientsCaSecret())
                .compose(state -> state.kafkaClientsPublicKeySecret())
                .compose(state -> state.kafkaClusterPublicKeySecret())
                .compose(state -> state.kafkaBrokersSecret())
                .compose(state -> state.kafkaNetPolicy())
                .compose(state -> state.kafkaStatefulSet())
                .compose(state -> state.kafkaRollingUpdate())
                .compose(state -> state.kafkaScaleUp())
                .compose(state -> state.kafkaServiceEndpointReady())
                .compose(state -> state.kafkaHeadlessServiceEndpointReady())

                .compose(state -> state.getTopicOperatorDescription())
                .compose(state -> state.topicOperatorServiceAccount())
                .compose(state -> state.topicOperatorRoleBinding())
                .compose(state -> state.topicOperatorAncillaryCm())
                .compose(state -> state.topicOperatorSecret())
                .compose(state -> state.topicOperatorDeployment())

                .compose(state -> state.getEntityOperatorDescription())
                .compose(state -> state.entityOperatorServiceAccount())
                .compose(state -> state.entityOperatorTopicOpRoleBinding())
                .compose(state -> state.entityOperatorUserOpRoleBinding())
                .compose(state -> state.entityOperatorTopicOpAncillaryCm())
                .compose(state -> state.entityOperatorUserOpAncillaryCm())
                .compose(state -> state.entityOperatorSecret())
                .compose(state -> state.entityOperatorDeployment())

                .compose(state -> chainFuture.complete(), chainFuture);

        return chainFuture;
    }

    /**
     * Hold the mutable state during a reconciliation
     */
    class ReconciliationState {

        private final String namespace;
        private final String name;
        private final Kafka kafkaAssembly;
        private final Reconciliation reconciliation;

        private ClusterCa clusterCa;
        private ClientsCa clientsCa;

        private ZookeeperCluster zkCluster;
        private Service zkService;
        private Service zkHeadlessService;
        private ConfigMap zkMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> zkDiffs;
        private boolean zkAncillaryCmChange;

        private KafkaCluster kafkaCluster = null;
        private Service kafkaService;
        private Service kafkaHeadlessService;
        private ConfigMap kafkaMetricsAndLogsConfigMap;
        private ReconcileResult<StatefulSet> kafkaDiffs;
        private String kafkaExternalBootstrapAddress;
        private SortedMap<Integer, String> kafkaExternalAddresses = new TreeMap<>();
        private boolean kafkaAncillaryCmChange;

        private TopicOperator topicOperator;
        private Deployment toDeployment = null;
        private ConfigMap toMetricsAndLogsConfigMap = null;

        private EntityOperator entityOperator;
        private Deployment eoDeployment = null;
        private ConfigMap topicOperatorMetricsAndLogsConfigMap = null;
        private ConfigMap userOperatorMetricsAndLogsConfigMap;

        ReconciliationState(Reconciliation reconciliation, Kafka kafkaAssembly) {
            this.reconciliation = reconciliation;
            this.kafkaAssembly = kafkaAssembly;
            this.namespace = kafkaAssembly.getMetadata().getNamespace();
            this.name = kafkaAssembly.getMetadata().getName();
        }

        /**
         * Asynchronously reconciles the cluster CA secret.
         * The secret has have the name determined by {@link AbstractModel#getClusterCaName(String)}.
         * Within the secret the current certificate is stored under the key {@code cluster-ca.crt}
         * and the current key is stored under the key {@code cluster-ca.crt}.
         * Old certificates which are still within their validity are stored under keys named like {@code cluster-ca-<not-after-date>.crt}, where
         * {@code <not-after-date>} is the ISO 8601-formatted date of the certificates "notAfter" attribute.
         * Likewise, the corresponding keys are stored under keys named like {@code cluster-ca-<not-after-date>.key}.
         */
        Future<ReconciliationState>  reconcileClusterCa() {
            Labels caLabels = Labels.userLabels(kafkaAssembly.getMetadata().getLabels()).withKind(reconciliation.type().toString()).withCluster(reconciliation.name());
            Future<ReconciliationState> result = Future.future();
            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        String clusterCaCertName = AbstractModel.getClusterCaName(name);
                        String clusterCaKeyName = AbstractModel.getClusterCaKeyName(name);
                        String clientsCaCertName = KafkaCluster.clientsPublicKeyName(name);
                        String clientsCaKeyName = KafkaCluster.clientsCASecretName(name);
                        Secret clusterCaCertSecret = null;
                        Secret clusterCaKeySecret = null;
                        Secret clientCaCertSecret = null;
                        Secret clientCaKeySecret = null;
                        List<Secret> clusterSecrets = secretOperations.list(reconciliation.namespace(), caLabels);
                        for (Secret secret : clusterSecrets) {
                            String secretName = secret.getMetadata().getName();
                            if (secretName.equals(clusterCaCertName)) {
                                clusterCaCertSecret = secret;
                            } else if (secretName.equals(clusterCaKeyName)) {
                                clusterCaKeySecret = secret;
                            } else if (secretName.equals(clientsCaKeyName)) {
                                clientCaCertSecret = secret;
                            } else if (secretName.equals(clientsCaCertName)) {
                                clientCaKeySecret = secret;
                            }
                        }
                        CertificateAuthority clusterCaConfig = kafkaAssembly.getSpec().getClusterCa();
                        this.clusterCa = new ClusterCa(certManager, name, clusterCaCertSecret, clusterCaKeySecret,
                                ModelUtils.getCertificateValidity(clusterCaConfig),
                                ModelUtils.getRenewalDays(clusterCaConfig),
                                clusterCaConfig == null || clusterCaConfig.isGenerateCertificateAuthority());
                        OwnerReference ownerRef = new OwnerReferenceBuilder()
                                .withApiVersion(kafkaAssembly.getApiVersion())
                                .withKind(kafkaAssembly.getKind())
                                .withName(kafkaAssembly.getMetadata().getName())
                                .withUid(kafkaAssembly.getMetadata().getUid())
                                .withBlockOwnerDeletion(false)
                                .withController(false)
                                .build();
                        clusterCa.createOrRenew(
                                reconciliation.namespace(), reconciliation.name(), caLabels.toMap(),
                                ownerRef);

                        this.clusterCa.initCaSecrets(clusterSecrets);

                        CertificateAuthority clientsCaConfig = kafkaAssembly.getSpec().getClusterCa();
                        this.clientsCa = new ClientsCa(certManager,
                                clientsCaCertName, clientCaCertSecret,
                                clientsCaKeyName, clientCaKeySecret,
                                ModelUtils.getCertificateValidity(clientsCaConfig),
                                ModelUtils.getRenewalDays(clientsCaConfig),
                                clientsCaConfig == null || clientsCaConfig.isGenerateCertificateAuthority());

                        secretOperations.reconcile(reconciliation.namespace(), clusterCaCertName, this.clusterCa.caCertSecret())
                                .compose(ignored -> secretOperations.reconcile(reconciliation.namespace(), clusterCaKeyName, this.clusterCa.caKeySecret()))
                                .compose(ignored  -> {
                                    future.complete(this);
                                }, future);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                    result.completer()
            );
            return result;
        }

        /**
         * Perform a rolling update of the cluster so that renewed CA certificates get added to their truststores,
         * or expired CA certificates get removed from their truststores .
         */
        Future<ReconciliationState> rollingUpdateForNewCaCert() {
            String r = "";
            if (this.clusterCa.certRenewed()) {
                r = "trust new CA certificate, ";
            }
            if (this.clusterCa.certsRemoved()) {
                r = r + "remove expired CA certificate";
            }
            String reason = r.trim();
            if (!reason.isEmpty()) {
                return zkSetOperations.getAsync(namespace, ZookeeperCluster.zookeeperClusterName(name))
                        .compose(ss -> {
                            log.debug("{}: Rolling StatefulSet {} to {}", reconciliation, ss.getMetadata().getName(), reason);
                            return zkSetOperations.maybeRollingUpdate(ss, true);
                        })
                        .compose(i -> kafkaSetOperations.getAsync(namespace, KafkaCluster.kafkaClusterName(name)))
                        .compose(ss -> {
                            log.debug("{}: Rolling StatefulSet {} to {}", reconciliation, ss.getMetadata().getName(), reason);
                            return kafkaSetOperations.maybeRollingUpdate(ss, true);
                        })
                        .compose(i -> {
                            if (topicOperator != null) {
                                log.debug("{}: Rolling Deployment {} to {}", reconciliation, TopicOperator.topicOperatorName(name), reason);
                                return deploymentOperations.rollingUpdate(namespace, TopicOperator.topicOperatorName(name), operationTimeoutMs);
                            } else {
                                return Future.succeededFuture();
                            }
                        })
                        .compose(i -> {
                            if (entityOperator != null) {
                                log.debug("{}: Rolling Deployment {} to {}", reconciliation, EntityOperator.entityOperatorName(name), reason);
                                return deploymentOperations.rollingUpdate(namespace, EntityOperator.entityOperatorName(name), operationTimeoutMs);
                            } else {
                                return Future.succeededFuture();
                            }
                        })
                        .map(i -> this);
            } else {
                return Future.succeededFuture(this);
            }
        }

        Future<ReconciliationState> kafkaUpgrade() {
            // Wait until the SS is not being updated (it shouldn't be, but there's no harm in checking)
            String kafkaSsName = KafkaCluster.kafkaClusterName(name);
            return kafkaSetOperations.waitForQuiescence(namespace, kafkaSsName).compose(
                ss -> {
                    if (ss == null) {
                        return Future.succeededFuture(this);
                    }
                    Future<?> result;
                    // Get the current version of the cluster
                    KafkaVersion currentVersion = KafkaVersion.version(ss.getMetadata().getAnnotations().get(KafkaCluster.ANNO_STRIMZI_IO_KAFKA_VERSION));
                    String fromVersionAnno = ss.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_FROM_VERSION);
                    KafkaVersion fromVersion;
                    if (fromVersionAnno != null) { // We're mid-upgrade
                        fromVersion = KafkaVersion.version(fromVersionAnno);
                    } else {
                        fromVersion = currentVersion;
                    }
                    String toVersionAnno = ss.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_TO_VERSION);
                    KafkaVersion toVersion;
                    if (toVersionAnno != null) { // We're mid-upgrade
                        toVersion = KafkaVersion.version(toVersionAnno);
                    } else {
                        toVersion = KafkaVersion.version(kafkaAssembly.getSpec().getKafka().getVersion());
                    }
                    KafkaUpgrade upgrade = new KafkaUpgrade(fromVersion, toVersion);
                    if (upgrade.isNoop()) {
                        log.debug("Kafka.spec.kafka.version unchanged");
                        result = Future.succeededFuture();
                    } else {
                        String image = imageForVersion(toVersion);
                        Future<StatefulSet> f = Future.succeededFuture(ss);
                        if (upgrade.isUpgrade()) {
                            if (currentVersion.equals(fromVersion)) {
                                f = f.compose(ignored -> kafkaUpgradePhase1(ss, upgrade, image));
                            }
                            result = f.compose(ss2 -> kafkaUpgradePhase2(ss2, upgrade));
                        } else {
                            if (currentVersion.equals(fromVersion)) {
                                f = f.compose(ignored -> kafkaDowngradePhase1(ss, upgrade));
                            }
                            result = f.compose(ignored -> kafkaDowngradePhase2(ss, upgrade, image));
                        }

                    }
                    return result.map(this);
                });
        }

        private String imageForVersion(KafkaVersion toVersion) {
            String i = kafkaAssembly.getSpec().getKafka().getImage();
            if (i == null) {
                i = versionMap.get(toVersion.version());
            }
            return i;
        }

        private Container getKafkaContainer(StatefulSet ss) {
            for (Container container : ss.getSpec().getTemplate().getSpec().getContainers()) {
                if ("kafka".equals(container.getName())) {
                    return container;
                }
            }
            throw new KafkaUpgradeException("Could not find 'kafka' container in StatefulSet " + ss.getMetadata().getName());
        }

        /**
         * <p>Initial upgrade phase.
         */
        private Future<StatefulSet> kafkaUpgradePhase1(StatefulSet ss, KafkaUpgrade upgrade, String upgradedImage) {
            log.info("{}: {}, phase 1", reconciliation, upgrade);

            Map<String, String> annotations = ss.getMetadata().getAnnotations();
            List<EnvVar> env = getKafkaContainer(ss).getEnv();
            EnvVar kafkaConfigEnvVar = ModelUtils.findEnv(env, ENV_VAR_KAFKA_CONFIGURATION);
            KafkaConfiguration currentKafkaConfig = KafkaConfiguration.unvalidated(kafkaConfigEnvVar != null ? kafkaConfigEnvVar.getValue() : "");

            String oldMessageFormat = currentKafkaConfig.getConfigOption(LOG_MESSAGE_FORMAT_VERSION);
            if (upgrade.requiresMessageFormatChange() &&
                    oldMessageFormat == null) {
                // Force the user to explicitly set the log.message.format.version
                // to match the old version
                throw new KafkaUpgradeException(upgrade + " requires a message format change " +
                        "from " + upgrade.from().messageVersion() + " to " + upgrade.to().messageVersion() + ". " +
                        "You must explicitly set " +
                        LOG_MESSAGE_FORMAT_VERSION + "=" + upgrade.from().messageVersion() +
                        " in Kafka.spec.kafka.config to perform the upgrade. " +
                        "Then you can upgrade client applications. " +
                        "And finally you can remove " + LOG_MESSAGE_FORMAT_VERSION +
                        " from Kafka.spec.kafka.config");
            }
            // Otherwise both versions use the same message format, so we don't care.

            String lowerVersionProtocol = currentKafkaConfig.getConfigOption(INTERBROKER_PROTOCOL_VERSION);
            boolean twoPhase;
            if (lowerVersionProtocol == null) {
                twoPhase = true;
                // Set proto version and message version in Kafka config, if they're not already set
                lowerVersionProtocol = currentKafkaConfig.getConfigOption(INTERBROKER_PROTOCOL_VERSION, upgrade.from().protocolVersion());
                log.info("{}: Upgrade: Setting {} to {}", reconciliation, INTERBROKER_PROTOCOL_VERSION, lowerVersionProtocol);
                currentKafkaConfig.setConfigOption(INTERBROKER_PROTOCOL_VERSION, lowerVersionProtocol);
                env.remove(kafkaConfigEnvVar);
                env.add(new EnvVar(ENV_VAR_KAFKA_CONFIGURATION, currentKafkaConfig.getConfiguration(), null));
                // Store upgrade state in annotations
                annotations.put(ANNO_STRIMZI_IO_FROM_VERSION, upgrade.from().version());
                annotations.put(ANNO_STRIMZI_IO_TO_VERSION, upgrade.to().version());
            } else {
                // There's no need for the next phase of update because the user has
                // inter.broker.protocol.version set explicitly: The CO shouldn't remove it.
                // We're done, so remove the annotations.
                twoPhase = false;
                log.info("{}: Upgrade: Removing annotations {}, {}",
                        reconciliation, ANNO_STRIMZI_IO_FROM_VERSION, ANNO_STRIMZI_IO_TO_VERSION);
                annotations.remove(ANNO_STRIMZI_IO_FROM_VERSION);
                annotations.remove(ANNO_STRIMZI_IO_TO_VERSION);
            }
            log.info("{}: Upgrade: Setting annotation {}={}",
                    reconciliation, KafkaCluster.ANNO_STRIMZI_IO_KAFKA_VERSION, upgrade.to().version());
            annotations.put(KafkaCluster.ANNO_STRIMZI_IO_KAFKA_VERSION, upgrade.to().version());
            // update the annotations, image and environment
            StatefulSet newSs = new StatefulSetBuilder(ss)
                    .editMetadata()
                        .withAnnotations(annotations)
                    .endMetadata()
                    .editSpec()
                        .editTemplate()
                            .editSpec()
                                .editFirstContainer()
                                    .withImage(upgradedImage)
                                    .withEnv(env)
                                .endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                .build();

            // patch and rolling upgrade
            String name = KafkaCluster.kafkaClusterName(this.name);
            log.info("{}: Upgrade: Patch + rolling update of {}", reconciliation, name);
            return kafkaSetOperations.reconcile(namespace, name, newSs)
                    .compose(result -> kafkaSetOperations.maybeRollingUpdate(ss, true).map(result.resource()))
                    .compose(ss2 -> {
                        log.info("{}: {}, phase 1 of {} completed: {}", reconciliation, upgrade,
                                twoPhase ? 2 : 1,
                                twoPhase ? "change in " + INTERBROKER_PROTOCOL_VERSION + " requires 2nd phase"
                                        : "no change to " + INTERBROKER_PROTOCOL_VERSION + " because it is explicitly configured"
                        );
                        return Future.succeededFuture(twoPhase ? ss2 : null);
                    });
        }

        /**
         * Final upgrade phase
         * Note: The log.message.format.version is left at the old version.
         * It is a manual action to remove that once the user has updated all their clients.
         */
        private Future<Void> kafkaUpgradePhase2(StatefulSet ss, KafkaUpgrade upgrade) {
            if (ss == null) {
                // It was a one-phase update
                return Future.succeededFuture();
            }
            // Cluster is now using new binaries, but old proto version
            log.info("{}: {}, phase 2", reconciliation, upgrade);
            // Remove the strimzi.io/from-version and strimzi.io/to-version since this is the last phase
            Map<String, String> annotations = ss.getMetadata().getAnnotations();
            log.info("{}: Upgrade: Removing annotations {}, {}",
                    reconciliation, ANNO_STRIMZI_IO_FROM_VERSION, ANNO_STRIMZI_IO_TO_VERSION);
            annotations.remove(ANNO_STRIMZI_IO_FROM_VERSION);
            annotations.remove(ANNO_STRIMZI_IO_TO_VERSION);

            // Remove inter.broker.protocol.version (so the new version's default is used)
            List<EnvVar> env = getKafkaContainer(ss).getEnv();
            EnvVar kafkaConfigEnvVar = ModelUtils.findEnv(env, ENV_VAR_KAFKA_CONFIGURATION);
            KafkaConfiguration currentKafkaConfig = KafkaConfiguration.unvalidated(kafkaConfigEnvVar.getValue());

            log.info("{}: Upgrade: Removing Kafka config {}, will default to {}",
                    reconciliation, INTERBROKER_PROTOCOL_VERSION, upgrade.to().protocolVersion());
            currentKafkaConfig.removeConfigOption(INTERBROKER_PROTOCOL_VERSION);
            env.remove(kafkaConfigEnvVar);
            env.add(new EnvVar(ENV_VAR_KAFKA_CONFIGURATION, currentKafkaConfig.getConfiguration(), null));

            // Update to new proto version and rolling upgrade
            currentKafkaConfig.removeConfigOption(INTERBROKER_PROTOCOL_VERSION);

            StatefulSet newSs = new StatefulSetBuilder(ss)
                    .editMetadata()
                        .withAnnotations(annotations)
                    .endMetadata()
                    .editSpec()
                        .editTemplate()
                            .editSpec()
                                .editFirstContainer()
                                    .withEnv(env)
                                .endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            // Reconcile the SS and perform a rolling update of the pods
            log.info("{}: Upgrade: Patch + rolling update of {}", reconciliation, name);
            return kafkaSetOperations.reconcile(namespace, KafkaCluster.kafkaClusterName(name), newSs)
                    .compose(ignored -> kafkaSetOperations.maybeRollingUpdate(ss, true))
                    .compose(ignored -> {
                        log.info("{}: {}, phase 2 of 2 completed", reconciliation, upgrade);
                        return Future.succeededFuture();
                    });
        }

        /**
         * <p>Initial downgrade phase.
         * <ol>
         *     <li>Set the log.message.format.version to the old version</li>
         *     <li>Set the inter.broker.protocol.version to the old version</li>
         *     <li>Set the strimzi.io/upgrade-phase=1 (to record progress of the upgrade in case of CO failure)</li>
         *     <li>Reconcile the SS and perform a rolling update of the pods</li>
         * </ol>
         */
        private Future<StatefulSet> kafkaDowngradePhase1(StatefulSet ss, KafkaUpgrade upgrade) {
            log.info("{}: {}, phase 1", reconciliation, upgrade);

            Map<String, String> annotations = ss.getMetadata().getAnnotations();
            List<EnvVar> env = getKafkaContainer(ss).getEnv();
            EnvVar kafkaConfigEnvVar = ModelUtils.findEnv(env, ENV_VAR_KAFKA_CONFIGURATION);
            KafkaConfiguration currentKafkaConfig = KafkaConfiguration.unvalidated(kafkaConfigEnvVar != null ? kafkaConfigEnvVar.getValue() : "");

            String oldMessageFormat = currentKafkaConfig.getConfigOption(LOG_MESSAGE_FORMAT_VERSION);
            // Force the user to explicitly set log.message.format.version
            // (Controller shouldn't break clients)
            if (oldMessageFormat == null || !oldMessageFormat.equals(upgrade.to().messageVersion())) {
                throw new KafkaUpgradeException(
                        String.format("Cannot downgrade Kafka cluster %s in namespace %s to version %s " +
                                        "because the current cluster is configured with %s=%s. " +
                                        "Downgraded brokers would not be able to understand existing " +
                                        "messages with the message version %s. ",
                                name, namespace, upgrade.to(),
                                LOG_MESSAGE_FORMAT_VERSION, oldMessageFormat,
                                oldMessageFormat));
            }

            String lowerVersionProtocol = currentKafkaConfig.getConfigOption(INTERBROKER_PROTOCOL_VERSION);
            String phases;
            if (lowerVersionProtocol == null
                    || compareDottedVersions(lowerVersionProtocol, upgrade.to().protocolVersion()) > 0) {
                phases = "2 (change in " + INTERBROKER_PROTOCOL_VERSION + " requires 2nd phase)";
                // Set proto version and message version in Kafka config, if they're not already set
                lowerVersionProtocol = currentKafkaConfig.getConfigOption(INTERBROKER_PROTOCOL_VERSION, upgrade.to().protocolVersion());
                log.info("{}: Downgrade: Setting {} to {}", reconciliation, INTERBROKER_PROTOCOL_VERSION, lowerVersionProtocol);
                currentKafkaConfig.setConfigOption(INTERBROKER_PROTOCOL_VERSION, lowerVersionProtocol);
                env.remove(kafkaConfigEnvVar);
                env.add(new EnvVar(ENV_VAR_KAFKA_CONFIGURATION, currentKafkaConfig.getConfiguration(), null));
                // Store upgrade state in annotations
                annotations.put(ANNO_STRIMZI_IO_FROM_VERSION, upgrade.from().version());
                annotations.put(ANNO_STRIMZI_IO_TO_VERSION, upgrade.to().version());
            } else {
                // In this case there's no need for this phase of update, because the both old and new
                // brokers speaking protocol of the lower version.
                phases = "2 (1st phase skips rolling update)";
                log.info("{}: {}, phase 1 of {} completed", reconciliation, upgrade, phases);
                return Future.succeededFuture(ss);
            }

            // update the annotations, image and environment
            StatefulSet newSs = new StatefulSetBuilder(ss)
                    .editMetadata()
                        .withAnnotations(annotations)
                    .endMetadata()
                    .editSpec()
                        .editTemplate()
                            .editSpec()
                                .editFirstContainer()
                                    .withEnv(env)
                                .endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            // patch and rolling upgrade
            String name = KafkaCluster.kafkaClusterName(this.name);
            log.info("{}: Downgrade: Patch + rolling update of {}", reconciliation, name);
            return kafkaSetOperations.reconcile(namespace, name, newSs)
                    .compose(result -> kafkaSetOperations.maybeRollingUpdate(ss, true).map(result.resource()))
                    .compose(ss2 -> {
                        log.info("{}: {}, phase 1 of {} completed", reconciliation, upgrade, phases);
                        return Future.succeededFuture(ss2);
                    });
        }

        /**
         * <p>Final downgrade phase
         * <ol>
         *     <li>Update the strimzi.io/kafka-version to the new version</li>
         *     <li>Remove the strimzi.io/from-kafka-version since this is the last phase</li>
         *     <li>Remove the strimzi.io/to-kafka-version since this is the last phase</li>
         *     <li>Remove inter.broker.protocol.version (so the new version's default is used)</li>
         *     <li>Update the image in the SS</li>
         *     <li>Reconcile the SS and perform a rolling update of the pods</li>
         * </ol>
         */
        private Future<Void> kafkaDowngradePhase2(StatefulSet ss, KafkaUpgrade downgrade, String downgradedImage) {
            log.info("{}: {}, phase 2", reconciliation, downgrade);
            // Remove the strimzi.io/from-version and strimzi.io/to-version since this is the last phase

            Map<String, String> annotations = ss.getMetadata().getAnnotations();

            log.info("{}: Upgrade: Removing annotations {}, {}",
                    reconciliation, ANNO_STRIMZI_IO_FROM_VERSION, ANNO_STRIMZI_IO_TO_VERSION);
            annotations.remove(ANNO_STRIMZI_IO_FROM_VERSION);
            annotations.remove(ANNO_STRIMZI_IO_TO_VERSION);
            annotations.put(ANNO_STRIMZI_IO_KAFKA_VERSION, downgrade.to().version());

            // Remove inter.broker.protocol.version (so the new version's default is used)
            List<EnvVar> env = getKafkaContainer(ss).getEnv();
            EnvVar kafkaConfigEnvVar = ModelUtils.findEnv(env, ENV_VAR_KAFKA_CONFIGURATION);
            KafkaConfiguration currentKafkaConfig = KafkaConfiguration.unvalidated(kafkaConfigEnvVar.getValue());
            log.info("{}: Upgrade: Removing Kafka config {}, will default to {}",
                    reconciliation, INTERBROKER_PROTOCOL_VERSION, downgrade.to().protocolVersion());
            currentKafkaConfig.removeConfigOption(INTERBROKER_PROTOCOL_VERSION);
            env.remove(kafkaConfigEnvVar);
            env.add(new EnvVar(ENV_VAR_KAFKA_CONFIGURATION, currentKafkaConfig.getConfiguration(), null));

            StatefulSet newSs = new StatefulSetBuilder(ss)
                    .editMetadata()
                        .withAnnotations(annotations)
                    .endMetadata()
                    .editSpec()
                        .editTemplate()
                            .editSpec()
                                .editFirstContainer()
                                    .withImage(downgradedImage)
                                    .withEnv(env)
                                .endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            // Reconcile the SS and perform a rolling update of the pods
            log.info("{}: Upgrade: Patch + rolling update of {}", reconciliation, name);
            return kafkaSetOperations.reconcile(namespace, KafkaCluster.kafkaClusterName(name), newSs)
                    .compose(ignored -> kafkaSetOperations.maybeRollingUpdate(ss, true))
                    .compose(ignored -> {
                        log.info("{}: {}, phase 2 of 2 completed", reconciliation, downgrade);
                        return Future.succeededFuture();
                    });
        }

        Future<ReconciliationState> getZookeeperState() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    try {
                        this.zkCluster = ZookeeperCluster.fromCrd(kafkaAssembly);

                        ConfigMap logAndMetricsConfigMap = zkCluster.generateMetricsAndLogConfigMap(zkCluster.getLogging() instanceof ExternalLogging ?
                                configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) zkCluster.getLogging()).getName()) :
                                null);

                        this.zkService = zkCluster.generateService();
                        this.zkHeadlessService = zkCluster.generateHeadlessService();
                        this.zkMetricsAndLogsConfigMap = zkCluster.generateMetricsAndLogConfigMap(logAndMetricsConfigMap);

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete((ReconciliationState) res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );

            return fut;
        }

        Future<ReconciliationState> withZkDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.zkDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withVoid(Future<?> r) {
            return r.map(this);
        }

        Future<ReconciliationState> zkScaleDown() {
            return withVoid(zkSetOperations.scaleDown(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getServiceName(), zkService));
        }

        Future<ReconciliationState> zkHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, zkCluster.getHeadlessServiceName(), zkHeadlessService));
        }

        Future<ReconciliationState> getReconciliationStateOfConfigMap(AbstractModel cluster, ConfigMap configMap, BiFunction<Boolean, Future<ReconcileResult<ConfigMap>>, Future<ReconciliationState>> function) {
            Future<ReconciliationState> result = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<Boolean>executeBlocking(
                future -> {
                    ConfigMap current = configMapOperations.get(namespace, cluster.getAncillaryConfigName());
                    boolean onlyMetricsSettingChanged = onlyMetricsSettingChanged(current, configMap);
                    future.complete(onlyMetricsSettingChanged);
                }, res -> {
                    if (res.succeeded())  {
                        boolean onlyMetricsSettingChanged = res.result();
                        function.apply(onlyMetricsSettingChanged, configMapOperations.reconcile(namespace, cluster.getAncillaryConfigName(), configMap)).setHandler(res2 -> {
                            if (res2.succeeded())   {
                                result.complete(res2.result());
                            } else {
                                result.fail(res2.cause());
                            }
                        });
                    } else {
                        result.fail(res.cause());
                    }
                });
            return result;
        }

        Future<ReconciliationState> zkAncillaryCm() {
            return getReconciliationStateOfConfigMap(zkCluster, zkMetricsAndLogsConfigMap, this::withZkAncillaryCmChanged);
        }

        Future<ReconciliationState> zkNodesSecret() {
            return withVoid(secretOperations.reconcile(namespace, ZookeeperCluster.nodesSecretName(name),
                    zkCluster.generateNodesSecret(clusterCa, kafkaAssembly)));
        }

        Future<ReconciliationState> zkNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, ZookeeperCluster.policyName(name), zkCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> zkStatefulSet() {
            return withZkDiff(zkSetOperations.reconcile(namespace, zkCluster.getName(), zkCluster.generateStatefulSet(isOpenShift)));
        }

        Future<ReconciliationState> zkRollingUpdate() {
            if (log.isDebugEnabled()) {
                String reason = "";
                if (this.clusterCa.certRenewed()) {
                    reason += "cluster CA certificate renewal, ";
                }
                if (this.clusterCa.certsRemoved()) {
                    reason += "cluster CA certificate removal, ";
                }
                if (zkAncillaryCmChange) {
                    reason += "ancillary CM change, ";
                }
                if (!reason.isEmpty()) {
                    log.debug("{}: Rolling ZK SS due to {}", reconciliation, reason.substring(0, reason.length() - 2));
                }
            }
            return withVoid(zkSetOperations.maybeRollingUpdate(zkDiffs.resource(),
                    zkAncillaryCmChange || this.clusterCa.certRenewed() || this.clusterCa.certsRemoved()));
        }

        Future<ReconciliationState> zkScaleUp() {
            return withVoid(zkSetOperations.scaleUp(namespace, zkCluster.getName(), zkCluster.getReplicas()));
        }

        Future<ReconciliationState> zkServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> zkHeadlessServiceEndpointReadiness() {
            return withVoid(serviceOperations.endpointReadiness(namespace, zkHeadlessService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> withZkAncillaryCmChanged(boolean onlyMetricsSettingChanged, Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                if (onlyMetricsSettingChanged) {
                    log.debug("Only metrics setting changed - not triggering rolling update");
                    this.zkAncillaryCmChange = false;
                } else {
                    this.zkAncillaryCmChange = rr instanceof ReconcileResult.Patched;
                }
                return this;
            });
        }

        private Future<ReconciliationState> getKafkaClusterDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.kafkaCluster = KafkaCluster.fromCrd(kafkaAssembly);

                        ConfigMap logAndMetricsConfigMap = kafkaCluster.generateMetricsAndLogConfigMap(
                                kafkaCluster.getLogging() instanceof ExternalLogging ?
                                        configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) kafkaCluster.getLogging()).getName()) :
                                        null);
                        this.kafkaService = kafkaCluster.generateService();
                        this.kafkaHeadlessService = kafkaCluster.generateHeadlessService();
                        this.kafkaMetricsAndLogsConfigMap = logAndMetricsConfigMap;

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> withKafkaDiff(Future<ReconcileResult<StatefulSet>> r) {
            return r.map(rr -> {
                this.kafkaDiffs = rr;
                return this;
            });
        }

        Future<ReconciliationState> withKafkaAncillaryCmChanged(boolean onlyMetricsSettingChanged, Future<ReconcileResult<ConfigMap>> r) {
            return r.map(rr -> {
                if (onlyMetricsSettingChanged) {
                    log.debug("Only metrics setting changed - not triggering rolling update");
                    this.kafkaAncillaryCmChange = false;
                } else {
                    this.kafkaAncillaryCmChange = rr instanceof ReconcileResult.Patched;
                }
                return this;
            });
        }

        Future<ReconciliationState> kafkaInitServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    KafkaCluster.initContainerServiceAccountName(kafkaCluster.getCluster()),
                    kafkaCluster.generateInitContainerServiceAccount()));
        }

        Future<ReconciliationState> kafkaInitClusterRoleBinding() {
            ClusterRoleBindingOperator.ClusterRoleBinding desired = kafkaCluster.generateClusterRoleBinding(namespace);
            Future<Void> fut = clusterRoleBindingOperator.reconcile(
                    KafkaCluster.initContainerClusterRoleBindingName(namespace, name),
                    desired);

            Future replacementFut = Future.future();

            fut.setHandler(res -> {
                if (res.failed())    {
                    if (desired == null && res.cause().getMessage().contains("403: Forbidden")) {
                        log.debug("Ignoring forbidden access to ClusterRoleBindings which seems not needed while Kafka rack awareness is disabled.");
                        replacementFut.complete();
                    } else {
                        replacementFut.fail(res.cause());
                    }
                } else {
                    replacementFut.complete();
                }
            });

            return withVoid(replacementFut);
        }

        Future<ReconciliationState> kafkaScaleDown() {
            return withVoid(kafkaSetOperations.scaleDown(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getServiceName(), kafkaService));
        }

        Future<ReconciliationState> kafkaHeadlessService() {
            return withVoid(serviceOperations.reconcile(namespace, kafkaCluster.getHeadlessServiceName(), kafkaHeadlessService));
        }

        Future<ReconciliationState> kafkaExternalBootstrapService() {
            return withVoid(serviceOperations.reconcile(namespace, KafkaCluster.externalBootstrapServiceName(name), kafkaCluster.generateExternalBootstrapService()));
        }

        Future<ReconciliationState> kafkaReplicaServices() {
            int replicas = kafkaCluster.getReplicas();
            List<Future> serviceFutures = new ArrayList<>(replicas);

            for (int i = 0; i < replicas; i++) {
                serviceFutures.add(serviceOperations.reconcile(namespace, KafkaCluster.externalServiceName(name, i), kafkaCluster.generateExternalService(i)));
            }

            return withVoid(CompositeFuture.join(serviceFutures));
        }

        Future<ReconciliationState> kafkaBootstrapRoute() {
            Route route = kafkaCluster.generateExternalBootstrapRoute();

            if (routeOperations != null) {
                return withVoid(routeOperations.reconcile(namespace, KafkaCluster.serviceName(name), route));
            } else if (route != null)   {
                log.warn("{}: Exposing Kafka cluster {} using OpenShift Routes is available only on OpenShift", reconciliation, name);
                return withVoid(Future.failedFuture("Exposing Kafka cluster " + name + " using OpenShift Routes is available only on OpenShift"));
            }

            return withVoid(Future.succeededFuture());
        }

        Future<ReconciliationState> kafkaReplicaRoutes() {
            int replicas = kafkaCluster.getReplicas();
            List<Future> routeFutures = new ArrayList<>(replicas);

            for (int i = 0; i < replicas; i++) {
                Route route = kafkaCluster.generateExternalRoute(i);

                if (routeOperations != null) {
                    routeFutures.add(routeOperations.reconcile(namespace, KafkaCluster.externalServiceName(name, i), route));
                } else if (route != null)   {
                    log.warn("{}: Exposing Kafka cluster {} using OpenShift Routes is available only on OpenShift", reconciliation, name);
                    return withVoid(Future.failedFuture("Exposing Kafka cluster " + name + " using OpenShift Routes is available only on OpenShift"));
                }
            }

            return withVoid(CompositeFuture.join(routeFutures));
        }

        Future<ReconciliationState> kafkaExternalBootstrapServiceReady() {
            if (!kafkaCluster.isExposedWithLoadBalancer() && !kafkaCluster.isExposedWithNodePort()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    String serviceName = KafkaCluster.externalBootstrapServiceName(name);
                    Future<Void> address = null;

                    if (kafkaCluster.isExposedWithNodePort()) {
                        address = serviceOperations.hasNodePort(namespace, serviceName, 1_000, operationTimeoutMs);
                    } else {
                        address = serviceOperations.hasIngressAddress(namespace, serviceName, 1_000, operationTimeoutMs);
                    }

                    address.setHandler(res -> {
                        if (res.succeeded()) {
                            if (kafkaCluster.isExposedWithLoadBalancer()) {
                                if (serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname() != null) {
                                    this.kafkaExternalBootstrapAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname();
                                } else {
                                    this.kafkaExternalBootstrapAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getIp();
                                }
                            } else if (kafkaCluster.isExposedWithNodePort()) {
                                this.kafkaExternalBootstrapAddress = serviceOperations.get(namespace, serviceName).getSpec().getPorts().get(0).getNodePort().toString();
                            }

                            if (log.isTraceEnabled()) {
                                log.trace("{}: Found address {} for Service {}", reconciliation, this.kafkaExternalBootstrapAddress, serviceName);
                            }

                            future.complete();
                        } else {
                            log.warn("{}: No address found for Service {}", reconciliation, serviceName);
                            future.fail("No address found for Service " + serviceName);
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaReplicaServicesReady() {
            if (!kafkaCluster.isExposedWithLoadBalancer() && !kafkaCluster.isExposedWithNodePort()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    int replicas = kafkaCluster.getReplicas();
                    List<Future> routeFutures = new ArrayList<>(replicas);

                    for (int i = 0; i < replicas; i++) {
                        String serviceName = KafkaCluster.externalServiceName(name, i);
                        Future routeFuture = Future.future();

                        Future<Void> address = null;

                        if (kafkaCluster.isExposedWithNodePort()) {
                            address = serviceOperations.hasNodePort(namespace, serviceName, 1_000, operationTimeoutMs);
                        } else {
                            address = serviceOperations.hasIngressAddress(namespace, serviceName, 1_000, operationTimeoutMs);
                        }

                        int podNumber = i;

                        address.setHandler(res -> {
                            if (res.succeeded()) {
                                String serviceAddress = null;
                                if (kafkaCluster.isExposedWithLoadBalancer()) {
                                    if (serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname() != null) {
                                        serviceAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getHostname();
                                    } else {
                                        serviceAddress = serviceOperations.get(namespace, serviceName).getStatus().getLoadBalancer().getIngress().get(0).getIp();
                                    }
                                } else if (kafkaCluster.isExposedWithNodePort()) {
                                    serviceAddress = serviceOperations.get(namespace, serviceName).getSpec().getPorts().get(0).getNodePort().toString();
                                }

                                this.kafkaExternalAddresses.put(podNumber, serviceAddress);

                                if (log.isTraceEnabled()) {
                                    log.trace("{}: Found address {} for Service {}", reconciliation, serviceAddress, serviceName);
                                }

                                routeFuture.complete();
                            } else {
                                log.warn("{}: No address found for Service {}", reconciliation, serviceName);
                                routeFuture.fail("No address found for Service " + serviceName);
                            }
                        });

                        routeFutures.add(routeFuture);
                    }

                    CompositeFuture.join(routeFutures).setHandler(res -> {
                        if (res.succeeded()) {
                            future.complete();
                        } else  {
                            future.fail(res.cause());
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaBootstrapRouteReady() {
            if (routeOperations == null || !kafkaCluster.isExposedWithRoute()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    String routeName = KafkaCluster.serviceName(name);
                    //Future future = Future.future();
                    Future<Void> address = routeOperations.hasAddress(namespace, routeName, 1_000, operationTimeoutMs);

                    address.setHandler(res -> {
                        if (res.succeeded()) {
                            this.kafkaExternalBootstrapAddress = routeOperations.get(namespace, routeName).getSpec().getHost();

                            if (log.isTraceEnabled()) {
                                log.trace("{}: Found address {} for Route {}", reconciliation, routeOperations.get(namespace, routeName).getSpec().getHost(), routeName);
                            }

                            future.complete();
                        } else {
                            log.warn("{}: No address found for Route {}", reconciliation, routeName);
                            future.fail("No address found for Route " + routeName);
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaReplicaRoutesReady() {
            if (routeOperations == null || !kafkaCluster.isExposedWithRoute()) {
                return withVoid(Future.succeededFuture());
            }

            Future blockingFuture = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {
                    int replicas = kafkaCluster.getReplicas();
                    List<Future> routeFutures = new ArrayList<>(replicas);

                    for (int i = 0; i < replicas; i++) {
                        String routeName = KafkaCluster.externalServiceName(name, i);
                        Future routeFuture = Future.future();
                        Future<Void> address = routeOperations.hasAddress(namespace, routeName, 1_000, operationTimeoutMs);
                        int podNumber = i;

                        address.setHandler(res -> {
                            if (res.succeeded()) {
                                this.kafkaExternalAddresses.put(podNumber, routeOperations.get(namespace, routeName).getSpec().getHost());

                                if (log.isTraceEnabled()) {
                                    log.trace("{}: Found address {} for Route {}", reconciliation, routeOperations.get(namespace, routeName).getSpec().getHost(), routeName);
                                }

                                routeFuture.complete();
                            } else {
                                log.warn("{}: No address found for Route {}", reconciliation, routeName);
                                routeFuture.fail("No address found for Route " + routeName);
                            }
                        });

                        routeFutures.add(routeFuture);
                    }

                    CompositeFuture.join(routeFutures).setHandler(res -> {
                        if (res.succeeded()) {
                            future.complete();
                        } else  {
                            future.fail(res.cause());
                        }
                    });
                }, res -> {
                    if (res.succeeded())    {
                        blockingFuture.complete();
                    } else  {
                        blockingFuture.fail(res.cause());
                    }
                });

            return withVoid(blockingFuture);
        }

        Future<ReconciliationState> kafkaGenerateCertificates() {
            Future<ReconciliationState> result = Future.future();
            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        if (kafkaCluster.isExposedWithNodePort()) {
                            kafkaCluster.generateCertificates(kafkaAssembly,
                                    clusterCa, clientsCa, null, Collections.EMPTY_MAP);
                        } else {
                            kafkaCluster.generateCertificates(kafkaAssembly,
                                    clusterCa, clientsCa, kafkaExternalBootstrapAddress, kafkaExternalAddresses);
                        }
                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                },
                true,
                result.completer());
            return result;
        }

        Future<ReconciliationState> kafkaAncillaryCm() {
            return getReconciliationStateOfConfigMap(kafkaCluster, kafkaMetricsAndLogsConfigMap, this::withKafkaAncillaryCmChanged);
        }

        Future<ReconciliationState> kafkaClientsCaSecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clientsCASecretName(name), clientsCa.caKeySecret()));
        }

        Future<ReconciliationState> kafkaClientsPublicKeySecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clientsPublicKeyName(name), clientsCa.caCertSecret()));
        }

        Future<ReconciliationState> kafkaClusterPublicKeySecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.clusterPublicKeyName(name), clusterCa.caCertSecret()));
        }

        Future<ReconciliationState> kafkaBrokersSecret() {
            return withVoid(secretOperations.reconcile(namespace, KafkaCluster.brokersSecretName(name), kafkaCluster.generateBrokersSecret()));
        }

        Future<ReconciliationState> kafkaNetPolicy() {
            return withVoid(networkPolicyOperator.reconcile(namespace, KafkaCluster.policyName(name), kafkaCluster.generateNetworkPolicy()));
        }

        Future<ReconciliationState> kafkaStatefulSet() {
            kafkaCluster.setExternalAddresses(kafkaExternalAddresses);
            return withKafkaDiff(kafkaSetOperations.reconcile(namespace, kafkaCluster.getName(), kafkaCluster.generateStatefulSet(isOpenShift)));
        }

        Future<ReconciliationState> kafkaRollingUpdate() {
            if (log.isDebugEnabled()) {
                String reason = "";
                if (this.clusterCa.certRenewed()) {
                    reason += "cluster CA certificate renewal, ";
                }
                if (this.clusterCa.certsRemoved()) {
                    reason += "cluster CA certificate removal, ";
                }
                if (kafkaAncillaryCmChange) {
                    reason += "ancillary CM change, ";
                }
                if (!reason.isEmpty()) {
                    log.debug("{}: Rolling Kafka SS due to {}", reconciliation, reason.substring(0, reason.length() - 2));
                }
            }
            return withVoid(kafkaSetOperations.maybeRollingUpdate(kafkaDiffs.resource(),
                    kafkaAncillaryCmChange || this.clusterCa.certRenewed() || this.clusterCa.certsRemoved()));
        }

        Future<ReconciliationState> kafkaScaleUp() {
            return withVoid(kafkaSetOperations.scaleUp(namespace, kafkaCluster.getName(), kafkaCluster.getReplicas()));
        }

        Future<ReconciliationState> kafkaServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaService, 1_000, operationTimeoutMs));
        }

        Future<ReconciliationState> kafkaHeadlessServiceEndpointReady() {
            return withVoid(serviceOperations.endpointReadiness(namespace, kafkaHeadlessService, 1_000, operationTimeoutMs));
        }

        private final Future<ReconciliationState> getTopicOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        this.topicOperator = TopicOperator.fromCrd(kafkaAssembly);

                        if (topicOperator != null) {
                            ConfigMap logAndMetricsConfigMap = topicOperator.generateMetricsAndLogConfigMap(
                                    topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null);
                            this.toDeployment = topicOperator.generateDeployment();
                            this.toMetricsAndLogsConfigMap = logAndMetricsConfigMap;
                            this.toDeployment.getSpec().getTemplate().getMetadata().getAnnotations().put("strimzi.io/logging", this.toMetricsAndLogsConfigMap.getData().get("log4j2.properties"));
                        } else {
                            this.toDeployment = null;
                            this.toMetricsAndLogsConfigMap = null;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> topicOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    TopicOperator.topicOperatorServiceAccountName(name),
                    toDeployment != null ? topicOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> topicOperatorRoleBinding() {
            String watchedNamespace = topicOperator != null ? topicOperator.getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    TopicOperator.roleBindingName(name),
                    toDeployment != null ? topicOperator.generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> topicOperatorAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    toDeployment != null ? topicOperator.getAncillaryConfigName() : TopicOperator.metricAndLogConfigsName(name),
                    toMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> topicOperatorDeployment() {
            return withVoid(deploymentOperations.reconcile(namespace, TopicOperator.topicOperatorName(name), toDeployment)
                .compose(reconcileResult -> {
                    if (this.clusterCa.certRenewed()  && reconcileResult instanceof ReconcileResult.Noop<?>) {
                        // roll the TO if the cluster CA was renewed and reconcile hasn't rolled it
                        log.debug("{}: Restarting Topic Operator due to Cluster CA renewal", reconciliation);
                        return deploymentOperations.rollingUpdate(namespace, TopicOperator.topicOperatorName(name), operationTimeoutMs);
                    } else {
                        return Future.succeededFuture();
                    }
                }));
        }

        Future<ReconciliationState> topicOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, TopicOperator.secretName(name), topicOperator == null ? null : topicOperator.generateSecret(clusterCa)));
        }

        private final Future<ReconciliationState> getEntityOperatorDescription() {
            Future<ReconciliationState> fut = Future.future();

            vertx.createSharedWorkerExecutor("kubernetes-ops-pool").<ReconciliationState>executeBlocking(
                future -> {
                    try {
                        EntityOperator entityOperator = EntityOperator.fromCrd(kafkaAssembly);

                        if (entityOperator != null) {
                            EntityTopicOperator topicOperator = entityOperator.getTopicOperator();
                            EntityUserOperator userOperator = entityOperator.getUserOperator();

                            ConfigMap topicOperatorLogAndMetricsConfigMap = topicOperator != null ?
                                    topicOperator.generateMetricsAndLogConfigMap(topicOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) topicOperator.getLogging()).getName()) :
                                            null) : null;

                            ConfigMap userOperatorLogAndMetricsConfigMap = userOperator != null ?
                                    userOperator.generateMetricsAndLogConfigMap(userOperator.getLogging() instanceof ExternalLogging ?
                                            configMapOperations.get(kafkaAssembly.getMetadata().getNamespace(), ((ExternalLogging) userOperator.getLogging()).getName()) :
                                            null) : null;

                            this.entityOperator = entityOperator;
                            this.eoDeployment = entityOperator.generateDeployment();
                            this.topicOperatorMetricsAndLogsConfigMap = topicOperatorLogAndMetricsConfigMap;
                            this.userOperatorMetricsAndLogsConfigMap = userOperatorLogAndMetricsConfigMap;
                        }

                        future.complete(this);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                }, true,
                res -> {
                    if (res.succeeded()) {
                        fut.complete(res.result());
                    } else {
                        fut.fail(res.cause());
                    }
                }
            );
            return fut;
        }

        Future<ReconciliationState> entityOperatorServiceAccount() {
            return withVoid(serviceAccountOperator.reconcile(namespace,
                    EntityOperator.entityOperatorServiceAccountName(name),
                    eoDeployment != null ? entityOperator.generateServiceAccount() : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getTopicOperator() != null ?
                    entityOperator.getTopicOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityTopicOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorUserOpRoleBinding() {
            String watchedNamespace = entityOperator != null && entityOperator.getUserOperator() != null ?
                    entityOperator.getUserOperator().getWatchedNamespace() : null;
            return withVoid(roleBindingOperator.reconcile(
                    watchedNamespace != null && !watchedNamespace.isEmpty() ?
                            watchedNamespace : namespace,
                    EntityUserOperator.roleBindingName(name),
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().generateRoleBinding(namespace) : null));
        }

        Future<ReconciliationState> entityOperatorTopicOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getTopicOperator() != null ?
                            entityOperator.getTopicOperator().getAncillaryConfigName() : EntityTopicOperator.metricAndLogConfigsName(name),
                    topicOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorUserOpAncillaryCm() {
            return withVoid(configMapOperations.reconcile(namespace,
                    eoDeployment != null && entityOperator.getUserOperator() != null ?
                            entityOperator.getUserOperator().getAncillaryConfigName() : EntityUserOperator.metricAndLogConfigsName(name),
                    userOperatorMetricsAndLogsConfigMap));
        }

        Future<ReconciliationState> entityOperatorDeployment() {
            return withVoid(deploymentOperations.reconcile(namespace, EntityOperator.entityOperatorName(name), eoDeployment)
                .compose(reconcileResult -> {
                    if (this.clusterCa.certRenewed() && reconcileResult instanceof ReconcileResult.Noop<?>) {
                        // roll the EO if the cluster CA was renewed and reconcile hasn't rolled it
                        log.debug("{}: Restarting Entity Operator due to Cluster CA renewal", reconciliation);
                        return deploymentOperations.rollingUpdate(namespace, EntityOperator.entityOperatorName(name), operationTimeoutMs);
                    } else {
                        return Future.succeededFuture();
                    }
                }));
        }

        Future<ReconciliationState> entityOperatorSecret() {
            return withVoid(secretOperations.reconcile(namespace, EntityOperator.secretName(name),
                    entityOperator == null ? null : entityOperator.generateSecret(clusterCa)));
        }
    }

    private final Future<CompositeFuture> deleteKafka(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete kafka {}", reconciliation, name);
        String kafkaSsName = KafkaCluster.kafkaClusterName(name);
        StatefulSet ss = kafkaSetOperations.get(namespace, kafkaSsName);
        int replicas = ss != null ? ss.getSpec().getReplicas() : 0;
        boolean deleteClaims = ss == null ? false : KafkaCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(deleteClaims ? replicas : 0);

        if (deleteClaims) {
            log.debug("{}: delete kafka {} PVCs", reconciliation, name);

            for (int i = 0; i < replicas; i++) {
                result.add(pvcOperations.reconcile(namespace,
                        KafkaCluster.getPersistentVolumeClaimName(kafkaSsName, i), null));
            }
        }

        return CompositeFuture.join(result);
    }

    private final Future<CompositeFuture> deleteZk(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.name();
        log.debug("{}: delete zookeeper {}", reconciliation, name);
        String zkSsName = ZookeeperCluster.zookeeperClusterName(name);
        StatefulSet ss = zkSetOperations.get(namespace, zkSsName);
        boolean deleteClaims = ss == null ? false : ZookeeperCluster.deleteClaim(ss);
        List<Future> result = new ArrayList<>(deleteClaims ? ss.getSpec().getReplicas() : 0);

        if (deleteClaims) {
            log.debug("{}: delete zookeeper {} PVCs", reconciliation, name);
            for (int i = 0; i < ss.getSpec().getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace, ZookeeperCluster.getPersistentVolumeClaimName(zkSsName, i), null));
            }
        }

        return CompositeFuture.join(result);
    }

    @Override
    protected Future<Void> delete(Reconciliation reconciliation) {
        return deleteKafka(reconciliation)
                .compose(i -> deleteZk(reconciliation))
                .map((Void) null);
    }

    @Override
    protected List<HasMetadata> getResources(String namespace, Labels selector) {
        // TODO: Search for PVCs!
        return Collections.EMPTY_LIST;
    }
}