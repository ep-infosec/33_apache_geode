/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.cache.lucene.internal;

import static org.apache.geode.cache.Region.SEPARATOR;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;

import org.apache.logging.log4j.Logger;

import org.apache.geode.DataSerializer;
import org.apache.geode.cache.lucene.LuceneServiceProvider;
import org.apache.geode.distributed.internal.ClusterDistributionManager;
import org.apache.geode.distributed.internal.MessageWithReply;
import org.apache.geode.distributed.internal.PooledDistributionMessage;
import org.apache.geode.distributed.internal.ReplyException;
import org.apache.geode.distributed.internal.ReplyMessage;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.logging.internal.log4j.api.LogService;

public class DestroyLuceneIndexMessage extends PooledDistributionMessage
    implements MessageWithReply {

  private static final Logger logger = LogService.getLogger();

  private int processorId;

  private String regionPath;

  private String indexName;

  /* For serialization */
  public DestroyLuceneIndexMessage() {
    // nothing
  }

  protected DestroyLuceneIndexMessage(Collection recipients, int processorId, String regionPath,
      String indexName) {
    super();
    setRecipients(recipients);
    this.processorId = processorId;
    this.regionPath = regionPath;
    this.indexName = indexName;
  }

  @Override
  protected void process(ClusterDistributionManager dm) {
    ReplyException replyException = null;
    try {
      if (logger.isDebugEnabled()) {
        logger.debug("DestroyLuceneIndexMessage: Destroying regionPath=" + regionPath
            + "; indexName=" + indexName);
      }
      try {
        InternalCache cache = dm.getCache();
        LuceneServiceImpl impl = (LuceneServiceImpl) LuceneServiceProvider.get(cache);
        try {
          impl.destroyIndex(indexName, regionPath, false);
          if (logger.isDebugEnabled()) {
            logger.debug("DestroyLuceneIndexMessage: Destroyed regionPath=" + regionPath
                + "; indexName=" + indexName);
          }
        } catch (IllegalArgumentException e) {
          // If the IllegalArgumentException is index not found, then its ok; otherwise rethrow it.
          String fullRegionPath =
              regionPath.startsWith(SEPARATOR) ? regionPath : SEPARATOR + regionPath;
          String indexNotFoundMessage = String.format("Lucene index %s was not found in region %s",
              indexName, fullRegionPath);
          if (!e.getLocalizedMessage().equals(indexNotFoundMessage)) {
            throw e;
          }
        }
      } catch (Throwable e) {
        replyException = new ReplyException(e);
        if (logger.isDebugEnabled()) {
          logger.debug(
              "DestroyLuceneIndexMessage: Caught the following exception attempting to destroy indexName="
                  + indexName + "; regionPath=" + regionPath + ":",
              e);
        }
      }
    } finally {
      ReplyMessage replyMsg = new ReplyMessage();
      replyMsg.setRecipient(getSender());
      replyMsg.setProcessorId(processorId);
      if (replyException != null) {
        replyMsg.setException(replyException);
      }
      dm.putOutgoing(replyMsg);
    }
  }

  @Override
  public int getDSFID() {
    return DESTROY_LUCENE_INDEX_MESSAGE;
  }

  @Override
  public void toData(DataOutput out,
      SerializationContext context) throws IOException {
    super.toData(out, context);
    out.writeInt(processorId);
    DataSerializer.writeString(regionPath, out);
    DataSerializer.writeString(indexName, out);
  }

  @Override
  public void fromData(DataInput in,
      DeserializationContext context) throws IOException, ClassNotFoundException {
    super.fromData(in, context);
    processorId = in.readInt();
    regionPath = DataSerializer.readString(in);
    indexName = DataSerializer.readString(in);
  }
}
