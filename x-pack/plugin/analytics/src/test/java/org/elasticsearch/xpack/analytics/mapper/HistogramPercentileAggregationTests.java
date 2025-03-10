/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.analytics.mapper;


import com.tdunning.math.stats.Centroid;
import org.HdrHistogram.DoubleHistogram;
import org.HdrHistogram.DoubleHistogramIterationValue;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;

import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.plugins.Plugin;

import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.metrics.InternalHDRPercentiles;
import org.elasticsearch.search.aggregations.metrics.InternalTDigestPercentiles;
import org.elasticsearch.search.aggregations.metrics.PercentilesAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.PercentilesMethod;
import org.elasticsearch.search.aggregations.metrics.TDigestState;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.analytics.AnalyticsPlugin;
import org.elasticsearch.xpack.core.XPackPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;


public class HistogramPercentileAggregationTests extends ESSingleNodeTestCase {

    public void testHDRHistogram() throws Exception {

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
              .startObject("_doc")
                .startObject("properties")
                  .startObject("data")
                     .field("type", "double")
                  .endObject()
                .endObject()
              .endObject()
            .endObject();
        createIndex("raw");
        PutMappingRequest request = new PutMappingRequest("raw").source(xContentBuilder);
        client().admin().indices().putMapping(request).actionGet();


        XContentBuilder xContentBuilder2 = XContentFactory.jsonBuilder()
            .startObject()
              .startObject("_doc")
                .startObject("properties")
                  .startObject("data")
                    .field("type", "histogram")
                  .endObject()
                .endObject()
              .endObject()
            .endObject();
        createIndex("pre_agg");
        PutMappingRequest request2 = new PutMappingRequest("pre_agg").source(xContentBuilder2);
        client().admin().indices().putMapping(request2).actionGet();


        int numberOfSignificantValueDigits = TestUtil.nextInt(random(), 1, 5);
        DoubleHistogram histogram = new DoubleHistogram(numberOfSignificantValueDigits);
        BulkRequest bulkRequest = new BulkRequest();

        int numDocs = 10000;
        int frq = 1000;

        for (int i =0; i < numDocs; i ++) {
            double value  = random().nextDouble();
            XContentBuilder doc = XContentFactory.jsonBuilder()
                .startObject()
                  .field("data", value)
                .endObject();
            bulkRequest.add(new IndexRequest("raw").source(doc));
            histogram.recordValue(value);
            if ((i + 1) % frq == 0) {
                client().bulk(bulkRequest);
                bulkRequest = new BulkRequest();
                List<Double> values = new ArrayList<>();
                List<Integer> counts = new ArrayList<>();
                Iterator<DoubleHistogramIterationValue> iterator = histogram.recordedValues().iterator();
                while (iterator.hasNext()) {
                    DoubleHistogramIterationValue histValue = iterator.next();
                    values.add(histValue.getValueIteratedTo());
                    counts.add(Math.toIntExact(histValue.getCountAtValueIteratedTo()));
                }
                XContentBuilder preAggDoc = XContentFactory.jsonBuilder()
                    .startObject()
                      .startObject("data")
                        .field("values", values.toArray(new Double[values.size()]))
                        .field("counts", counts.toArray(new Integer[counts.size()]))
                      .endObject()
                    .endObject();
                client().prepareIndex("pre_agg").setSource(preAggDoc).get();
                histogram.reset();
            }
        }
        client().admin().indices().refresh(new RefreshRequest("raw", "pre_agg")).get();

        SearchResponse response = client().prepareSearch("raw").setTrackTotalHits(true).get();
        assertEquals(numDocs, response.getHits().getTotalHits().value);

        response = client().prepareSearch("pre_agg").get();
        assertEquals(numDocs / frq, response.getHits().getTotalHits().value);

        PercentilesAggregationBuilder builder =
            AggregationBuilders.percentiles("agg").field("data").method(PercentilesMethod.HDR)
                .numberOfSignificantValueDigits(numberOfSignificantValueDigits).percentiles(10);

        SearchResponse responseRaw = client().prepareSearch("raw").addAggregation(builder).get();
        SearchResponse responsePreAgg = client().prepareSearch("pre_agg").addAggregation(builder).get();
        SearchResponse responseBoth = client().prepareSearch("pre_agg", "raw").addAggregation(builder).get();

        InternalHDRPercentiles percentilesRaw =  responseRaw.getAggregations().get("agg");
        InternalHDRPercentiles percentilesPreAgg =  responsePreAgg.getAggregations().get("agg");
        InternalHDRPercentiles percentilesBoth =  responseBoth.getAggregations().get("agg");
        for (int i = 1; i < 100; i++) {
            assertEquals(percentilesRaw.percentile(i), percentilesPreAgg.percentile(i), 0.0);
            assertEquals(percentilesRaw.percentile(i), percentilesBoth.percentile(i), 0.0);
        }
    }

