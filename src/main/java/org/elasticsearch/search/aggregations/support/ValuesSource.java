/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.support;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.RandomAccessOrds;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.ScorerAware;
import org.elasticsearch.index.fielddata.AtomicOrdinalsFieldData;
import org.elasticsearch.index.fielddata.AtomicParentChildFieldData;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexGeoPointFieldData;
import org.elasticsearch.index.fielddata.IndexNumericFieldData;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.index.fielddata.IndexParentChildFieldData;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.index.fielddata.SortingBinaryDocValues;
import org.elasticsearch.index.fielddata.SortingNumericDocValues;
import org.elasticsearch.index.fielddata.SortingNumericDoubleValues;
import org.elasticsearch.index.fielddata.plain.ParentChildIndexFieldData;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.aggregations.support.ValuesSource.WithScript.BytesValues;
import org.elasticsearch.search.aggregations.support.values.ScriptBytesValues;
import org.elasticsearch.search.aggregations.support.values.ScriptDoubleValues;
import org.elasticsearch.search.aggregations.support.values.ScriptLongValues;

import java.io.IOException;

public abstract class ValuesSource {

    /**
     * Get the current {@link BytesValues}.
     */
    public abstract SortedBinaryDocValues bytesValues(LeafReaderContext context) throws IOException;

    public abstract Bits docsWithValue(LeafReaderContext context) throws IOException;

    /** Whether this values source needs scores. */
    public boolean needsScores() {
        return false;
    }

    public static abstract class Bytes extends ValuesSource {

        public Bits docsWithValue(LeafReaderContext context) throws IOException {
            final SortedBinaryDocValues bytes = bytesValues(context);
            if (org.elasticsearch.index.fielddata.FieldData.unwrapSingleton(bytes) != null) {
                return org.elasticsearch.index.fielddata.FieldData.unwrapSingletonBits(bytes);
            } else {
                return org.elasticsearch.index.fielddata.FieldData.docsWithValue(bytes, context.reader().maxDoc());
            }
        }

        public static abstract class WithOrdinals extends Bytes {

            public Bits docsWithValue(LeafReaderContext context) {
                final RandomAccessOrds ordinals = ordinalsValues(context);
                if (DocValues.unwrapSingleton(ordinals) != null) {
                    return DocValues.docsWithValue(DocValues.unwrapSingleton(ordinals), context.reader().maxDoc());
                } else {
                    return DocValues.docsWithValue(ordinals, context.reader().maxDoc());
                }
            }

            public abstract RandomAccessOrds ordinalsValues(LeafReaderContext context);

            public abstract RandomAccessOrds globalOrdinalsValues(LeafReaderContext context);

            public abstract long globalMaxOrd(IndexSearcher indexSearcher);

            public static class FieldData extends WithOrdinals {

                protected final IndexOrdinalsFieldData indexFieldData;

                public FieldData(IndexOrdinalsFieldData indexFieldData) {
                    this.indexFieldData = indexFieldData;
                }

                @Override
                public SortedBinaryDocValues bytesValues(LeafReaderContext context) {
                    final AtomicOrdinalsFieldData atomicFieldData = indexFieldData.load(context);
                    return atomicFieldData.getBytesValues();
                }

                @Override
                public RandomAccessOrds ordinalsValues(LeafReaderContext context) {
                    final AtomicOrdinalsFieldData atomicFieldData = indexFieldData.load(context);
                    return atomicFieldData.getOrdinalsValues();
                }

                @Override
                public RandomAccessOrds globalOrdinalsValues(LeafReaderContext context) {
                    final IndexOrdinalsFieldData global = indexFieldData.loadGlobal(context.parent.reader());
                    final AtomicOrdinalsFieldData atomicFieldData = global.load(context);
                    return atomicFieldData.getOrdinalsValues();
                }

                @Override
                public long globalMaxOrd(IndexSearcher indexSearcher) {
                    IndexReader indexReader = indexSearcher.getIndexReader();
                    if (indexReader.leaves().isEmpty()) {
                        return 0;
                    } else {
                        LeafReaderContext atomicReaderContext = indexReader.leaves().get(0);
                        IndexOrdinalsFieldData globalFieldData = indexFieldData.loadGlobal(indexReader);
                        AtomicOrdinalsFieldData afd = globalFieldData.load(atomicReaderContext);
                        RandomAccessOrds values = afd.getOrdinalsValues();
                        return values.getValueCount();
                    }
                }
            }
        }

        public static class ParentChild extends Bytes {

            protected final ParentChildIndexFieldData indexFieldData;

            public ParentChild(ParentChildIndexFieldData indexFieldData) {
                this.indexFieldData = indexFieldData;
            }

