// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.partition;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.request.Action.Method.POST;
import static com.google.domain.registry.util.PipelineUtils.createJobPath;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.inputs.InMemoryInput;
import com.google.common.collect.ImmutableList;
import com.google.domain.registry.config.RegistryEnvironment;
import com.google.domain.registry.mapreduce.MapreduceAction;
import com.google.domain.registry.mapreduce.MapreduceRunner;
import com.google.domain.registry.model.ofy.CommitLogBucket;
import com.google.domain.registry.request.Action;
import com.google.domain.registry.request.Response;

import com.googlecode.objectify.Key;

import javax.inject.Inject;

/** Deletes all commit logs in datastore. */
@Action(path = "/_dr/task/killAllCommitLogs", method = POST)
public class KillAllCommitLogsAction implements MapreduceAction {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject KillAllCommitLogsAction() {}

  @Override
  public void run() {
    checkArgument( // safety
        RegistryEnvironment.get() == RegistryEnvironment.CRASH
            || RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "DO NOT RUN ANYWHERE ELSE EXCEPT CRASH OR TESTS.");
    // Create a in-memory input, assigning each bucket to its own shard for maximum parallelization.
    Input<Key<CommitLogBucket>> input =
        new InMemoryInput<>(partition(CommitLogBucket.getAllBucketKeys().asList(), 1));
    response.sendJavaScriptRedirect(createJobPath(mrRunner
        .setJobName("Delete all commit logs")
        .setModuleName("tools")
        .runMapreduce(
            new KillAllCommitLogsMapper(),
            new KillAllEntitiesReducer(),
            ImmutableList.of(input))));
  }

  /**
   * Mapper to delete a {@link CommitLogBucket} and any commit logs in the bucket.
   *
   * <p>This will delete:
   * <ul>
   *   <li>{@link CommitLogBucket}
   *   <li>{@code CommitLogManifest}
   *   <li>{@code CommitLogMutation}
   * </ul>
   */
  static class KillAllCommitLogsMapper extends Mapper<Key<CommitLogBucket>, Key<?>, Key<?>> {

    private static final long serialVersionUID = 1504266335352952033L;

    @Override
    public void map(Key<CommitLogBucket> bucket) {
      for (Key<Object> key : ofy().load().ancestor(bucket).keys()) {
        emit(bucket, key);
        getContext().incrementCounter("entities emitted");
        getContext().incrementCounter(String.format("%s emitted", key.getKind()));
     }
    }
  }
}

