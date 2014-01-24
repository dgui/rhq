package org.rhq.server.metrics.aggregation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;

import org.rhq.server.metrics.ArithmeticMeanCalculator;
import org.rhq.server.metrics.MetricsDAO;
import org.rhq.server.metrics.StorageResultSetFuture;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.AggregateType;
import org.rhq.server.metrics.domain.MetricsTable;

/**
 * Computes 6 hour data for a batch of raw data result sets. The generated 6 hour aggregates are inserted along with
 * their corresponding index updates.
 *
 * @author John Sanda
 */
class Compute6HourData implements AsyncFunction<List<ResultSet>, List<ResultSet>> {

    private final Log log = LogFactory.getLog(Compute6HourData.class);

    private DateTime startTime;

    private MetricsDAO dao;

    private DateTime twentyFourHourTimeSlice;

    public Compute6HourData(DateTime startTime, DateTime twentyFourHourTimeSlice, MetricsDAO dao) {
        this.startTime = startTime;
        this.twentyFourHourTimeSlice = twentyFourHourTimeSlice;
        this.dao = dao;
    }

    @Override
    public ListenableFuture<List<ResultSet>> apply(List<ResultSet> oneHourDataResultSets) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Computing and storing 6 hour data for " + oneHourDataResultSets.size() + " schedules");
        }
        Stopwatch stopwatch = new Stopwatch().start();
        try {
            List<StorageResultSetFuture> insertFutures =
                new ArrayList<StorageResultSetFuture>(oneHourDataResultSets.size());
            for (ResultSet resultSet : oneHourDataResultSets) {
                if (resultSet == null) {
                    // resultSet could be null if the 1 hr data query failed for whatever reason. We currently lack
                    // a way of correlating the failed query back to a schedule id. It could be useful to log the
                    // schedule id, possibly for debugging purposes.
                    continue;
                }
                AggregateNumericMetric aggregate = calculateAggregate(resultSet);
                insertFutures.add(dao.insertSixHourDataAsync(aggregate.getScheduleId(), aggregate.getTimestamp(),
                    AggregateType.MIN, aggregate.getMin()));
                insertFutures.add(dao.insertSixHourDataAsync(aggregate.getScheduleId(), aggregate.getTimestamp(),
                    AggregateType.MAX, aggregate.getMax()));
                insertFutures.add(dao.insertSixHourDataAsync(aggregate.getScheduleId(), aggregate.getTimestamp(),
                    AggregateType.AVG, aggregate.getAvg()));
                insertFutures.add(dao.updateMetricsIndex(MetricsTable.TWENTY_FOUR_HOUR, aggregate.getScheduleId(),
                    twentyFourHourTimeSlice.getMillis()));
            }
            return Futures.successfulAsList(insertFutures);
        } finally {
            if (log.isDebugEnabled()) {
                stopwatch.stop();
                log.debug("Finished computing and storing 6 hour data for " + oneHourDataResultSets.size() +
                    " schedules in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
            }
        }
    }

    private AggregateNumericMetric calculateAggregate(ResultSet resultSet) {
        double min = Double.NaN;
        double max = min;
        ArithmeticMeanCalculator mean = new ArithmeticMeanCalculator();
        List<Row> rows = resultSet.all();

        // Inserting an aggregate metric currently requires three writes. If one of those writes fails, we could wind
        // update with an IndexOutOfBoundsException in the loop below because it assumes rows.size() % 3 == 0 and that
        // would could be false if writes fail. This won't be an issue though when the changes for BZ 1049054 are
        // implemented, and this long comment can then be deleted.

        for (int i = 0; i < rows.size(); i += 3) {
            if (i == 0) {
                min = rows.get(i + 1).getDouble(3);
                max = rows.get(i).getDouble(3);
            } else {
                if (rows.get(i + 1).getDouble(3) < min) {
                    min = rows.get(i + 1).getDouble(3);
                }
                if (rows.get(i).getDouble(3) > max) {
                    max = rows.get(i).getDouble(3);
                }
            }
            mean.add(rows.get(i + 2).getDouble(3));
        }
        return new AggregateNumericMetric(rows.get(0).getInt(0), mean.getArithmeticMean(), min, max,
            startTime.getMillis());
    }
}