    public void testTDigestHistogram() throws Exception {

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
              .startObject("_doc")
                .startObject("properties")
                  .startObject("data")
                    .field("type", "double")
                  .endObject()
                .endObject()
              .endObject()
            .endObject();
        createIndex("raw");
        PutMappingRequest request = new PutMappingRequest("raw").source(xContentBuilder);
        client().admin().indices().putMapping(request).actionGet();


        XContentBuilder xContentBuilder2 = XContentFactory.jsonBuilder()
            .startObject()
              .startObject("_doc")
                .startObject("properties")
                  .startObject("data")
                    .field("type", "histogram")
                  .endObject()
                .endObject()
              .endObject()
            .endObject();
        createIndex("pre_agg");
        PutMappingRequest request2 = new PutMappingRequest("pre_agg").source(xContentBuilder2);
        client().admin().indices().putMapping(request2).actionGet();


        int compression = TestUtil.nextInt(random(), 25, 300);
        TDigestState histogram = new TDigestState(compression);
        BulkRequest bulkRequest = new BulkRequest();

        int numDocs = 10000;
        int frq = 1000;

        for (int i =0; i < numDocs; i ++) {
            double value  = random().nextDouble();
            XContentBuilder doc = XContentFactory.jsonBuilder()
                .startObject()
                  .field("data", value)
                .endObject();
            bulkRequest.add(new IndexRequest("raw").source(doc));
            histogram.add(value);
            if ((i + 1) % frq == 0) {
                client().bulk(bulkRequest);
                bulkRequest = new BulkRequest();
                List<Double> values = new ArrayList<>();
                List<Integer> counts = new ArrayList<>();
                Collection<Centroid> centroids = histogram.centroids();
                for (Centroid centroid : centroids) {
                    values.add(centroid.mean());
                    counts.add(centroid.count());
                }
                XContentBuilder preAggDoc = XContentFactory.jsonBuilder()
                    .startObject()
                      .startObject("data")
                        .field("values", values.toArray(new Double[values.size()]))
                        .field("counts", counts.toArray(new Integer[counts.size()]))
                      .endObject()
                    .endObject();
                client().prepareIndex("pre_agg").setSource(preAggDoc).get();
                histogram = new TDigestState(compression);
            }
        }
        client().admin().indices().refresh(new RefreshRequest("raw", "pre_agg")).get();

        SearchResponse response = client().prepareSearch("raw").setTrackTotalHits(true).get();
        assertEquals(numDocs, response.getHits().getTotalHits().value);

        response = client().prepareSearch("pre_agg").get();
        assertEquals(numDocs / frq, response.getHits().getTotalHits().value);

        PercentilesAggregationBuilder builder =
            AggregationBuilders.percentiles("agg").field("data").method(PercentilesMethod.TDIGEST)
                .compression(compression).percentiles(10, 25, 500, 75);

        SearchResponse responseRaw = client().prepareSearch("raw").addAggregation(builder).get();
        SearchResponse responsePreAgg = client().prepareSearch("pre_agg").addAggregation(builder).get();
        SearchResponse responseBoth = client().prepareSearch("raw", "pre_agg").addAggregation(builder).get();

        InternalTDigestPercentiles percentilesRaw = responseRaw.getAggregations().get("agg");
        InternalTDigestPercentiles percentilesPreAgg = responsePreAgg.getAggregations().get("agg");
        InternalTDigestPercentiles percentilesBoth = responseBoth.getAggregations().get("agg");
        for (int i = 1; i < 100; i++) {
            assertEquals(percentilesRaw.percentile(i), percentilesPreAgg.percentile(i), 1e-2);
            assertEquals(percentilesRaw.percentile(i), percentilesBoth.percentile(i), 1e-2);
        }
    }


    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.getPlugins());
        plugins.add(AnalyticsPlugin.class);
        plugins.add(XPackPlugin.class);
        return plugins;
    }

}
