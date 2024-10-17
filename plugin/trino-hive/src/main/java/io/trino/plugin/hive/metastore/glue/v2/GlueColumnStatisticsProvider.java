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
package io.trino.plugin.hive.metastore.glue.v2;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.metastore.HiveColumnStatistics;
import io.trino.metastore.Partition;
import io.trino.metastore.Table;

import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public interface GlueColumnStatisticsProvider
{
    Map<String, HiveColumnStatistics> getTableColumnStatistics(String databaseName, String tableName, Set<String> columnNames);

    Map<String, Map<String, HiveColumnStatistics>> getPartitionColumnStatistics(
            String databaseName,
            String tableName,
            Set<String> partitionNames,
            Set<String> columns);

    void updateTableColumnStatistics(Table table, Map<String, HiveColumnStatistics> columnStatistics);

    default void updatePartitionStatistics(Partition partition, Map<String, HiveColumnStatistics> columnStatistics)
    {
        updatePartitionStatistics(ImmutableSet.of(new PartitionStatisticsUpdate(partition, columnStatistics)));
    }

    void updatePartitionStatistics(Set<PartitionStatisticsUpdate> partitionStatisticsUpdates);

    class PartitionStatisticsUpdate
    {
        private final Partition partition;
        private final Map<String, HiveColumnStatistics> columnStatistics;

        public PartitionStatisticsUpdate(Partition partition, Map<String, HiveColumnStatistics> columnStatistics)
        {
            this.partition = requireNonNull(partition, "partition is null");
            this.columnStatistics = ImmutableMap.copyOf(requireNonNull(columnStatistics, "columnStatistics is null"));
        }

        public Partition getPartition()
        {
            return partition;
        }

        public Map<String, HiveColumnStatistics> getColumnStatistics()
        {
            return columnStatistics;
        }
    }
}