            public long globalMaxOrd(IndexSearcher indexSearcher, String type) {
                IndexReader indexReader = indexSearcher.getIndexReader();
                if (indexReader.leaves().isEmpty()) {
                    return 0;
                } else {
                    LeafReaderContext atomicReaderContext = indexReader.leaves().get(0);
                    IndexParentChildFieldData globalFieldData = indexFieldData.loadGlobal(indexReader);
                    AtomicParentChildFieldData afd = globalFieldData.load(atomicReaderContext);
                    SortedDocValues values = afd.getOrdinalsValues(type);
                    return values.getValueCount();
                }
            }

            public SortedDocValues globalOrdinalsValues(String type, LeafReaderContext context) {
                final IndexParentChildFieldData global = indexFieldData.loadGlobal(context.parent.reader());
                final AtomicParentChildFieldData atomicFieldData = global.load(context);
                return atomicFieldData.getOrdinalsValues(type);
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) {
                final AtomicParentChildFieldData atomicFieldData = indexFieldData.load(context);
                return atomicFieldData.getBytesValues();
            }
        }

        public static class FieldData extends Bytes {

            protected final IndexFieldData<?> indexFieldData;

            public FieldData(IndexFieldData<?> indexFieldData) {
                this.indexFieldData = indexFieldData;
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) {
                return indexFieldData.load(context).getBytesValues();
            }
        }

        public static class Script extends Bytes {

            private final ScriptBytesValues values;

            public Script(SearchScript script) {
                values = new ScriptBytesValues(script);
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) throws IOException {
                values.script().setNextReader(context);
                return values;
            }

            @Override
            public boolean needsScores() {
                // TODO: add a way to know whether scripts are using scores
                return true;
            }
        }


    }

    public static abstract class Numeric extends ValuesSource {

        /** Whether the underlying data is floating-point or not. */
        public abstract boolean isFloatingPoint();

        /** Get the current {@link SortedNumericDocValues}. */
        public abstract SortedNumericDocValues longValues(LeafReaderContext context) throws IOException;

        /** Get the current {@link SortedNumericDoubleValues}. */
        public abstract SortedNumericDoubleValues doubleValues(LeafReaderContext context) throws IOException;

        @Override
        public Bits docsWithValue(LeafReaderContext context) throws IOException {
            if (isFloatingPoint()) {
                final SortedNumericDoubleValues values = doubleValues(context);
                if (org.elasticsearch.index.fielddata.FieldData.unwrapSingleton(values) != null) {
                    return org.elasticsearch.index.fielddata.FieldData.unwrapSingletonBits(values);
                } else {
                    return org.elasticsearch.index.fielddata.FieldData.docsWithValue(values, context.reader().maxDoc());
                }
            } else {
                final SortedNumericDocValues values = longValues(context);
                if (DocValues.unwrapSingleton(values) != null) {
                    return DocValues.unwrapSingletonBits(values);
                } else {
                    return DocValues.docsWithValue(values, context.reader().maxDoc());
                }
            }
        }

        public static class WithScript extends Numeric {

            private final Numeric delegate;
            private final SearchScript script;

            public WithScript(Numeric delegate, SearchScript script) {
                this.delegate = delegate;
                this.script = script;
            }

            @Override
            public boolean isFloatingPoint() {
                return true; // even if the underlying source produces longs, scripts can change them to doubles
            }

            @Override
            public boolean needsScores() {
                // TODO: add a way to know whether scripts are using scores
                return true;
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new ValuesSource.WithScript.BytesValues(delegate.bytesValues(context), script);
            }

            @Override
            public SortedNumericDocValues longValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new LongValues(delegate.longValues(context), script);
            }

            @Override
            public SortedNumericDoubleValues doubleValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new DoubleValues(delegate.doubleValues(context), script);
            }

            static class LongValues extends SortingNumericDocValues implements ScorerAware {

                private final SortedNumericDocValues longValues;
                private final SearchScript script;

                public LongValues(SortedNumericDocValues values, SearchScript script) {
                    this.longValues = values;
                    this.script = script;
                }

                @Override
                public void setDocument(int doc) {
                    longValues.setDocument(doc);
                    resize(longValues.count());
                    script.setNextDocId(doc);
                    for (int i = 0; i < count(); ++i) {
                        script.setNextVar("_value", longValues.valueAt(i));
                        values[i] = script.runAsLong();
                    }
                    sort();
                }

                @Override
                public void setScorer(Scorer scorer) {
                    script.setScorer(scorer);
                }
            }

            static class DoubleValues extends SortingNumericDoubleValues implements ScorerAware {

                private final SortedNumericDoubleValues doubleValues;
                private final SearchScript script;

