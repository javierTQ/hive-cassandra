package org.apache.hadoop.hive.cassandra.input.cql;

import org.apache.cassandra.hadoop2.ColumnFamilySplit;
import org.apache.cassandra.hadoop2.ConfigHelper;
import org.apache.cassandra.hadoop2.cql3.CqlPagingInputFormat;
import org.apache.cassandra.hadoop2.cql3.CqlPagingRecordReader;
import org.apache.cassandra.thrift.ColumnDef;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.cassandra.input.HiveCassandraStandardSplit;
import org.apache.hadoop.hive.cassandra.serde.AbstractCassandraSerDe;
import org.apache.hadoop.hive.cassandra.serde.cql.CqlSerDe;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.index.IndexPredicateAnalyzer;
import org.apache.hadoop.hive.ql.index.IndexSearchCondition;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.TableScanDesc;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.task.*;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hive.cassandra.cql.CqlPushdownPredicate;

public class HiveCqlInputFormat extends InputFormat<MapWritableComparable, MapWritable>
        implements org.apache.hadoop.mapred.InputFormat<MapWritableComparable, MapWritable> {

    static final Logger LOG = LoggerFactory.getLogger(HiveCqlInputFormat.class);

    private final CqlPagingInputFormat cfif = new CqlPagingInputFormat();

    @Override
    public RecordReader<MapWritableComparable, MapWritable> getRecordReader(InputSplit split,
            JobConf jobConf, final Reporter reporter) throws IOException {
        HiveCassandraStandardSplit cassandraSplit = (HiveCassandraStandardSplit) split;

        List<String> columns = CqlSerDe.parseColumnMapping(cassandraSplit.getColumnMapping());

        List<Integer> readColIDs = ColumnProjectionUtils.getReadColumnIDs(jobConf);

        if (columns.size() < readColIDs.size()) {
            throw new IOException("Cannot read more columns than the given table contains.");
        }

        ColumnFamilySplit cfSplit = cassandraSplit.getSplit();
        Job job = new Job(jobConf);

        TaskAttemptContext tac = new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID()) {
            @Override
            public void progress() {
                reporter.progress();
            }
        };

        SlicePredicate predicate = new SlicePredicate();

        predicate.setColumn_names(getColumnNames(columns, readColIDs));

        try {

            boolean wideRows = true;

            ConfigHelper.setInputColumnFamily(tac.getConfiguration(),
                    cassandraSplit.getKeyspace(), cassandraSplit.getColumnFamily(), wideRows);

            ConfigHelper.setInputSlicePredicate(tac.getConfiguration(), predicate);
            ConfigHelper.setRangeBatchSize(tac.getConfiguration(), cassandraSplit.getRangeBatchSize());
            ConfigHelper.setInputRpcPort(tac.getConfiguration(), cassandraSplit.getPort() + "");
            ConfigHelper.setInputInitialAddress(tac.getConfiguration(), cassandraSplit.getHost());
            ConfigHelper.setInputPartitioner(tac.getConfiguration(), cassandraSplit.getPartitioner());
            // Set Split Size
            ConfigHelper.setInputSplitSize(tac.getConfiguration(), cassandraSplit.getSplitSize());

            LOG.info("Validators : " + tac.getConfiguration().get(CqlSerDe.CASSANDRA_VALIDATOR_TYPE));
            List<IndexExpression> indexExpr = parseFilterPredicate(jobConf);
            if (indexExpr != null) {
                //We have pushed down a filter from the Hive query, we can use this against secondary indexes
                ConfigHelper.setInputRange(tac.getConfiguration(), indexExpr);
            }

            CqlHiveRecordReader rr = new CqlHiveRecordReader(new CqlPagingRecordReader());

            rr.initialize(cfSplit, tac);

            return rr;

        } catch (Exception ie) {
            throw new IOException(ie);
        }
    }

    @Override
    public InputSplit[] getSplits(JobConf jobConf, int numSplits) throws IOException {
        String ks = jobConf.get(AbstractCassandraSerDe.CASSANDRA_KEYSPACE_NAME);
        String cf = jobConf.get(AbstractCassandraSerDe.CASSANDRA_CF_NAME);
        int slicePredicateSize = jobConf.getInt(AbstractCassandraSerDe.CASSANDRA_SLICE_PREDICATE_SIZE,
                AbstractCassandraSerDe.DEFAULT_SLICE_PREDICATE_SIZE);
        int sliceRangeSize = jobConf.getInt(
                AbstractCassandraSerDe.CASSANDRA_RANGE_BATCH_SIZE,
                AbstractCassandraSerDe.DEFAULT_RANGE_BATCH_SIZE);
        int splitSize = jobConf.getInt(
                AbstractCassandraSerDe.CASSANDRA_SPLIT_SIZE,
                AbstractCassandraSerDe.DEFAULT_SPLIT_SIZE);
        String cassandraColumnMapping = jobConf.get(AbstractCassandraSerDe.CASSANDRA_COL_MAPPING);
        int rpcPort = jobConf.getInt(AbstractCassandraSerDe.CASSANDRA_PORT, 9160);
        String host = jobConf.get(AbstractCassandraSerDe.CASSANDRA_HOST);
        String partitioner = jobConf.get(AbstractCassandraSerDe.CASSANDRA_PARTITIONER);

        if (cassandraColumnMapping == null) {
            throw new IOException("cassandra.columns.mapping required for Cassandra Table.");
        }

        SliceRange range = new SliceRange();
        range.setStart(new byte[0]);
        range.setFinish(new byte[0]);
        range.setReversed(false);
        range.setCount(slicePredicateSize);
        SlicePredicate predicate = new SlicePredicate();
        predicate.setSlice_range(range);

        ConfigHelper.setInputRpcPort(jobConf, "" + rpcPort);
        ConfigHelper.setInputInitialAddress(jobConf, host);
        ConfigHelper.setInputPartitioner(jobConf, partitioner);
        ConfigHelper.setInputSlicePredicate(jobConf, predicate);
        ConfigHelper.setInputColumnFamily(jobConf, ks, cf);
        ConfigHelper.setRangeBatchSize(jobConf, sliceRangeSize);
        ConfigHelper.setInputSplitSize(jobConf, splitSize);

        Job job = new Job(jobConf);
        JobContext jobContext = new JobContextImpl(job.getConfiguration(), job.getJobID());

        Path[] tablePaths = FileInputFormat.getInputPaths(jobContext);
        List<org.apache.hadoop.mapreduce.InputSplit> splits = getSplits(jobContext);
        InputSplit[] results = new InputSplit[splits.size()];

        for (int i = 0; i < splits.size(); ++i) {
            HiveCassandraStandardSplit csplit = new HiveCassandraStandardSplit(
                    (ColumnFamilySplit) splits.get(i), cassandraColumnMapping, tablePaths[0]);
            csplit.setKeyspace(ks);
            csplit.setColumnFamily(cf);
            csplit.setRangeBatchSize(sliceRangeSize);
            csplit.setSplitSize(splitSize);
            csplit.setHost(host);
            csplit.setPort(rpcPort);
            csplit.setSlicePredicateSize(slicePredicateSize);
            csplit.setPartitioner(partitioner);
            csplit.setColumnMapping(cassandraColumnMapping);
            results[i] = csplit;
        }
        return results;
    }

    /**
     * Return a list of columns names to read from cassandra. The column defined
     * as the key in the column mapping should be skipped.
     *
     * @param columns column mapping
     * @param readColIDs column names to read from cassandra
     */
    private List<ByteBuffer> getColumnNames(List<String> columns, List<Integer> readColIDs) {

        List<ByteBuffer> results = new ArrayList<ByteBuffer>();
        int maxSize = columns.size();

        for (Integer i : readColIDs) {
            assert (i < maxSize);
            results.add(ByteBufferUtil.bytes(columns.get(i.intValue())));
        }

        return results;
    }

    @Override
    public List<org.apache.hadoop.mapreduce.InputSplit> getSplits(JobContext context)
            throws IOException {
        return cfif.getSplits(context);
    }

    @Override
    public org.apache.hadoop.mapreduce.RecordReader<MapWritableComparable, MapWritable> createRecordReader(
            org.apache.hadoop.mapreduce.InputSplit arg0, TaskAttemptContext tac) throws IOException,
            InterruptedException {

        return new CqlHiveRecordReader(new CqlPagingRecordReader());
    }

    /**
     * Look for a filter predicate pushed down by the StorageHandler. If a
     * filter was pushed down, the filter expression and the list of indexed
     * columns should be set in the JobConf properties. If either is not set, we
     * can't deal with the filter here so return null. If both are present in
     * the JobConf, translate the filter expression into a list of C*
     * IndexExpressions which we'll later use in queries. The filter expression
     * should translate exactly to IndexExpressions, as our
     * HiveStoragePredicateHandler implementation has already done this once. As
     * an additional check, if this is no longer the case & there is some
     * residual predicate after translation, throw an Exception.
     *
     * @param jobConf Job Configuration
     * @return C* IndexExpressions representing the pushed down filter or null
     * pushdown is not possible
     * @throws java.io.IOException if there are problems deserializing from the
     * JobConf
     */
    private List<IndexExpression> parseFilterPredicate(JobConf jobConf) throws IOException {
        String filterExprSerialized = jobConf.get(TableScanDesc.FILTER_EXPR_CONF_STR);
        if (filterExprSerialized == null) {
            return null;
        }

        ExprNodeDesc filterExpr = Utilities.deserializeExpression(filterExprSerialized);
        String encodedIndexedColumns = jobConf.get(AbstractCassandraSerDe.CASSANDRA_INDEXED_COLUMNS);
        Set<ColumnDef> indexedColumns = CqlPushdownPredicate.deserializeIndexedColumns(encodedIndexedColumns);
        if (indexedColumns.isEmpty()) {
            return null;
        }

        IndexPredicateAnalyzer analyzer = CqlPushdownPredicate.newIndexPredicateAnalyzer(indexedColumns);
        List<IndexSearchCondition> searchConditions = new ArrayList<IndexSearchCondition>();
        ExprNodeDesc residualPredicate = analyzer.analyzePredicate(filterExpr, searchConditions);

        // There should be no residual predicate since we already negotiated
        // that earlier in CqlStorageHandler.decomposePredicate.
        if (residualPredicate != null) {
            throw new RuntimeException("Unexpected residual predicate : " + residualPredicate.getExprString());
        }

        if (!searchConditions.isEmpty()) {
            return CqlPushdownPredicate.translateSearchConditions(searchConditions, indexedColumns);
        } else {
            throw new RuntimeException("At least one search condition expected in filter predicate");
        }
    }
}
