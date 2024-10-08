package com.ozangunalp.kafka.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.common.metadata.FeatureLevelRecord;
import org.apache.kafka.common.metadata.UserScramCredentialRecord;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.metadata.bootstrap.BootstrapMetadata;
import org.apache.kafka.metadata.properties.MetaProperties;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.server.common.MetadataVersion;
import org.jboss.logging.Logger;

import kafka.server.KafkaConfig;
import kafka.tools.StorageTool;
import scala.collection.immutable.Seq;

public final class Storage {

    static final Logger LOGGER = Logger.getLogger(Storage.class.getName());
    public static final String LOG_DIR = "log.dir";

    private Storage() {
    }

    public static void ensureLogDirExists(Properties properties) {
        String logDir = properties.getProperty(LOG_DIR);
        if (logDir != null) {
            try {
                Files.createDirectories(Paths.get(logDir));
            } catch (Throwable throwable) {
                LOGGER.warnf(throwable, "Error using %s as `log.dir`, setting up a temporary directory.", logDir);
                Storage.createAndSetLogDir(properties);
            }
        } else {
            Storage.createAndSetLogDir(properties);
        }
    }

    public static void createAndSetLogDir(Properties properties) {
        try {
            properties.put(LOG_DIR,
                    Files.createTempDirectory(EmbeddedKafkaBroker.KAFKA_PREFIX + UUID.randomUUID()).toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void formatStorageFromConfig(KafkaConfig config, String clusterId, boolean ignoreFormatted, MetadataVersion metadataVersion, List<UserScramCredentialRecord> scramCredentials) {
        if (!scramCredentials.isEmpty() && !metadataVersion.isScramSupported()) {
            throw new IllegalArgumentException("SCRAM is only supported in metadataVersion IBP_3_5_IV2 or later.");
        }
        Seq<String> directories = StorageTool.configToLogDirectories(config);
        MetaProperties metaProperties = StorageTool.buildMetadataProperties(clusterId, config);
        var metadataRecords = new ArrayList<ApiMessageAndVersion>();
        metadataRecords.add(metadataVersionMessage(metadataVersion));
        for (var scramCredential : scramCredentials) {
            metadataRecords.add(scramMessage(scramCredential));
        }
        BootstrapMetadata bootstrapMetadata = BootstrapMetadata.fromRecords(metadataRecords, "kafka-native");
        StorageTool.formatCommand(LoggingOutputStream.loggerPrintStream(LOGGER), directories, metaProperties,
                bootstrapMetadata, metadataVersion, ignoreFormatted);
    }

    public static ApiMessageAndVersion withVersion(ApiMessage message) {
        return new ApiMessageAndVersion(message, (short) 0);
    }

    private static ApiMessageAndVersion metadataVersionMessage(MetadataVersion metadataVersion) {
        return withVersion(new FeatureLevelRecord().
                setName(MetadataVersion.FEATURE_NAME).
                setFeatureLevel(metadataVersion.featureLevel()));
    }

    private static ApiMessageAndVersion scramMessage(UserScramCredentialRecord scramRecord) {
        return withVersion(scramRecord);
    }

    public static class LoggingOutputStream extends java.io.OutputStream {

        public static PrintStream loggerPrintStream(Logger logger) {
            return new PrintStream(new LoggingOutputStream(logger));
        }

        private final ByteArrayOutputStream os = new ByteArrayOutputStream(1000);
        private final Logger logger;

        LoggingOutputStream(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n' || b == '\r') {
                os.flush();
                String log = os.toString();
                logger.info(log);
            } else {
                os.write(b);
            }
        }
    }
}
