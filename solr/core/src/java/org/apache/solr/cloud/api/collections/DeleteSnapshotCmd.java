/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.api.collections;

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.params.CollectionAdminParams.FOLLOW_ALIASES;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;
import static org.apache.solr.common.params.CommonParams.NAME;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.solr.cloud.api.collections.CollectionHandlingUtils.ShardRequestTracker;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Replica.State;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.CoreAdminParams.CoreAdminAction;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.snapshots.CollectionSnapshotMetaData;
import org.apache.solr.core.snapshots.CollectionSnapshotMetaData.CoreSnapshotMetaData;
import org.apache.solr.core.snapshots.CollectionSnapshotMetaData.SnapshotStatus;
import org.apache.solr.core.snapshots.SolrSnapshotManager;
import org.apache.solr.handler.component.ShardHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the functionality of deleting a collection level snapshot.
 */
public class DeleteSnapshotCmd implements CollApiCmds.CollectionApiCommand {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CollectionCommandContext ccc;

  public DeleteSnapshotCmd(CollectionCommandContext ccc) {
    this.ccc = ccc;
  }

  @Override
  public void call(ClusterState state, ZkNodeProps message, NamedList<Object> results) throws Exception {
    String extCollectionName =  message.getStr(COLLECTION_PROP);
    boolean followAliases = message.getBool(FOLLOW_ALIASES, false);
    String collectionName;
    if (followAliases) {
      collectionName = ccc.getZkStateReader().getAliases().resolveSimpleAlias(extCollectionName);
    } else {
      collectionName = extCollectionName;
    }
    String commitName =  message.getStr(CoreAdminParams.COMMIT_NAME);
    String asyncId = message.getStr(ASYNC);
    NamedList<Object> shardRequestResults = new NamedList<>();
    ShardHandler shardHandler = ccc.newShardHandler();
    SolrZkClient zkClient = ccc.getZkStateReader().getZkClient();

    Optional<CollectionSnapshotMetaData> meta = SolrSnapshotManager.getCollectionLevelSnapshot(zkClient, collectionName, commitName);
    if (!meta.isPresent()) { // Snapshot not found. Nothing to do.
      return;
    }

    log.info("Deleting a snapshot for collection={} with commitName={}", collectionName, commitName);

    Set<String> existingCores = new HashSet<>();
    for (Slice s : ccc.getZkStateReader().getClusterState().getCollection(collectionName).getSlices()) {
      for (Replica r : s.getReplicas()) {
        existingCores.add(r.getCoreName());
      }
    }

    Set<String> coresWithSnapshot = new HashSet<>();
    for (CoreSnapshotMetaData m : meta.get().getReplicaSnapshots()) {
      if (existingCores.contains(m.getCoreName())) {
        coresWithSnapshot.add(m.getCoreName());
      }
    }

    final ShardRequestTracker shardRequestTracker = CollectionHandlingUtils.asyncRequestTracker(asyncId, ccc);
    log.info("Existing cores with snapshot for collection={} are {}", collectionName, existingCores);
    for (Slice slice : ccc.getZkStateReader().getClusterState().getCollection(collectionName).getSlices()) {
      for (Replica replica : slice.getReplicas()) {
        if (replica.getState() == State.DOWN) {
          continue; // Since replica is down - no point sending a request.
        }

        // Note - when a snapshot is found in_progress state - it is the result of overseer
        // failure while handling the snapshot creation. Since we don't know the exact set of
        // replicas to contact at this point, we try on all replicas.
        if (meta.get().getStatus() == SnapshotStatus.InProgress || coresWithSnapshot.contains(replica.getCoreName())) {
          String coreName = replica.getStr(CORE_NAME_PROP);

          ModifiableSolrParams params = new ModifiableSolrParams();
          params.set(CoreAdminParams.ACTION, CoreAdminAction.DELETESNAPSHOT.toString());
          params.set(NAME, slice.getName());
          params.set(CORE_NAME_PROP, coreName);
          params.set(CoreAdminParams.COMMIT_NAME, commitName);

          log.info("Sending deletesnapshot request to core={} with commitName={}", coreName, commitName);
          shardRequestTracker.sendShardRequest(replica.getNodeName(), params, shardHandler);
        }
      }
    }

    shardRequestTracker.processResponses(shardRequestResults, shardHandler, false, null);
    @SuppressWarnings("unchecked")
    NamedList<Object> success = (NamedList<Object>) shardRequestResults.get("success");
    List<CoreSnapshotMetaData> replicas = new ArrayList<>();
    if (success != null) {
      for ( int i = 0 ; i < success.size() ; i++) {
        NamedList<?> resp = (NamedList<?>)success.getVal(i);
        // Unfortunately async processing logic doesn't provide the "core" name automatically.
        String coreName = (String)resp.get("core");
        coresWithSnapshot.remove(coreName);
      }
    }

    if (!coresWithSnapshot.isEmpty()) { // One or more failures.
      log.warn("Failed to delete a snapshot for collection {} with commitName = {}. Snapshot could not be deleted for following cores {}",
          collectionName, commitName, coresWithSnapshot);

      List<CoreSnapshotMetaData> replicasWithSnapshot = new ArrayList<>();
      for (CoreSnapshotMetaData m : meta.get().getReplicaSnapshots()) {
        if (coresWithSnapshot.contains(m.getCoreName())) {
          replicasWithSnapshot.add(m);
        }
      }

      // Update the ZK meta-data to include only cores with the snapshot. This will enable users to figure out
      // which cores still contain the named snapshot.
      CollectionSnapshotMetaData newResult = new CollectionSnapshotMetaData(meta.get().getName(), SnapshotStatus.Failed,
          meta.get().getCreationDate(), replicasWithSnapshot);
      SolrSnapshotManager.updateCollectionLevelSnapshot(zkClient, collectionName, newResult);
      if (log.isInfoEnabled()) {
        log.info("Saved snapshot information for collection={} with commitName={} in Zookeeper as follows: {}", collectionName, commitName,
            Utils.toJSON(newResult));
      }
      throw new SolrException(ErrorCode.SERVER_ERROR, "Failed to delete snapshot on cores " + coresWithSnapshot);

    } else {
      // Delete the ZK path so that we eliminate the references of this snapshot from collection level meta-data.
      SolrSnapshotManager.deleteCollectionLevelSnapshot(zkClient, collectionName, commitName);
      log.info("Deleted Zookeeper snapshot metdata for collection={} with commitName={}", collectionName, commitName);
      log.info("Successfully deleted snapshot for collection={} with commitName={}", collectionName, commitName);
    }
  }
}
