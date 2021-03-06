/**
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
package org.apache.pulsar.admin.cli;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.common.naming.TopicName.DEFAULT_NAMESPACE;
import static org.apache.pulsar.common.naming.TopicName.PUBLIC_TENANT;
import static org.apache.pulsar.functions.utils.Utils.BUILTIN;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.StringConverter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.pulsar.admin.cli.utils.CmdUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.internal.FunctionsImpl;
import org.apache.pulsar.common.io.ConnectorDefinition;
import org.apache.pulsar.common.nar.NarClassLoader;
import org.apache.pulsar.functions.instance.AuthenticationConfig;
import org.apache.pulsar.functions.utils.*;
import org.apache.pulsar.functions.utils.io.ConnectorUtils;
import org.apache.pulsar.functions.utils.io.Connectors;

@Getter
@Parameters(commandDescription = "Interface for managing Pulsar IO sinks (egress data from Pulsar)")
@Slf4j
public class CmdSinks extends CmdBase {

    private final CreateSink createSink;
    private final UpdateSink updateSink;
    private final DeleteSink deleteSink;
    private final ListSinks listSinks;
    private final GetSink getSink;
    private final GetSinkStatus getSinkStatus;
    private final StopSink stopSink;
    private final RestartSink restartSink;
    private final LocalSinkRunner localSinkRunner;

    public CmdSinks(PulsarAdmin admin) {
        super("sink", admin);
        createSink = new CreateSink();
        updateSink = new UpdateSink();
        deleteSink = new DeleteSink();
        listSinks = new ListSinks();
        getSink = new GetSink();
        getSinkStatus = new GetSinkStatus();
        stopSink = new StopSink();
        restartSink = new RestartSink();
        localSinkRunner = new LocalSinkRunner();

        jcommander.addCommand("create", createSink);
        jcommander.addCommand("update", updateSink);
        jcommander.addCommand("delete", deleteSink);
        jcommander.addCommand("list", listSinks);
        jcommander.addCommand("get", getSink);
        jcommander.addCommand("getstatus", getSinkStatus);
        jcommander.addCommand("stop", stopSink);
        jcommander.addCommand("restart", restartSink);
        jcommander.addCommand("localrun", localSinkRunner);
        jcommander.addCommand("available-sinks", new ListBuiltInSinks());
    }

    /**
     * Base command
     */
    @Getter
    abstract class BaseCommand extends CliCommand {
        @Override
        void run() throws Exception {
            processArguments();
            runCmd();
        }

        void processArguments() throws Exception {
        }

        abstract void runCmd() throws Exception;
    }

    @Parameters(commandDescription = "Run a Pulsar IO sink connector locally (rather than deploying it to the Pulsar cluster)")
    protected class LocalSinkRunner extends CreateSink {

        @Parameter(names = "--brokerServiceUrl", description = "The URL for the Pulsar broker", hidden = true)
        protected String DEPRECATED_brokerServiceUrl;
        @Parameter(names = "--broker-service-url", description = "The URL for the Pulsar broker")
        protected String brokerServiceUrl;

        @Parameter(names = "--clientAuthPlugin", description = "Client authentication plugin using which function-process can connect to broker", hidden = true)
        protected String DEPRECATED_clientAuthPlugin;
        @Parameter(names = "--client-auth-plugin", description = "Client authentication plugin using which function-process can connect to broker")
        protected String clientAuthPlugin;

        @Parameter(names = "--clientAuthParams", description = "Client authentication param", hidden = true)
        protected String DEPRECATED_clientAuthParams;
        @Parameter(names = "--client-auth-params", description = "Client authentication param")
        protected String clientAuthParams;

        @Parameter(names = "--use_tls", description = "Use tls connection", hidden = true)
        protected Boolean DEPRECATED_useTls;
        @Parameter(names = "--use-tls", description = "Use tls connection")
        protected boolean useTls;

        @Parameter(names = "--tls_allow_insecure", description = "Allow insecure tls connection", hidden = true)
        protected Boolean DEPRECATED_tlsAllowInsecureConnection;
        @Parameter(names = "--tls-allow-insecure", description = "Allow insecure tls connection")
        protected boolean tlsAllowInsecureConnection;

        @Parameter(names = "--hostname_verification_enabled", description = "Enable hostname verification", hidden = true)
        protected Boolean DEPRECATED_tlsHostNameVerificationEnabled;
        @Parameter(names = "--hostname-verification-enabled", description = "Enable hostname verification")
        protected boolean tlsHostNameVerificationEnabled;

        @Parameter(names = "--tls_trust_cert_path", description = "tls trust cert file path", hidden = true)
        protected String DEPRECATED_tlsTrustCertFilePath;
        @Parameter(names = "--tls-trust-cert-path", description = "tls trust cert file path")
        protected String tlsTrustCertFilePath;

        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_brokerServiceUrl)) brokerServiceUrl = DEPRECATED_brokerServiceUrl;
            if (!StringUtils.isBlank(DEPRECATED_clientAuthPlugin)) clientAuthPlugin = DEPRECATED_clientAuthPlugin;
            if (!StringUtils.isBlank(DEPRECATED_clientAuthParams)) clientAuthParams = DEPRECATED_clientAuthParams;
            if (DEPRECATED_useTls != null) useTls = DEPRECATED_useTls;
            if (DEPRECATED_tlsAllowInsecureConnection != null) tlsAllowInsecureConnection = DEPRECATED_tlsAllowInsecureConnection;
            if (DEPRECATED_tlsHostNameVerificationEnabled != null) tlsHostNameVerificationEnabled = DEPRECATED_tlsHostNameVerificationEnabled;
            if (!StringUtils.isBlank(DEPRECATED_tlsTrustCertFilePath)) tlsTrustCertFilePath = DEPRECATED_tlsTrustCertFilePath;
        }

        @Override
        void runCmd() throws Exception {
            // merge deprecated args with new args
            mergeArgs();

            CmdFunctions.startLocalRun(createSinkConfigProto2(sinkConfig), sinkConfig.getParallelism(),
                    0, brokerServiceUrl, null,
                    AuthenticationConfig.builder().clientAuthenticationPlugin(clientAuthPlugin)
                            .clientAuthenticationParameters(clientAuthParams).useTls(useTls)
                            .tlsAllowInsecureConnection(tlsAllowInsecureConnection)
                            .tlsHostnameVerificationEnable(tlsHostNameVerificationEnabled)
                            .tlsTrustCertsFilePath(tlsTrustCertFilePath).build(),
                    sinkConfig.getArchive(), admin);
        }

        @Override
        protected String validateSinkType(String sinkType) throws IOException {
            // Validate the connector sink type from the locally available connectors
            String pulsarHome = System.getenv("PULSAR_HOME");
            if (pulsarHome == null) {
                pulsarHome = Paths.get("").toAbsolutePath().toString();
            }
            String connectorsDir = Paths.get(pulsarHome, "connectors").toString();
            Connectors connectors = ConnectorUtils.searchForConnectors(connectorsDir);

            if (!connectors.getSinks().containsKey(sinkType)) {
                throw new ParameterException("Invalid sink type '" + sinkType + "' -- Available sinks are: "
                        + connectors.getSinks().keySet());
            }

            // Sink type is a valid built-in connector type. For local-run we'll fill it up with its own archive path
            return connectors.getSinks().get(sinkType).toString();
        }
    }

    @Parameters(commandDescription = "Submit a Pulsar IO sink connector to run in a Pulsar cluster")
    protected class CreateSink extends SinkDetailsCommand {
        @Override
        void runCmd() throws Exception {
            if (Utils.isFunctionPackageUrlSupported(archive)) {
                admin.sink().createSinkWithUrl(sinkConfig, sinkConfig.getArchive());
            } else {
                admin.sink().createSink(sinkConfig, sinkConfig.getArchive());
            }
            print("Created successfully");
        }
    }

    @Parameters(commandDescription = "Update a Pulsar IO sink connector")
    protected class UpdateSink extends SinkDetailsCommand {
        @Override
        void runCmd() throws Exception {
            if (Utils.isFunctionPackageUrlSupported(archive)) {
                admin.sink().updateSinkWithUrl(sinkConfig, sinkConfig.getArchive());
            } else {
                admin.sink().updateSink(sinkConfig, sinkConfig.getArchive());
            }
            print("Updated successfully");
        }
    }

    abstract class SinkDetailsCommand extends BaseCommand {
        @Parameter(names = "--tenant", description = "The sink's tenant")
        protected String tenant;
        @Parameter(names = "--namespace", description = "The sink's namespace")
        protected String namespace;
        @Parameter(names = "--name", description = "The sink's name")
        protected String name;

        @Parameter(names = { "-t", "--sink-type" }, description = "The sinks's connector provider")
        protected String sinkType;

        @Parameter(names = { "-i",
                "--inputs" }, description = "The sink's input topic or topics (multiple topics can be specified as a comma-separated list)")
        protected String inputs;

        @Parameter(names = "--topicsPattern", description = "TopicsPattern to consume from list of topics under a namespace that match the pattern. [--input] and [--topicsPattern] are mutually exclusive. Add SerDe class name for a pattern in --customSerdeInputs  (supported for java fun only)", hidden = true)
        protected String DEPRECATED_topicsPattern;
        @Parameter(names = "--topics-pattern", description = "TopicsPattern to consume from list of topics under a namespace that match the pattern. [--input] and [--topicsPattern] are mutually exclusive. Add SerDe class name for a pattern in --customSerdeInputs  (supported for java fun only)")
        protected String topicsPattern;

        @Parameter(names = "--subsName", description = "Pulsar source subscription name if user wants a specific subscription-name for input-topic consumer", hidden = true)
        protected String DEPRECATED_subsName;
        @Parameter(names = "--subs-name", description = "Pulsar source subscription name if user wants a specific subscription-name for input-topic consumer")
        protected String subsName;

        @Parameter(names = "--customSerdeInputs", description = "The map of input topics to SerDe class names (as a JSON string)", hidden = true)
        protected String DEPRECATED_customSerdeInputString;
        @Parameter(names = "--custom-serde-inputs", description = "The map of input topics to SerDe class names (as a JSON string)")
        protected String customSerdeInputString;

        @Parameter(names = "--custom-schema-inputs", description = "The map of input topics to Schema types or class names (as a JSON string)")
        protected String customSchemaInputString;


        @Parameter(names = "--processingGuarantees", description = "The processing guarantees (aka delivery semantics) applied to the sink", hidden = true)
        protected FunctionConfig.ProcessingGuarantees DEPRECATED_processingGuarantees;
        @Parameter(names = "--processing-guarantees", description = "The processing guarantees (aka delivery semantics) applied to the sink")
        protected FunctionConfig.ProcessingGuarantees processingGuarantees;
        @Parameter(names = "--retainOrdering", description = "Sink consumes and sinks messages in order", hidden = true)
        protected Boolean DEPRECATED_retainOrdering;
        @Parameter(names = "--retain-ordering", description = "Sink consumes and sinks messages in order")
        protected boolean retainOrdering;
        @Parameter(names = "--parallelism", description = "The sink's parallelism factor (i.e. the number of sink instances to run)")
        protected Integer parallelism;
        @Parameter(names = {"-a", "--archive"}, description = "Path to the archive file for the sink. It also supports url-path [http/https/file (file protocol assumes that file already exists on worker host)] from which worker can download the package.", listConverter = StringConverter.class)
        protected String archive;
        @Parameter(names = "--className", description = "The sink's class name if archive is file-url-path (file://)", hidden = true)
        protected String DEPRECATED_className;
        @Parameter(names = "--classname", description = "The sink's class name if archive is file-url-path (file://)")
        protected String className;

        @Parameter(names = "--sinkConfigFile", description = "The path to a YAML config file specifying the "
                + "sink's configuration", hidden = true)
        protected String DEPRECATED_sinkConfigFile;
        @Parameter(names = "--sink-config-file", description = "The path to a YAML config file specifying the "
                + "sink's configuration")
        protected String sinkConfigFile;
        @Parameter(names = "--cpu", description = "The CPU (in cores) that needs to be allocated per sink instance (applicable only to Docker runtime)")
        protected Double cpu;
        @Parameter(names = "--ram", description = "The RAM (in bytes) that need to be allocated per sink instance (applicable only to the process and Docker runtimes)")
        protected Long ram;
        @Parameter(names = "--disk", description = "The disk (in bytes) that need to be allocated per sink instance (applicable only to Docker runtime)")
        protected Long disk;
        @Parameter(names = "--sinkConfig", description = "User defined configs key/values", hidden = true)
        protected String DEPRECATED_sinkConfigString;
        @Parameter(names = "--sink-config", description = "User defined configs key/values")
        protected String sinkConfigString;
        @Parameter(names = "--auto-ack", description = "Whether or not the framework will automatically acknowleges messages", arity = 1)
        protected boolean autoAck = true;
        @Parameter(names = "--timeout-ms", description = "The message timeout in milliseconds")
        protected Long timeoutMs;

        protected SinkConfig sinkConfig;

        protected NarClassLoader classLoader;

        private void mergeArgs() {
            if (!StringUtils.isBlank(DEPRECATED_subsName)) subsName = DEPRECATED_subsName;
            if (!StringUtils.isBlank(DEPRECATED_topicsPattern)) topicsPattern = DEPRECATED_topicsPattern;
            if (!StringUtils.isBlank(DEPRECATED_customSerdeInputString)) customSerdeInputString = DEPRECATED_customSerdeInputString;
            if (DEPRECATED_processingGuarantees != null) processingGuarantees = DEPRECATED_processingGuarantees;
            if (DEPRECATED_retainOrdering != null) retainOrdering = DEPRECATED_retainOrdering;
            if (!StringUtils.isBlank(DEPRECATED_className)) className = DEPRECATED_className;
            if (!StringUtils.isBlank(DEPRECATED_sinkConfigFile)) sinkConfigFile = DEPRECATED_sinkConfigFile;
            if (!StringUtils.isBlank(DEPRECATED_sinkConfigString)) sinkConfigString = DEPRECATED_sinkConfigString;
        }

        @Override
        void processArguments() throws Exception {
            super.processArguments();
            // merge deprecated args with new args
            mergeArgs();

            if (null != sinkConfigFile) {
                this.sinkConfig = CmdUtils.loadConfig(sinkConfigFile, SinkConfig.class);
                log.info("The sinkConfig read from file is {}", sinkConfig);
            } else {
                this.sinkConfig = new SinkConfig();
            }

            if (null != tenant) {
                sinkConfig.setTenant(tenant);
            }

            if (null != namespace) {
                sinkConfig.setNamespace(namespace);
            }

            if (null != className) {
                sinkConfig.setClassName(className);
            }

            if (null != name) {
                sinkConfig.setName(name);
            }
            if (null != processingGuarantees) {
                sinkConfig.setProcessingGuarantees(processingGuarantees);
            }

            sinkConfig.setRetainOrdering(retainOrdering);

            if (null != inputs) {
                sinkConfig.setInputs(Arrays.asList(inputs.split(",")));
            }
            if (null != customSerdeInputString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> customSerdeInputMap = new Gson().fromJson(customSerdeInputString, type);
                sinkConfig.setTopicToSerdeClassName(customSerdeInputMap);
            }

            if (null != customSchemaInputString) {
                Type type = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> customSchemaInputMap = new Gson().fromJson(customSchemaInputString, type);
                sinkConfig.setTopicToSchemaType(customSchemaInputMap);
            }

            if (isNotBlank(subsName)) {
                sinkConfig.setSourceSubscriptionName(subsName);
            }

            if (null != topicsPattern) {
                sinkConfig.setTopicsPattern(topicsPattern);
            }

            if (parallelism != null) {
                sinkConfig.setParallelism(parallelism);
            }

            if (archive != null && sinkType != null) {
                throw new ParameterException("Cannot specify both archive and sink-type");
            }

            if (null != archive) {
                sinkConfig.setArchive(archive);
            }

            if (sinkType != null) {
                sinkConfig.setArchive(validateSinkType(sinkType));
            }

            org.apache.pulsar.functions.utils.Resources resources = sinkConfig.getResources();
            if (resources == null) {
                resources = new org.apache.pulsar.functions.utils.Resources();
            }
            if (cpu != null) {
                resources.setCpu(cpu);
            }

            if (ram != null) {
                resources.setRam(ram);
            }

            if (disk != null) {
                resources.setDisk(disk);
            }
            sinkConfig.setResources(resources);

            if (null != sinkConfigString) {
                sinkConfig.setConfigs(parseConfigs(sinkConfigString));
            }

            sinkConfig.setAutoAck(autoAck);
            if (timeoutMs != null) {
                sinkConfig.setTimeoutMs(timeoutMs);
            }
            
            if (null != sinkConfigString) {
                sinkConfig.setConfigs(parseConfigs(sinkConfigString));
            }
            
            inferMissingArguments(sinkConfig);

            // check if configs are valid
            validateSinkConfigs(sinkConfig);
        }

        protected Map<String, Object> parseConfigs(String str) {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            return new Gson().fromJson(str, type);
        }

        protected void inferMissingArguments(SinkConfig sinkConfig) {
            if (sinkConfig.getTenant() == null) {
                sinkConfig.setTenant(PUBLIC_TENANT);
            }
            if (sinkConfig.getNamespace() == null) {
                sinkConfig.setNamespace(DEFAULT_NAMESPACE);
            }
        }

        protected void validateSinkConfigs(SinkConfig sinkConfig) {

            if (isBlank(sinkConfig.getArchive())) {
                throw new ParameterException("Sink archive not specfied");
            }

            if (!Utils.isFunctionPackageUrlSupported(sinkConfig.getArchive()) &&
                    !sinkConfig.getArchive().startsWith(BUILTIN)) {
                if (!new File(sinkConfig.getArchive()).exists()) {
                    throw new IllegalArgumentException(String.format("Sink Archive file %s does not exist", sinkConfig.getArchive()));
                }
            }

            try {
                // Need to load jar and set context class loader before calling
                String sourcePkgUrl = Utils.isFunctionPackageUrlSupported(sinkConfig.getArchive()) ? sinkConfig.getArchive() : null;
                Path archivePath = (Utils.isFunctionPackageUrlSupported(sinkConfig.getArchive()) || sinkConfig.getArchive().startsWith(BUILTIN)) ? null : new File(sinkConfig.getArchive()).toPath();
                classLoader = SinkConfigUtils.validate(sinkConfig, archivePath, sourcePkgUrl, null);
            } catch (Exception e) {
                throw new ParameterException(e.getMessage());
            }
        }


        protected org.apache.pulsar.functions.proto.Function.FunctionDetails createSinkConfigProto2(SinkConfig sinkConfig)
                throws IOException {
            org.apache.pulsar.functions.proto.Function.FunctionDetails.Builder functionDetailsBuilder
                    = org.apache.pulsar.functions.proto.Function.FunctionDetails.newBuilder();
            Utils.mergeJson(FunctionsImpl.printJson(SinkConfigUtils.convert(sinkConfig, classLoader)), functionDetailsBuilder);
            return functionDetailsBuilder.build();
        }

        protected String validateSinkType(String sinkType) throws IOException {
            Set<String> availableSinks;
            try {
                availableSinks = admin.sink().getBuiltInSinks().stream().map(ConnectorDefinition::getName).collect(Collectors.toSet());
            } catch (PulsarAdminException e) {
                throw new IOException(e);
            }

            if (!availableSinks.contains(sinkType)) {
                throw new ParameterException(
                        "Invalid sink type '" + sinkType + "' -- Available sinks are: " + availableSinks);
            }

            // Source type is a valid built-in connector type
            return "builtin://" + sinkType;
        }
    }

    /**
     * Sink level command
     */
    @Getter
    abstract class SinkCommand extends BaseCommand {
        @Parameter(names = "--tenant", description = "The sink's tenant")
        protected String tenant;

        @Parameter(names = "--namespace", description = "The sink's namespace")
        protected String namespace;

        @Parameter(names = "--name", description = "The sink's name")
        protected String sinkName;

        @Override
        void processArguments() throws Exception {
            super.processArguments();
            if (tenant == null) {
                tenant = PUBLIC_TENANT;
            }
            if (namespace == null) {
                namespace = DEFAULT_NAMESPACE;
            }
            if (null == sinkName) {
                throw new RuntimeException(
                        "You must specify a name for the sink");
            }
        }
    }

    @Parameters(commandDescription = "Stops a Pulsar IO sink connector")
    protected class DeleteSink extends SinkCommand {

        @Override
        void runCmd() throws Exception {
            admin.sink().deleteSink(tenant, namespace, sinkName);
            print("Deleted successfully");
        }
    }

    @Parameters(commandDescription = "Gets the information about a Pulsar IO sink connector")
    protected class GetSink extends SinkCommand {

        @Override
        void runCmd() throws Exception {
            SinkConfig sinkConfig = admin.sink().getSink(tenant, namespace, sinkName);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(sinkConfig));
        }
    }

    /**
     * List Sources command
     */
    @Parameters(commandDescription = "List all running Pulsar IO sink connectors")
    protected class ListSinks extends BaseCommand {
        @Parameter(names = "--tenant", description = "The sink's tenant")
        protected String tenant;

        @Parameter(names = "--namespace", description = "The sink's namespace")
        protected String namespace;

        @Override
        public void processArguments() {
            if (tenant == null) {
                tenant = PUBLIC_TENANT;
            }
            if (namespace == null) {
                namespace = DEFAULT_NAMESPACE;
            }
        }

        @Override
        void runCmd() throws Exception {
            List<String> sinks = admin.sink().listSinks(tenant, namespace);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(sinks));
        }
    }

    @Parameters(commandDescription = "Check the current status of a Pulsar Sink")
    class GetSinkStatus extends SinkCommand {

        @Parameter(names = "--instance-id", description = "The sink instanceId (Get-status of all instances if instance-id is not provided")
        protected String instanceId;

        @Override
        void runCmd() throws Exception {
            String json = Utils.printJson(
                    isBlank(instanceId) ? admin.sink().getSinkStatus(tenant, namespace, sinkName)
                            : admin.sink().getSinkStatus(tenant, namespace, sinkName,
                            Integer.parseInt(instanceId)));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            System.out.println(gson.toJson(new JsonParser().parse(json)));
        }
    }

    @Parameters(commandDescription = "Restart sink instance")
    class RestartSink extends SinkCommand {

        @Parameter(names = "--instance-id", description = "The sink instanceId (restart all instances if instance-id is not provided")
        protected String instanceId;

        @Override
        void runCmd() throws Exception {
            if (isNotBlank(instanceId)) {
                try {
                    admin.sink().restartSink(tenant, namespace, sinkName, Integer.parseInt(instanceId));
                } catch (NumberFormatException e) {
                    System.err.println("instance-id must be a number");
                }
            } else {
                admin.sink().restartSink(tenant, namespace, sinkName);
            }
            System.out.println("Restarted successfully");
        }
    }

    @Parameters(commandDescription = "Temporary stops sink instance. (If worker restarts then it reassigns and starts sink again")
    class StopSink extends SinkCommand {

        @Parameter(names = "--instance-id", description = "The sink instanceId (stop all instances if instance-id is not provided")
        protected String instanceId;

        @Override
        void runCmd() throws Exception {
            if (isNotBlank(instanceId)) {
                try {
                    admin.sink().stopSink(tenant, namespace, sinkName, Integer.parseInt(instanceId));
                } catch (NumberFormatException e) {
                    System.err.println("instance-id must be a number");
                }
            } else {
                admin.sink().stopSink(tenant, namespace, sinkName);
            }
            System.out.println("Restarted successfully");
        }
    }

    @Parameters(commandDescription = "Get the list of Pulsar IO connector sinks supported by Pulsar cluster")
    public class ListBuiltInSinks extends BaseCommand {
        @Override
        void runCmd() throws Exception {
            admin.sink().getBuiltInSinks().stream().filter(x -> isNotBlank(x.getSinkClass()))
                    .forEach(connector -> {
                        System.out.println(connector.getName());
                        System.out.println(WordUtils.wrap(connector.getDescription(), 80));
                        System.out.println("----------------------------------------");
                    });
        }
    }
}
