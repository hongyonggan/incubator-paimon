/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.sorter;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.JoinedRow;
import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.flink.FlinkRowData;
import org.apache.paimon.flink.FlinkRowWrapper;
import org.apache.paimon.flink.shuffle.RangeShuffle;
import org.apache.paimon.flink.utils.InternalTypeInfo;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.RowType;
import org.apache.paimon.utils.KeyProjectedRow;
import org.apache.paimon.utils.SerializableSupplier;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.table.data.RowData;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * This is a table sorter which will sort the records by the key of the shuffleKeyComparator
 * generates. It is a global sort method, we will shuffle the input stream through shuffleKey. After
 * sorted, we convert datastream from paimon RowData back to Flink RowData
 *
 * <pre>
 *                         toPaimonDataStream                         add key column                                  range shuffle by key                                    local sort                                              remove key
 * DataStream[RowData] --------------------> DataStream[PaimonRowData] -------------------> DataStream[PaimonRowData] -------------------------> DataStream[PaimonRowData] -----------------------> DataStream[PaimonRowData sorted] ---------------------> DataStream[RowData sorted]
 *                                                                                                                                                                                                                                   back to flink RowData
 * </pre>
 */
public class SortUtils {

    /**
     * Sort the input stream by the key specified.
     *
     * @param inputStream the input data stream
     * @param table the sorted file store table
     * @param sortKeyType we will use paimon `BinaryExternalSortBuffer` to local sort, so we need to
     *     specify the key type.
     * @param keyTypeInformation we will use range shuffle in global sort, so we need to range
     *     shuffle by the key first.
     * @param shuffleKeyComparator comparator to compare the key when shuffle
     * @param shuffleKeyAbstract abstract the key from the input `RowData`
     * @param convertor convert the `KEY` to the sort key, then we can sort in
     *     `BinaryExternalSortBuffer`.
     * @return the global sorted data stream
     * @param <KEY> the KEY type in range shuffle
     */
    public static <KEY> DataStream<RowData> sortStreamByKey(
            final DataStream<RowData> inputStream,
            final FileStoreTable table,
            final RowType sortKeyType,
            final TypeInformation<KEY> keyTypeInformation,
            final SerializableSupplier<Comparator<KEY>> shuffleKeyComparator,
            final KeyAbstract<KEY> shuffleKeyAbstract,
            final ShuffleKeyConvertor<KEY> convertor) {

        final RowType valueRowType = table.rowType();
        final int parallelism = inputStream.getParallelism();
        CoreOptions options = table.coreOptions();

        String sinkParallelismValue =
                table.options().get(FlinkConnectorOptions.SINK_PARALLELISM.key());
        final int sinkParallelism =
                sinkParallelismValue == null
                        ? inputStream.getParallelism()
                        : Integer.parseInt(sinkParallelismValue);
        if (sinkParallelism == -1) {
            throw new UnsupportedOperationException(
                    "The adaptive batch scheduler is not supported. Please set the sink parallelism using the key: "
                            + FlinkConnectorOptions.SINK_PARALLELISM.key());
        }
        final int sampleSize = sinkParallelism * 1000;
        final int rangeNum = sinkParallelism * 10;

        int keyFieldCount = sortKeyType.getFieldCount();
        int valueFieldCount = valueRowType.getFieldCount();
        final int[] valueProjectionMap = new int[valueFieldCount];
        for (int i = 0; i < valueFieldCount; i++) {
            valueProjectionMap[i] = i + keyFieldCount;
        }

        List<DataField> keyFields = sortKeyType.getFields();
        List<DataField> dataFields = valueRowType.getFields();

        List<DataField> fields = new ArrayList<>();
        fields.addAll(keyFields);
        fields.addAll(dataFields);
        final RowType longRowType = new RowType(fields);
        final InternalTypeInfo<InternalRow> internalRowType =
                InternalTypeInfo.fromRowType(longRowType);

        // generate the KEY as the key of Pair.
        DataStream<Tuple2<KEY, RowData>> inputWithKey =
                inputStream
                        .map(
                                new RichMapFunction<RowData, Tuple2<KEY, RowData>>() {

                                    @Override
                                    public void open(Configuration parameters) throws Exception {
                                        super.open(parameters);
                                        shuffleKeyAbstract.open();
                                    }

                                    @Override
                                    public Tuple2<KEY, RowData> map(RowData value) {
                                        return Tuple2.of(shuffleKeyAbstract.apply(value), value);
                                    }
                                },
                                new TupleTypeInfo<>(keyTypeInformation, inputStream.getType()))
                        .setParallelism(parallelism);

        // range shuffle by key
        return RangeShuffle.rangeShuffleByKey(
                        inputWithKey,
                        shuffleKeyComparator,
                        keyTypeInformation,
                        sampleSize,
                        rangeNum,
                        sinkParallelism)
                .map(
                        a -> new JoinedRow(convertor.apply(a.f0), new FlinkRowWrapper(a.f1)),
                        internalRowType)
                .setParallelism(sinkParallelism)
                // sort the output locally by `SortOperator`
                .transform(
                        "LOCAL SORT",
                        internalRowType,
                        new SortOperator(
                                sortKeyType,
                                longRowType,
                                options.writeBufferSize(),
                                options.pageSize(),
                                options.localSortMaxNumFileHandles(),
                                sinkParallelism))
                .setParallelism(sinkParallelism)
                // remove the key column from every row
                .map(
                        new RichMapFunction<InternalRow, InternalRow>() {

                            private transient KeyProjectedRow keyProjectedRow;

                            @Override
                            public void open(Configuration parameters) {
                                keyProjectedRow = new KeyProjectedRow(valueProjectionMap);
                            }

                            @Override
                            public InternalRow map(InternalRow value) {
                                return keyProjectedRow.replaceRow(value);
                            }
                        },
                        InternalTypeInfo.fromRowType(valueRowType))
                .setParallelism(sinkParallelism)
                .map(FlinkRowData::new, inputStream.getType())
                .setParallelism(sinkParallelism);
    }

    /** Abstract key from a row data. */
    interface KeyAbstract<KEY> extends Serializable {
        default void open() {}

        KEY apply(RowData value);
    }

    interface ShuffleKeyConvertor<KEY> extends Function<KEY, InternalRow>, Serializable {}
}