                public DoubleValues(SortedNumericDoubleValues values, SearchScript script) {
                    this.doubleValues = values;
                    this.script = script;
                }

                @Override
                public void setDocument(int doc) {
                    doubleValues.setDocument(doc);
                    resize(doubleValues.count());
                    script.setNextDocId(doc);
                    for (int i = 0; i < count(); ++i) {
                        script.setNextVar("_value", doubleValues.valueAt(i));
                        values[i] = script.runAsDouble();
                    }
                    sort();
                }

                @Override
                public void setScorer(Scorer scorer) {
                    script.setScorer(scorer);
                }
            }
        }

        public static class FieldData extends Numeric {

            protected final IndexNumericFieldData indexFieldData;

            public FieldData(IndexNumericFieldData indexFieldData) {
                this.indexFieldData = indexFieldData;
            }

            @Override
            public boolean isFloatingPoint() {
                return indexFieldData.getNumericType().isFloatingPoint();
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) {
                return indexFieldData.load(context).getBytesValues();
            }

            @Override
            public SortedNumericDocValues longValues(LeafReaderContext context) {
                return indexFieldData.load(context).getLongValues();
            }

            @Override
            public SortedNumericDoubleValues doubleValues(LeafReaderContext context) {
                return indexFieldData.load(context).getDoubleValues();
            }
        }

        public static class Script extends Numeric {
            private final SearchScript script;
            private final ValueType scriptValueType;

            public Script(SearchScript script, ValueType scriptValueType) {
                this.script = script;
                this.scriptValueType = scriptValueType;
            }

            @Override
            public boolean isFloatingPoint() {
                return scriptValueType != null ? scriptValueType.isFloatingPoint() : true;
            }

            @Override
            public SortedNumericDocValues longValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new ScriptLongValues(script);
            }

            @Override
            public SortedNumericDoubleValues doubleValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new ScriptDoubleValues(script);
            }

            @Override
            public SortedBinaryDocValues bytesValues(LeafReaderContext context) throws IOException {
                script.setNextReader(context);
                return new ScriptBytesValues(script);
            }

            @Override
            public boolean needsScores() {
                // TODO: add a way to know whether scripts are using scores
                return true;
            }
        }

    }

    // No need to implement ReaderContextAware here, the delegate already takes care of updating data structures
    public static class WithScript extends Bytes {

        private final ValuesSource delegate;
        private final SearchScript script;

        public WithScript(ValuesSource delegate, SearchScript script) {
            this.delegate = delegate;
            this.script = script;
        }

        @Override
        public boolean needsScores() {
            // TODO: add a way to know whether scripts are using scores
            return true;
        }

        @Override
        public SortedBinaryDocValues bytesValues(LeafReaderContext context) throws IOException {
            script.setNextReader(context);
            return new BytesValues(delegate.bytesValues(context), script);
        }

        static class BytesValues extends SortingBinaryDocValues implements ScorerAware {

            private final SortedBinaryDocValues bytesValues;
            private final SearchScript script;

            public BytesValues(SortedBinaryDocValues bytesValues, SearchScript script) {
                this.bytesValues = bytesValues;
                this.script = script;
            }

            @Override
            public void setDocument(int docId) {
                bytesValues.setDocument(docId);
                count = bytesValues.count();
                grow();
                for (int i = 0; i < count; ++i) {
                    final BytesRef value = bytesValues.valueAt(i);
                    script.setNextVar("_value", value.utf8ToString());
                    values[i].copyChars(script.run().toString());
                }
                sort();
            }

            @Override
            public void setScorer(Scorer scorer) {
                script.setScorer(scorer);
            }
        }
    }

    public static class GeoPoint extends ValuesSource {

        protected final IndexGeoPointFieldData indexFieldData;

        public GeoPoint(IndexGeoPointFieldData indexFieldData) {
            this.indexFieldData = indexFieldData;
        }

        @Override
        public Bits docsWithValue(LeafReaderContext context) {
            final MultiGeoPointValues geoPoints = geoPointValues(context);
            if (org.elasticsearch.index.fielddata.FieldData.unwrapSingleton(geoPoints) != null) {
                return org.elasticsearch.index.fielddata.FieldData.unwrapSingletonBits(geoPoints);
            } else {
                return org.elasticsearch.index.fielddata.FieldData.docsWithValue(geoPoints, context.reader().maxDoc());
            }
        }

        @Override
        public SortedBinaryDocValues bytesValues(LeafReaderContext context) {
            return indexFieldData.load(context).getBytesValues();
        }

        public org.elasticsearch.index.fielddata.MultiGeoPointValues geoPointValues(LeafReaderContext context) {
            return indexFieldData.load(context).getGeoPointValues();
        }
    }

}
