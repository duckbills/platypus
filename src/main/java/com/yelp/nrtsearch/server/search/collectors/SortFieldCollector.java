/*
 * Copyright 2020 Yelp Inc.
 *
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
package com.yelp.nrtsearch.server.search.collectors;

import com.yelp.nrtsearch.server.grpc.CollectorResult;
import com.yelp.nrtsearch.server.grpc.LastHitInfo;
import com.yelp.nrtsearch.server.grpc.SearchResponse;
import com.yelp.nrtsearch.server.search.SearchRequestProcessor;
import com.yelp.nrtsearch.server.search.sort.SortContext;
import com.yelp.nrtsearch.server.search.sort.SortParser;
import java.util.List;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldCollectorManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Collector for getting documents ranked by sorting fields. */
public class SortFieldCollector extends DocCollector {
  private static final Logger logger = LoggerFactory.getLogger(SortFieldCollector.class);

  private final CollectorManager<TopFieldCollector, TopFieldDocs> manager;
  private final SortContext sortContext;

  public SortFieldCollector(
      CollectorCreatorContext context,
      List<AdditionalCollectorManager<? extends Collector, ? extends CollectorResult>>
          additionalCollectors) {
    super(context, additionalCollectors);
    int topHits = getNumHitsToCollect();
    int totalHitsThreshold = SearchRequestProcessor.TOTAL_HITS_THRESHOLD;

    FieldDoc searchAfter = null;
    LastHitInfo lastHitInfo = context.getRequest().getSearchAfter();
    if (lastHitInfo != null && lastHitInfo.getLastFieldValuesCount() > 0) {
      searchAfter =
          SortParser.parseLastHitInfo(
              lastHitInfo, context.getRequest().getQuerySort(), context.getQueryFields());
    }

    // if there are additional collectors, we cannot skip any recalled docs
    if (!additionalCollectors.isEmpty()) {
      totalHitsThreshold = Integer.MAX_VALUE;
      if (context.getRequest().getTotalHitsThreshold() != 0) {
        logger.warn("Query totalHitsThreshold ignored when using additional collectors");
      }
    } else if (context.getRequest().getTotalHitsThreshold() != 0) {
      totalHitsThreshold = context.getRequest().getTotalHitsThreshold();
    }

    sortContext = new SortContext(context.getRequest().getQuerySort(), context.getQueryFields());
    manager =
        new TopFieldCollectorManager(
            sortContext.getSort(), topHits, searchAfter, totalHitsThreshold);
  }

  @Override
  public CollectorManager<? extends Collector, ? extends TopDocs> getManager() {
    return manager;
  }

  @Override
  public void fillHitRanking(SearchResponse.Hit.Builder hitResponse, ScoreDoc scoreDoc) {
    FieldDoc fd = (FieldDoc) scoreDoc;
    hitResponse.putAllSortedFields(SortParser.getAllSortedValues(fd, sortContext));
  }

  @Override
  public void fillLastHit(SearchResponse.SearchState.Builder stateBuilder, ScoreDoc lastHit) {
    FieldDoc fd = (FieldDoc) lastHit;
    LastHitInfo.Builder lastHitBuilder = LastHitInfo.newBuilder();
    lastHitBuilder.setLastDocId(lastHit.doc);
    for (Object fv : fd.fields) {
      String fvstr;
      if (fv == null) {
        fvstr = SortParser.NULL_SORT_VALUE;
      } else if (fv instanceof BytesRef) {
        fvstr = ((BytesRef) fv).utf8ToString();
      } else {
        fvstr = fv.toString();
      }
      stateBuilder.addLastFieldValues(fvstr);
      lastHitBuilder.addLastFieldValues(fvstr);
    }
    stateBuilder.setLastHitInfo(lastHitBuilder.build());
  }
}
