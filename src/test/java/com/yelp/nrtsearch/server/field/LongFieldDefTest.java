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
package com.yelp.nrtsearch.server.field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.yelp.nrtsearch.server.ServerTestCase;
import com.yelp.nrtsearch.server.grpc.AddDocumentRequest;
import com.yelp.nrtsearch.server.grpc.FieldDefRequest;
import com.yelp.nrtsearch.server.grpc.Query;
import com.yelp.nrtsearch.server.grpc.RangeQuery;
import com.yelp.nrtsearch.server.grpc.SearchRequest;
import com.yelp.nrtsearch.server.grpc.SearchResponse;
import io.grpc.StatusRuntimeException;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.ClassRule;
import org.junit.Test;

public class LongFieldDefTest extends ServerTestCase {

  @ClassRule public static final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static final String fieldName = "long_field";
  private static final List<String> values =
      Arrays.asList(
          String.valueOf(Long.MIN_VALUE), "1", "10", "20", "30", String.valueOf(Long.MAX_VALUE));

  private Map<String, AddDocumentRequest.MultiValuedField> getFieldsMapForOneDocument(
      String value) {
    Map<String, AddDocumentRequest.MultiValuedField> fieldsMap = new HashMap<>();
    fieldsMap.put(
        fieldName, AddDocumentRequest.MultiValuedField.newBuilder().addValue(value).build());
    fieldsMap.put(
        "single_stored", AddDocumentRequest.MultiValuedField.newBuilder().addValue(value).build());
    fieldsMap.put(
        "multi_stored",
        AddDocumentRequest.MultiValuedField.newBuilder().addValue(value).addValue("15").build());
    return fieldsMap;
  }

  private List<AddDocumentRequest> buildDocuments(String indexName) {
    List<AddDocumentRequest> documentRequests = new ArrayList<>();
    for (String value : values) {
      documentRequests.add(
          AddDocumentRequest.newBuilder()
              .setIndexName(indexName)
              .putAllFields(getFieldsMapForOneDocument(value))
              .build());
    }
    return documentRequests;
  }

  @Override
  public FieldDefRequest getIndexDef(String name) throws IOException {
    return getFieldsFromResourceFile("/field/registerFieldsLong.json");
  }

  @Override
  public void initIndex(String name) throws Exception {
    List<AddDocumentRequest> documents = buildDocuments(name);
    addDocuments(documents.stream());
  }

  private SearchResponse getSearchResponse(Query query) {
    return getGrpcServer()
        .getBlockingStub()
        .search(
            SearchRequest.newBuilder()
                .setIndexName(DEFAULT_TEST_INDEX)
                .setQuery(query)
                .setTopHits(10)
                .addRetrieveFields(fieldName)
                .build());
  }

