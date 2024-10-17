/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg.catalog.glue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.filesystem.hdfs.HdfsFileSystemFactory;
import io.trino.hdfs.DynamicHdfsConfiguration;
import io.trino.hdfs.HdfsConfig;
import io.trino.hdfs.HdfsConfiguration;
import io.trino.hdfs.HdfsConfigurationInitializer;
import io.trino.hdfs.HdfsEnvironment;
import io.trino.hdfs.TrinoHdfsFileSystemStats;
import io.trino.hdfs.authentication.NoHdfsAuthentication;
import io.trino.plugin.iceberg.BaseIcebergConnectorSmokeTest;
import io.trino.plugin.iceberg.IcebergQueryRunner;
import io.trino.plugin.iceberg.SchemaInitializer;
import io.trino.testing.QueryRunner;
import org.apache.iceberg.FileFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.hive.metastore.glue.v2.AwsSdkUtil.getPaginatedResults;
import static io.trino.plugin.hive.metastore.glue.v2.converter.GlueToTrinoConverter.getTableParameters;
import static io.trino.plugin.iceberg.IcebergTestUtils.checkParquetFileSorting;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

/*
 * TestIcebergGlueCatalogConnectorSmokeTest currently uses AWS Default Credential Provider Chain,
 * See https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
 * on ways to set your AWS credentials which will be needed to run this test.
 */
@TestInstance(PER_CLASS)
public class TestIcebergGlueCatalogConnectorSmokeTest
        extends BaseIcebergConnectorSmokeTest
{
    private final String bucketName;
    private final String schemaName;
    private final GlueClient glueClient;
    private final TrinoFileSystemFactory fileSystemFactory;

    public TestIcebergGlueCatalogConnectorSmokeTest()
    {
        super(FileFormat.PARQUET);
        this.bucketName = requireNonNull(System.getenv("S3_BUCKET"), "Environment S3_BUCKET was not set");
        this.schemaName = "test_iceberg_smoke_" + randomNameSuffix();
        glueClient = GlueClient.create();

        HdfsConfigurationInitializer initializer = new HdfsConfigurationInitializer(new HdfsConfig(), ImmutableSet.of());
        HdfsConfiguration hdfsConfiguration = new DynamicHdfsConfiguration(initializer, ImmutableSet.of());
        this.fileSystemFactory = new HdfsFileSystemFactory(new HdfsEnvironment(hdfsConfiguration, new HdfsConfig(), new NoHdfsAuthentication()), new TrinoHdfsFileSystemStats());
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return IcebergQueryRunner.builder()
                .setIcebergProperties(
                        ImmutableMap.of(
                                "iceberg.file-format", format.name(),
                                "iceberg.catalog.type", "glue",
                                "hive.metastore.glue.default-warehouse-dir", schemaPath(),
                                "iceberg.register-table-procedure.enabled", "true",
                                "iceberg.writer-sort-buffer-size", "1MB"))
                .setSchemaInitializer(
                        SchemaInitializer.builder()
                                .withClonedTpchTables(REQUIRED_TPCH_TABLES)
                                .withSchemaName(schemaName)
                                .build())
                .build();
    }

    @AfterAll
    public void cleanup()
    {
        computeActual("SHOW TABLES").getMaterializedRows()
                .forEach(table -> getQueryRunner().execute("DROP TABLE " + table.getField(0)));
        getQueryRunner().execute("DROP SCHEMA IF EXISTS " + schemaName);

        // DROP TABLES should clean up any files, but clear the directory manually to be safe
        deleteDirectory(schemaPath());
    }

    @Test
    @Override
    public void testShowCreateTable()
    {
        assertThat((String) computeScalar("SHOW CREATE TABLE region"))
                .matches(format("" +
                                "\\QCREATE TABLE iceberg.%1$s.region (\n" +
                                "   regionkey bigint,\n" +
                                "   name varchar,\n" +
                                "   comment varchar\n" +
                                ")\n" +
                                "WITH (\n" +
                                "   format = 'PARQUET',\n" +
                                "   format_version = 2,\n" +
                                "   location = '%2$s/%1$s.db/region-\\E.*\\Q'\n" +
                                ")\\E",
                        schemaName,
                        schemaPath()));
    }

    @Test
    @Override
    public void testRenameSchema()
    {
        assertThatThrownBy(super::testRenameSchema)
                .hasStackTraceContaining("renameNamespace is not supported for Iceberg Glue catalogs");
    }

    @Override
    protected void dropTableFromMetastore(String tableName)
    {
        DeleteTableRequest deleteTableRequest = DeleteTableRequest.builder()
                .databaseName(schemaName)
                .name(tableName)
                .build();
        glueClient.deleteTable(deleteTableRequest);
        GetTableRequest getTableRequest = GetTableRequest.builder()
                .databaseName(schemaName)
                .name(tableName)
                .build();
        assertThatThrownBy(() -> glueClient.getTable(getTableRequest))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Override
    protected String getMetadataLocation(String tableName)
    {
        GetTableRequest getTableRequest = GetTableRequest.builder()
                .databaseName(schemaName)
                .name(tableName)
                .build();
        return getTableParameters(glueClient.getTable(getTableRequest).table())
                .get("metadata_location");
    }

    @Override
    protected void deleteDirectory(String location)
    {
        try (S3Client s3 = S3Client.create()) {
            ListObjectsV2Request.Builder listObjectsRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(location);

            List<ObjectIdentifier> keysToDelete = getPaginatedResults(
                    builder -> s3.listObjectsV2(builder.build()),
                    listObjectsRequest,
                    ListObjectsV2Request.Builder::continuationToken,
                    ListObjectsV2Response::nextContinuationToken)
                    .flatMap(response -> response.contents().stream())
                    .map(object -> ObjectIdentifier.builder()
                            .key(object.key())
                            .build())
                    .collect(toImmutableList());

            Delete delete = Delete.builder().objects(keysToDelete).build();

            if (!keysToDelete.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucketName).delete(delete).build());
            }
            assertThat(s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(location)
                    .maxKeys(1)
                    .build())
                    .contents()).isEmpty();
        }
    }

    @Override
    protected boolean isFileSorted(Location path, String sortColumnName)
    {
        TrinoFileSystem fileSystem = fileSystemFactory.create(SESSION);
        return checkParquetFileSorting(fileSystem.newInputFile(path), sortColumnName);
    }

    @Override
    protected String schemaPath()
    {
        return format("s3://%s/%s", bucketName, schemaName);
    }

    @Override
    protected boolean locationExists(String location)
    {
        String prefix = "s3://" + bucketName + "/";
        try (S3Client s3 = S3Client.create()) {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(location.substring(prefix.length()))
                    .maxKeys(1)
                    .build();
            return !s3.listObjectsV2(request).contents().isEmpty();
        }
    }
}
