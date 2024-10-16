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
package io.trino.plugin.singlestore;

import com.google.common.collect.ImmutableSet;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;

import static io.trino.testing.TestingProperties.requiredNonEmptySystemProperty;

public class TestingSingleStoreServer
        extends JdbcDatabaseContainer<TestingSingleStoreServer>
{
    private static final String MEM_SQL_LICENSE = requiredNonEmptySystemProperty("memsql.license");

    public static final String DEFAULT_TAG = "memsql/cluster-in-a-box:centos-7.1.13-11ddea2a3a-3.0.0-1.9.0";
    public static final String LATEST_TESTED_TAG = "ghcr.io/singlestore-labs/singlestoredb-dev:0.2.34";

    public static final Integer SINGLESTORE_PORT = 3306;

    public TestingSingleStoreServer()
    {
        this(DEFAULT_TAG);
    }

    public TestingSingleStoreServer(String dockerImageName)
    {
        super(DockerImageName.parse(dockerImageName));
        withStartupTimeout(Duration.ofMinutes(5));
        addEnv("ROOT_PASSWORD", "memsql_root_password");
        if (isSingleStoreDevImage(dockerImageName)) {
            addEnv("SINGLESTORE_VERSION", "8.7.16");
            withCopyFileToContainer(MountableFile.forClasspathResource("init.sql"), "/docker-entrypoint-initdb.d/init.sql");
        }
        else {
            withCommand("sh", "-xeuc",
                    "/startup && " +
                            // Lower the size of pre-allocated log files to 1MB (minimum allowed) to reduce disk footprint
                            "memsql-admin update-config --yes --all --set-global --key \"log_file_size_partitions\" --value \"1048576\" && " +
                            "memsql-admin update-config --yes --all --set-global --key \"log_file_size_ref_dbs\" --value \"1048576\" && " +
                            // re-execute startup to actually start the nodes (first run performs setup but doesn't start the nodes)
                            "exec /startup");
        }
        start();
    }

    @Override
    public Set<Integer> getLivenessCheckPortNumbers()
    {
        return ImmutableSet.of(getMappedPort(SINGLESTORE_PORT));
    }

    @Override
    protected void configure()
    {
        addExposedPort(SINGLESTORE_PORT);
        addEnv("LICENSE_KEY", MEM_SQL_LICENSE);
        setStartupAttempts(3);
    }

    @Override
    public String getDriverClassName()
    {
        return "com.singlestore.jdbc.Driver";
    }

    @Override
    public String getUsername()
    {
        return "root";
    }

    @Override
    public String getPassword()
    {
        return "memsql_root_password";
    }

    @Override
    public String getJdbcUrl()
    {
        return "jdbc:singlestore://" + getHost() + ":" + getMappedPort(SINGLESTORE_PORT);
    }

    @Override
    public String getTestQueryString()
    {
        return "SELECT 1";
    }

    public void execute(String sql)
    {
        execute(sql, getUsername(), getPassword());
    }

    public void execute(String sql, String user, String password)
    {
        try (Connection connection = DriverManager.getConnection(getJdbcUrl(), user, password);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isSingleStoreDevImage(String dockerImageName)
    {
        return dockerImageName.startsWith("ghcr.io/singlestore-labs/singlestoredb-dev");
    }
}
