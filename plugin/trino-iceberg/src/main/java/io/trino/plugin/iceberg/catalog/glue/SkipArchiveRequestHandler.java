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

import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.glue.model.BatchGetPartitionRequest;
import software.amazon.awssdk.services.glue.model.CreateDatabaseRequest;
import software.amazon.awssdk.services.glue.model.CreateTableRequest;
import software.amazon.awssdk.services.glue.model.DeleteDatabaseRequest;
import software.amazon.awssdk.services.glue.model.DeleteTableRequest;
import software.amazon.awssdk.services.glue.model.GetDatabaseRequest;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.glue.model.GetPartitionsRequest;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTablesRequest;
import software.amazon.awssdk.services.glue.model.UpdateTableRequest;

public class SkipArchiveRequestHandler
        implements ExecutionInterceptor
{
    @Override
    public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes)
    {
        if (context.request() instanceof UpdateTableRequest updateTableRequest) {
            return updateTableRequest
                    .toBuilder()
                    .skipArchive(true)
                    .build();
        }

        return switch (context.request()) {
            case CreateDatabaseRequest request -> request;
            case DeleteDatabaseRequest request -> request;
            case GetDatabasesRequest request -> request;
            case GetDatabaseRequest request -> request;
            case CreateTableRequest request -> request;
            case DeleteTableRequest request -> request;
            case GetTablesRequest request -> request;
            case GetTableRequest request -> request;
            case GetPartitionsRequest request -> request;
            case BatchGetPartitionRequest request -> request;
            default -> throw new IllegalArgumentException("Unsupported request: " + context.request());
        };
    }
}