  @Test
  public void testStoredFields() {
    SearchResponse response =
        getGrpcServer()
            .getBlockingStub()
            .search(
                SearchRequest.newBuilder()
                    .setIndexName(DEFAULT_TEST_INDEX)
                    .setTopHits(10)
                    .addRetrieveFields("single_stored")
                    .addRetrieveFields("multi_stored")
                    .addRetrieveFields("single_none_stored")
                    .addRetrieveFields("multi_none_stored")
                    .setQuery(Query.newBuilder().build())
                    .build());
    assertEquals(6, response.getHitsCount());
    SearchResponse.Hit hit = response.getHits(0);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(
        Long.MIN_VALUE, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(
        Long.MIN_VALUE, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());

    hit = response.getHits(1);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(1, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());
    assertEquals(0, hit.getFieldsOrThrow("single_none_stored").getFieldValueCount());
    assertEquals(0, hit.getFieldsOrThrow("multi_none_stored").getFieldValueCount());

    hit = response.getHits(2);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(10, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(10, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());
    assertEquals(0, hit.getFieldsOrThrow("single_none_stored").getFieldValueCount());
    assertEquals(0, hit.getFieldsOrThrow("multi_none_stored").getFieldValueCount());

    hit = response.getHits(3);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(20, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(20, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());
    assertEquals(0, hit.getFieldsOrThrow("single_none_stored").getFieldValueCount());
    assertEquals(0, hit.getFieldsOrThrow("multi_none_stored").getFieldValueCount());

    hit = response.getHits(4);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(30, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(30, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());
    assertEquals(0, hit.getFieldsOrThrow("single_none_stored").getFieldValueCount());
    assertEquals(0, hit.getFieldsOrThrow("multi_none_stored").getFieldValueCount());

    hit = response.getHits(5);
    assertEquals(1, hit.getFieldsOrThrow("single_stored").getFieldValueCount());
    assertEquals(
        Long.MAX_VALUE, hit.getFieldsOrThrow("single_stored").getFieldValue(0).getLongValue());
    assertEquals(2, hit.getFieldsOrThrow("multi_stored").getFieldValueCount());
    assertEquals(
        Long.MAX_VALUE, hit.getFieldsOrThrow("multi_stored").getFieldValue(0).getLongValue());
    assertEquals(15, hit.getFieldsOrThrow("multi_stored").getFieldValue(1).getLongValue());
    assertEquals(0, hit.getFieldsOrThrow("single_none_stored").getFieldValueCount());
    assertEquals(0, hit.getFieldsOrThrow("multi_none_stored").getFieldValueCount());
  }

  @Test
  public void testRangeQuery() {
    rangeQuery("long_field");
  }

  @Test
  public void testRangeQuery_docValues() {
    rangeQuery("long_field.dv");
  }

  @Test
  public void testRangeQuery_searchable() {
    rangeQuery("long_field.search");
  }

  @Test
  public void testRangeQuery_multiDocValues() {
    rangeQuery("long_field.mv_dv");
  }

  @Test
  public void testRangeQuery_multiSearchable() {
    rangeQuery("long_field.mv_search");
  }

  @Test
  public void testRangeQuery_unsupported() {
    try {
      rangeQuery("long_field.none");
      fail();
    } catch (StatusRuntimeException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Range query requires field to be searchable or have doc values: long_field.none"));
    }
  }

  private void rangeQuery(String fieldName) {
    // Both bounds defined

    // Both inclusive
    RangeQuery rangeQuery =
        RangeQuery.newBuilder().setField(fieldName).setLower("1").setUpper("30").build();
    assertRangeQuery(rangeQuery, 1L, 10L, 20L, 30L);

    // Lower exclusive, upper inclusive
    rangeQuery =
        RangeQuery.newBuilder()
            .setField(fieldName)
            .setLower("1")
            .setUpper("30")
            .setLowerExclusive(true)
            .build();
    assertRangeQuery(rangeQuery, 10L, 20L, 30L);

    // Lower inclusive, upper exclusive
    rangeQuery =
        RangeQuery.newBuilder()
            .setField(fieldName)
            .setLower("1")
            .setUpper("30")
            .setUpperExclusive(true)
            .build();
    assertRangeQuery(rangeQuery, 1L, 10L, 20L);

    // Both exclusive
    rangeQuery =
        RangeQuery.newBuilder()
            .setField(fieldName)
            .setLower("1")
            .setUpper("30")
            .setLowerExclusive(true)
            .setUpperExclusive(true)
            .build();
    assertRangeQuery(rangeQuery, 10L, 20L);

    // Only upper bound defined

    // Both inclusive
    rangeQuery = RangeQuery.newBuilder().setField(fieldName).setUpper("20").build();
    assertRangeQuery(rangeQuery, Long.MIN_VALUE, 1L, 10L, 20L);

    // Lower exclusive, upper inclusive
    rangeQuery =
        RangeQuery.newBuilder().setField(fieldName).setUpper("20").setLowerExclusive(true).build();
    assertRangeQuery(rangeQuery, 1L, 10L, 20L);

    // Lower inclusive, upper exclusive
    rangeQuery =
        RangeQuery.newBuilder().setField(fieldName).setUpper("20").setUpperExclusive(true).build();
    assertRangeQuery(rangeQuery, Long.MIN_VALUE, 1L, 10L);

    // Both exclusive
    rangeQuery =
        RangeQuery.newBuilder()
            .setField(fieldName)
            .setUpper("20")
            .setLowerExclusive(true)
            .setUpperExclusive(true)
            .build();
    assertRangeQuery(rangeQuery, 1L, 10L);

    // Only lower bound defined

    // Both inclusive
    rangeQuery = RangeQuery.newBuilder().setField(fieldName).setLower("10").build();
    assertRangeQuery(rangeQuery, 10L, 20L, 30L, Long.MAX_VALUE);

    // Lower exclusive, upper inclusive
    rangeQuery =
        RangeQuery.newBuilder().setField(fieldName).setLower("10").setLowerExclusive(true).build();
    assertRangeQuery(rangeQuery, 20L, 30L, Long.MAX_VALUE);

    // Lower inclusive, upper exclusive
    rangeQuery =
        RangeQuery.newBuilder().setField(fieldName).setLower("10").setUpperExclusive(true).build();
    assertRangeQuery(rangeQuery, 10L, 20L, 30L);

    // Both exclusive
    rangeQuery =
        RangeQuery.newBuilder()
            .setField(fieldName)
            .setLower("10")
            .setLowerExclusive(true)
            .setUpperExclusive(true)
            .build();
    assertRangeQuery(rangeQuery, 20L, 30L);
  }

  private void assertRangeQuery(RangeQuery rangeQuery, Long... expectedValues) {
    Query query = Query.newBuilder().setRangeQuery(rangeQuery).build();
    SearchResponse searchResponse = getSearchResponse(query);
    assertEquals(expectedValues.length, searchResponse.getHitsCount());
    List<Long> actualValues =
        searchResponse.getHitsList().stream()
            .map(hit -> hit.getFieldsMap().get(fieldName).getFieldValueList().get(0).getLongValue())
            .sorted()
            .collect(Collectors.toList());
    assertEquals(Arrays.asList(expectedValues), actualValues);
  }
}
