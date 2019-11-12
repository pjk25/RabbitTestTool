package com.jackvanlightly.rabbittesttool;

import com.jackvanlightly.rabbittesttool.clients.ConnectionSettings;
import com.jackvanlightly.rabbittesttool.clients.consumers.ConsumerGroup;
import com.jackvanlightly.rabbittesttool.clients.publishers.PublisherGroup;
import com.jackvanlightly.rabbittesttool.model.MessageModel;
import com.jackvanlightly.rabbittesttool.register.BenchmarkRegister;
import com.jackvanlightly.rabbittesttool.register.StepStatistics;
import com.jackvanlightly.rabbittesttool.statistics.Stats;
import com.jackvanlightly.rabbittesttool.topology.QueueGroup;
import com.jackvanlightly.rabbittesttool.topology.model.*;
import com.jackvanlightly.rabbittesttool.topology.TopologyGenerator;
import com.jackvanlightly.rabbittesttool.topology.model.consumers.ConsumerConfig;
import com.jackvanlightly.rabbittesttool.topology.model.publishers.PublisherConfig;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Orchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(Orchestrator.class);
    private BenchmarkRegister benchmarkRegister;
    private TopologyGenerator topologyGenerator;
    private ConnectionSettings connectionSettingsTemplate;
    private Stats stats;
    private MessageModel messageModel;

    private List<QueueGroup> queueGroups;
    private List<PublisherGroup> publisherGroups;
    private List<ConsumerGroup> consumerGroups;

    public Orchestrator(TopologyGenerator topologyGenerator,
                        BenchmarkRegister benchmarkRegister,
                        ConnectionSettings connectionSettings,
                        Stats stats,
                        MessageModel messageModel) {
        this.benchmarkRegister = benchmarkRegister;
        this.topologyGenerator = topologyGenerator;
        this.connectionSettingsTemplate = connectionSettings;
        this.stats = stats;

        this.messageModel = messageModel;
        queueGroups = new ArrayList<>();
        publisherGroups = new ArrayList<>();
        consumerGroups = new ArrayList<>();
    }

    public boolean runBenchmark(String runId,
                                Topology topology,
                                List<String> nodes,
                                Duration gracePeriod) {
        try {
            initialSetup(topology, nodes);
            switch(topology.getTopologyType()) {
                case Fixed:
                    performFixedBenchmark(runId, topology, gracePeriod);
                    break;
                case SingleVariable:
                    performSingleVariableBenchmark(runId, topology, gracePeriod);
                    break;
                case MultiVariable:
                    performMultiVariableBenchmark(runId, topology, gracePeriod);
                    break;
                default:
                    throw new RuntimeException("Non-supported topology type");
            }
            return true;
        } catch(Exception e) {
            LOGGER.error("Failed running benchmark", e);
            return false;
        }
        finally {
            LOGGER.info("Clean up started");
            cleanUp();
            LOGGER.info("Clean up complete");
        }
    }

    private void initialSetup(Topology topology, List<String> nodes) {
        for(VirtualHost vhost : topology.getVirtualHosts()) {
            topologyGenerator.declareVHost(vhost);
            topologyGenerator.declareExchanges(vhost);
            topologyGenerator.declarePolicies(vhost.getName(), topology.getPolicies());

            addQueueGroups(vhost, nodes);
            addPublisherGroups(vhost, topology);
            addConsumerGroups(vhost, topology);
            stats.addClientGroups(publisherGroups, consumerGroups);
        }
    }

    private void addQueueGroups(VirtualHost vhost, List<String> nodes) {
        for(QueueConfig queueConfig : vhost.getQueues()) {
            QueueGroup queueGroup = new QueueGroup(vhost.getName(), queueConfig, nodes, topologyGenerator);
            queueGroup.createInitialQueues();
            queueGroups.add(queueGroup);
        }
    }

    private void addPublisherGroups(VirtualHost vhost, Topology topology) {
        for(PublisherConfig publisherConfig : vhost.getPublishers()) {

            double publisherMaxScale = publisherConfig.getScale();
            switch(topology.getTopologyType()) {
                case SingleVariable:
                    if (topology.getVariableConfig().getDimension() == VariableDimension.Publishers) {
                        if (topology.getVariableConfig().getGroup() == null
                                || topology.getVariableConfig().getGroup().equals(publisherConfig.getGroup())) {
                            publisherMaxScale = topology.getVariableConfig().getMaxScale();
                        }
                    }
                    break;
                case MultiVariable:
                    Map<VariableDimension,Double> maxScales = topology.getVariableConfig().getMaxScales();
                    if(maxScales.containsKey(VariableDimension.Publishers)) {
                        if (topology.getVariableConfig().getGroup() == null
                                || topology.getVariableConfig().getGroup().equals(publisherConfig.getGroup())) {
                            publisherMaxScale = maxScales.get(VariableDimension.Publishers);
                        }
                    }
                    break;
            }

            PublisherGroup publisherGroup = new PublisherGroup(connectionSettingsTemplate.getClone(vhost.getName()),
                    publisherConfig,
                    vhost,
                    stats,
                    messageModel,
                    (int)publisherMaxScale);
            publisherGroup.createInitialPublishers();
            publisherGroups.add(publisherGroup);
        }
    }

    private void addConsumerGroups(VirtualHost vhost, Topology topology) {
        for(ConsumerConfig consumerConfig : vhost.getConsumers()) {
            double consumerMaxScale = consumerConfig.getScale();

            switch(topology.getTopologyType()) {
                case SingleVariable:
                    if(topology.getVariableConfig().getDimension() == VariableDimension.Consumers) {
                        if (topology.getVariableConfig().getGroup() == null
                                || topology.getVariableConfig().getGroup().equals(consumerConfig.getGroup())) {
                            consumerMaxScale = topology.getVariableConfig().getMaxScale();
                        }
                    }
                    break;
                case MultiVariable:
                    Map<VariableDimension,Double> maxScales = topology.getVariableConfig().getMaxScales();
                    if(maxScales.containsKey(VariableDimension.Consumers)) {
                        consumerMaxScale = (maxScales.get(VariableDimension.Consumers));
                    }
                    break;
            }

            ConsumerGroup consumerGroup = new ConsumerGroup(connectionSettingsTemplate.getClone(vhost.getName()),
                    consumerConfig,
                    vhost,
                    stats,
                    messageModel,
                    (int)consumerMaxScale);
            consumerGroup.createInitialConsumers();
            consumerGroups.add(consumerGroup);
        }
    }

    private void setCountStats() {
        setConsumerCountStats();
        setPublisherCountStats();
        setQueueCountStats();
        setTargetPublisherRateStats();
        setPublisherInFlightLimitStats();
    }

    private void setConsumerCountStats() {
        int totalConsumers = consumerGroups.stream().map(x -> x.getConsumerCount()).reduce(0, Integer::sum);
        stats.setConsumerCount(totalConsumers);
    }

    private void setPublisherCountStats() {
        int totalPublishers = publisherGroups.stream().map(x -> x.getPublisherCount()).reduce(0, Integer::sum);
        stats.setPublisherCount(totalPublishers);
    }

    private void setQueueCountStats() {
        int totalQueues = queueGroups.stream().map(x -> x.getQueueCount()).reduce(0, Integer::sum);
        stats.setQueueCount(totalQueues);
    }

    private void setTargetPublisherRateStats() {
        int totalPublishers = publisherGroups.stream()
                .map(x -> x.getPublisherCount())
                .reduce(0, Integer::sum);

        int averageTargetRate = publisherGroups.stream()
                .map(x -> x.getPublisherCount() * x.getPublishRatePerSecond())
                .reduce(0, Integer::sum) / totalPublishers;
        stats.setTargetPublishRate(averageTargetRate);
    }

    private void setPublisherInFlightLimitStats() {
        int totalPublishers = publisherGroups.stream()
                .map(x -> x.getPublisherCount())
                .reduce(0, Integer::sum);

        int averageInFlightLimit = publisherGroups.stream()
                .map(x -> x.getPublisherCount() * x.getInFlightLimit())
                .reduce(0, Integer::sum) / totalPublishers;
        stats.setPublisherInFlightLimit(averageInFlightLimit);
    }

    private void performFixedBenchmark(String runId,
                                       Topology topology,
                                       Duration gracePeriod) {
        FixedConfig fixedConfig = topology.getFixedConfig();
        setCountStats();

        // start the publishers and consumers
        for(ConsumerGroup consumerGroup : consumerGroups)
            consumerGroup.startInitialConsumers();

        for(PublisherGroup publisherGroup : publisherGroups)
            publisherGroup.startInitialPublishers();

        int step = 1;
        while(step <= fixedConfig.getStepRepeat()) {
            // wait for the ramp up time before recording and timing the run
            waitFor(fixedConfig.getStepRampUpSeconds() * 1000);

            // start recording and log start
            stats.startRecordingStep();
            benchmarkRegister.logStepStart(runId, step, topology.getFixedConfig().getDurationSeconds(), null);

            StopWatch sw = new StopWatch();
            sw.start();

            // wait for the duration second to pass
            while (sw.getTime(TimeUnit.SECONDS) < fixedConfig.getDurationSeconds()) {
                waitFor(1000);
                StepStatistics liveStepStats = stats.readCurrentStepStatistics(fixedConfig.getDurationSeconds());
                benchmarkRegister.logLiveStatistics(runId, step, liveStepStats);
            }

            // stop recording and log step results
            stats.stopRecordingStep();
            benchmarkRegister.logStepEnd(runId, step, stats.getStepStatistics(fixedConfig.getDurationSeconds()));

            step++;
        }

        initiateCleanStop(gracePeriod);
    }

    private void performSingleVariableBenchmark(String runId,
                                                Topology topology,
                                                Duration gracePeriod) throws IOException {
        VariableConfig variableConfig = topology.getVariableConfig();

        // initialize dimenion values as step 1 values. Acts as an extra ramp up.
        if(variableConfig.getValueType() == ValueType.Value)
            setSingleDimensionStepValue(variableConfig, variableConfig.getDimension(), variableConfig.getValues().get(0));
        setCountStats();

        // start the publishers and consumers
        for(ConsumerGroup consumerGroup : consumerGroups)
            consumerGroup.startInitialConsumers();

        for(PublisherGroup publisherGroup : publisherGroups)
            publisherGroup.startInitialPublishers();

        int step = 1;

        // execute each step
        for (int i = 0; i < variableConfig.getStepCount(); i++) {
            int counter = 0;
            while(counter < variableConfig.getStepRepeat()) {
                // configure step dimension
                setSingleDimensionStepValue(variableConfig,
                        variableConfig.getDimension(),
                        variableConfig.getValues().get(i));

                setCountStats();

                // wait for the ramp up time before recording and timing the step
                waitFor(variableConfig.getStepRampUpSeconds() * 1000);
                benchmarkRegister.logStepStart(runId, step, topology.getVariableConfig().getStepDurationSeconds(), variableConfig.getValues().get(i).toString());
                stats.startRecordingStep();

                StopWatch sw = new StopWatch();
                sw.start();

                // wait for step duration seconds to pass
                while (sw.getTime(TimeUnit.SECONDS) < variableConfig.getStepDurationSeconds()) {
                    waitFor(1000);
                    StepStatistics liveStepStats = stats.readCurrentStepStatistics(variableConfig.getStepDurationSeconds());
                    benchmarkRegister.logLiveStatistics(runId, step, liveStepStats);
                }

                stats.stopRecordingStep();
                benchmarkRegister.logStepEnd(runId, step, stats.getStepStatistics(variableConfig.getStepDurationSeconds()));

                step++;
                counter++;
            }
        }

        initiateCleanStop(gracePeriod);
    }

    private void performMultiVariableBenchmark(String runId,
                                               Topology topology,
                                               Duration gracePeriod) throws IOException {
        List<String> dimStr = Arrays.stream(topology.getVariableConfig().getMultiDimensions()).map(x -> String.valueOf(x)).collect(Collectors.toList());
        LOGGER.info("Multi-variable with dimensions: " + String.join(" ", dimStr));

        // initialize dimenion values as step 1 values. Acts as an extra ramp up.
        VariableConfig variableConfig = topology.getVariableConfig();
        for(int vd=0; vd <variableConfig.getMultiDimensions().length; vd++) {
            setSingleDimensionStepValue(variableConfig,
                    variableConfig.getMultiDimensions()[vd],
                    variableConfig.getMultiValues().get(0)[vd]);
        }

        setCountStats();

        // start the publishers and consumers
        for(ConsumerGroup consumerGroup : consumerGroups)
            consumerGroup.startInitialConsumers();

        for(PublisherGroup publisherGroup : publisherGroups)
            publisherGroup.startInitialPublishers();

        int step = 1;
        // execute each step
        for(int i=0; i< variableConfig.getStepCount(); i++) {

            int counter = 0;
            while(counter < variableConfig.getStepRepeat()) {
                String stepValues = "";
                for (int vd = 0; vd < variableConfig.getMultiDimensions().length; vd++) {
                    setSingleDimensionStepValue(variableConfig,
                            variableConfig.getMultiDimensions()[vd],
                            variableConfig.getMultiValues().get(i)[vd]);

                    if (vd > 0)
                        stepValues += ",";
                    stepValues += variableConfig.getMultiValues().get(i)[vd].toString();
                }

                setCountStats();

                // wait for the ramp up time before recording and timing the step
                waitFor(variableConfig.getStepRampUpSeconds() * 1000);
                benchmarkRegister.logStepStart(runId, step, topology.getVariableConfig().getStepDurationSeconds(), stepValues);
                stats.startRecordingStep();

                StopWatch sw = new StopWatch();
                sw.start();

                // wait for step duration seconds to pass
                while (sw.getTime(TimeUnit.SECONDS) < variableConfig.getStepDurationSeconds()) {
                    waitFor(1000);
                    StepStatistics liveStepStats = stats.readCurrentStepStatistics(variableConfig.getStepDurationSeconds());
                    benchmarkRegister.logLiveStatistics(runId, step, liveStepStats);
                }

                stats.stopRecordingStep();
                benchmarkRegister.logStepEnd(runId, step, stats.getStepStatistics(variableConfig.getStepDurationSeconds()));

                step++;
                counter++;
            }
        }

        initiateCleanStop(gracePeriod);
    }

    private void initiateCleanStop(Duration gracePeriod) {
        LOGGER.info("Signalling publishers to stop");
        for(PublisherGroup publisherGroup : publisherGroups)
            publisherGroup.stopAllPublishers();

        if(!gracePeriod.equals(Duration.ZERO)) {
            LOGGER.info("Started grace period of " + gracePeriod.getSeconds() + " seconds for consumers to catch up");
            Instant starts = Instant.now();

            while(Duration.between(starts, Instant.now()).getSeconds() < gracePeriod.getSeconds()) {
                waitFor(1000);
                if(messageModel.allMessagesReceived())
                    break;
            }
            LOGGER.info("Grace period complete");
        }

        LOGGER.info("Signalling consumers to stop");
        for(ConsumerGroup consumerGroup : consumerGroups)
            consumerGroup.stopAllConsumers();
    }

    private void setSingleDimensionStepValue(VariableConfig variableConfig, VariableDimension dimension, double value) throws IOException {
        switch(dimension) {
            case Consumers:
                nextConsumerStep(variableConfig, (int)value);
                break;
            case Publishers:
                nextPublisherStep(variableConfig, (int)value);
                break;
            case Queues:
                nextQueueStep(variableConfig, (int)value);
                break;
            case ConsumerAckInterval:
                nextConsumerAckIntervalStep(variableConfig, (int)value);
                break;
            case ConsumerPrefetch:
                nextConsumerPrefetchStep(variableConfig, (int)value);
                break;
            case PublisherInFlightLimit:
                nextPublisherInFlightLimitStep(variableConfig, (int)value);
                break;
            case RoutingKeyIndex:
                nextRoutingKeyIndexStep(variableConfig, (int)value);
                break;
            case PublishRatePerPublisher:
                nextPublishRatePerSecondStep(variableConfig, value);
                break;
            case MessageHeaders:
                nextMessageHeadersStep(variableConfig, (int)value);
                break;
            case MessageSize:
                nextMessageSizeStep(variableConfig, (int)value);
                break;
            case ProcessingMs:
                nextProcessingMsStep(variableConfig, (int)value);
                break;
            default:
                LOGGER.error("Dimension " + dimension + " has no step implemented");
                break;
        }
    }

    private void nextConsumerStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in CONSUMERS variable dimension with value: " + value);
        for(ConsumerGroup consumerGroup : this.consumerGroups) {
            if(variableConfig.getGroup() == null) {
                while(consumerGroup.getConsumerCount() < value)
                    consumerGroup.addAndStartConsumer();
            }
            else if(consumerGroup.getGroup().equals(variableConfig.getGroup())) {
                while(consumerGroup.getConsumerCount() < value)
                    consumerGroup.addAndStartConsumer();
            }
        }
    }

    private void nextPublisherStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in PUBLISHERS variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null) {
                while(publisherGroup.getPublisherCount() < value)
                    publisherGroup.addAndStartPublisher();
            }
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup())) {
                while(publisherGroup.getPublisherCount() < value)
                    publisherGroup.addAndStartPublisher();
            }
        }
    }

    private void nextQueueStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in QUEUES variable dimension with value: " + value);
        for(QueueGroup queueGroup : this.queueGroups) {
            if(variableConfig.getGroup() == null) {
                while(queueGroup.getQueueCount() < value)
                    addQueue(queueGroup);
            }
            else if(queueGroup.getGroup().equals(variableConfig.getGroup())) {
                while(queueGroup.getQueueCount() < value)
                    addQueue(queueGroup);
            }
        }
    }

    private void addQueue(QueueGroup queueGroup) {
        // add to the queue group which also creates the queue physically
        String queueName = queueGroup.addQueue();

        // broadcast the queue group change to all publisher groups
        for (PublisherGroup publisherGroup : publisherGroups)
            publisherGroup.addQueue(queueGroup.getGroup(), queueName);

        // broadcast the queue group change to all consumer groups
        for(ConsumerGroup consumerGroup : consumerGroups)
            consumerGroup.addQueue(queueGroup.getGroup(), queueName);
    }

    private void nextConsumerPrefetchStep(VariableConfig variableConfig, int value) throws IOException{
        LOGGER.info("Next step in CONSUMER PREFETCH variable dimension with value: " + value);
        for(ConsumerGroup consumerGroup : this.consumerGroups) {
            if(variableConfig.getGroup() == null) {
                consumerGroup.setConsumerPrefetch(value);
            }
            else if(consumerGroup.getGroup().equals(variableConfig.getGroup())) {
                consumerGroup.setConsumerPrefetch(value);
            }
        }
    }

    private void nextConsumerAckIntervalStep(VariableConfig variableConfig, int value) throws IOException{
        LOGGER.info("Next step in CONSUMER ACKS variable dimension with value: " + value);
        for(ConsumerGroup consumerGroup : this.consumerGroups) {
            if(variableConfig.getGroup() == null) {
                consumerGroup.setAckInterval(value);
            }
            else if(consumerGroup.getGroup().equals(variableConfig.getGroup())) {
                consumerGroup.setAckInterval(value);
            }
        }
    }

    private void nextMessageHeadersStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in HEADERS variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null)
                publisherGroup.setMessageHeaders(value);
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup()))
                publisherGroup.setMessageHeaders(value);
        }
    }

    private void nextMessageSizeStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in MESSAGE SIZE variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null)
                publisherGroup.setMessageSize(value);
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup()))
                publisherGroup.setMessageSize(value);
        }
    }

    private void nextPublisherInFlightLimitStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in IN FLIGHT LIMIT variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null)
                publisherGroup.setInFlightLimit(value);
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup()))
                publisherGroup.setInFlightLimit(value);
        }
    }

    private void nextRoutingKeyIndexStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in ROUTING KEY INDEX variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null)
                publisherGroup.setRoutingKeyIndex(value);
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup()))
                publisherGroup.setRoutingKeyIndex(value);
        }
    }

    private void nextProcessingMsStep(VariableConfig variableConfig, int value) {
        LOGGER.info("Next step in PROCESSING MS variable dimension with value: " + value);
        for(ConsumerGroup consumerGroup : this.consumerGroups) {
            if(variableConfig.getGroup() == null) {
                consumerGroup.setProcessingMs(value);
            }
            else if(consumerGroup.getGroup().equals(variableConfig.getGroup())) {
                consumerGroup.setProcessingMs(value);
            }
        }
    }

    private void nextPublishRatePerSecondStep(VariableConfig variableConfig, double value) {
        LOGGER.info("Next step in PUBLISH RATE variable dimension with value: " + value);
        for(PublisherGroup publisherGroup : this.publisherGroups) {
            if(variableConfig.getGroup() == null) {
                if(variableConfig.getValueType().equals(ValueType.Value))
                    publisherGroup.setPublishRatePerSecond((int)value);
                else
                    publisherGroup.modifyPublishRatePerSecond(value);
            }
            else if(publisherGroup.getGroup().equals(variableConfig.getGroup())) {
                if (variableConfig.getValueType().equals(ValueType.Value))
                    publisherGroup.setPublishRatePerSecond((int) value);
                else
                    publisherGroup.modifyPublishRatePerSecond(value);
            }
        }
    }


    private void cleanUp() {
        waitFor(5000);

        LOGGER.info("Shutting down thread pools");
        for(PublisherGroup publisherGroup : this.publisherGroups)
            publisherGroup.shutdown();

        for(ConsumerGroup consumerGroup : this.consumerGroups)
            consumerGroup.shutdown();

        LOGGER.info("Waiting for thread pools to terminate");
        for(PublisherGroup publisherGroup : this.publisherGroups)
            publisherGroup.awaitTermination();

        for(ConsumerGroup consumerGroup : this.consumerGroups)
            consumerGroup.awaitTermination();
    }

    private void waitFor(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}