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
package org.apache.solr.handler.admin.api;

import org.apache.solr.api.EndPoint;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.handler.admin.CollectionsHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.DELETE;
import static org.apache.solr.common.params.CommonParams.ACTION;
import static org.apache.solr.common.params.CommonParams.NAME;
import static org.apache.solr.handler.ClusterAPI.wrapParams;
import static org.apache.solr.security.PermissionNameProvider.Name.COLL_EDIT_PERM;

/**
 * V2 API for deleting collections.
 *
 * This API (DELETE /v2/collections/collectionName) is equivalent to the v1 /admin/collections?action=DELETE command.
 */
public class DeleteCollectionAPI {

  private final CollectionsHandler collectionsHandler;

  public DeleteCollectionAPI(CollectionsHandler collectionsHandler) {
    this.collectionsHandler = collectionsHandler;
  }

  @EndPoint(path = {"/c/{collection}", "/collections/{collection}"},
          method = DELETE,
          permission = COLL_EDIT_PERM)
  public void deleteCollection(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    req = wrapParams(req, ACTION,
            CollectionParams.CollectionAction.DELETE.toString(),
            NAME, req.getPathTemplateValues().get(ZkStateReader.COLLECTION_PROP));
    collectionsHandler.handleRequestBody(req, rsp);
  }
}
