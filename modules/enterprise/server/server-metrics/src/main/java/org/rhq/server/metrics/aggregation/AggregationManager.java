package org.rhq.server.metrics.aggregation;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;

import org.rhq.server.metrics.AbortedException;
import org.rhq.server.metrics.DateTimeService;
import org.rhq.server.metrics.MetricsConfiguration;
import org.rhq.server.metrics.MetricsDAO;
import org.rhq.server.metrics.StorageClientThreadFactory;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.IndexBucket;

/**
 * @author John Sanda
 */
public class AggregationManager {

    private static final Comparator<AggregateNumericMetric> AGGREGATE_COMPARATOR = new Comparator<AggregateNumericMetric>() {
        @Override
        public int compare(AggregateNumericMetric left, AggregateNumericMetric right) {
            if (left.getScheduleId() == right.getScheduleId()) {
                if (left.getTimestamp() < right.getTimestamp()) {
                    return -1;
                } else if (left.getTimestamp() > right.getTimestamp()) {
                    return 1;
                } else {
                    return 0;
                }
            }
            if (left.getScheduleId() < right.getScheduleId()) {
                return -1;
            }
            return 1;
        }
    };

    private final Log log = LogFactory.getLog(AggregationManager.class);

    private MetricsDAO dao;

    private DateTimeService dtService;

    private ListeningExecutorService aggregationTasks;

    private MetricsConfiguration configuration;

    private int batchSize;

    private int parallelism;

    private AtomicLong totalAggregationTime = new AtomicLong();

    private ThreadPoolExecutor threadPool;

    public AggregationManager(MetricsDAO dao, DateTimeService dtService, MetricsConfiguration configuration) {

        this.dao = dao;
        this.dtService = dtService;
        this.configuration = configuration;
        batchSize = Integer.parseInt(System.getProperty("rhq.metrics.aggregation.batch-size", "5"));
        parallelism = Integer.parseInt(System.getProperty("rhq.metrics.aggregation.parallelism", "3"));

        int numWorkers = Integer.parseInt(System.getProperty("rhq.metrics.aggregation.workers", "4"));
        // We have to have more than 1 thread, otherwise we can deadlock during aggregation task scheduling.
        // See https://bugzilla.redhat.com/show_bug.cgi?id=1084626 for details
        if (numWorkers < 2) {
            numWorkers = 2;
        }
        threadPool = new ThreadPoolExecutor(numWorkers, numWorkers, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), new StorageClientThreadFactory("AggregationTasks"));
        aggregationTasks = MoreExecutors.listeningDecorator(threadPool);
    }

    public void shutdown() {
        aggregationTasks.shutdownNow();
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getParallelism() {
        return parallelism;
    }

    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    public int getNumWorkers() {
        return threadPool.getMaximumPoolSize();
    }

    public void setNumWorkers(int numWorkers) {
        log.debug("Setting aggregation worker thread count to " + numWorkers);
        threadPool.setCorePoolSize(numWorkers);
        threadPool.setMaximumPoolSize(numWorkers);
    }

    /**
     * @return The total aggregation time in milliseconds since server start. This property is updated after each of
     * raw, one hour, and six hour data are aggregated.
     */
    public long getTotalAggregationTime() {
        return totalAggregationTime.get();
    }

    public Set<AggregateNumericMetric> run() {
        log.info("Starting metrics data aggregation");
        Stopwatch stopwatch = Stopwatch.createStarted();
        Semaphore permits = new Semaphore(batchSize * parallelism);

        log.debug("Allocating " + permits.availablePermits() + " permits");

        int num1Hour = 0;
        int num6Hour = 0;
        int num24Hour = 0;
        try {
            PersistFunctions persistFunctions = new PersistFunctions(dao, dtService);
            final Set<AggregateNumericMetric> oneHourData = new ConcurrentSkipListSet<AggregateNumericMetric>(
                AGGREGATE_COMPARATOR);
            DateTime endTime = dtService.currentHour();
            DateTime end = endTime;
            // We set the start time to the retention period minus 1 hour, or 6 days and 23
            // hours ago instead of 7 days ago because if we set the start time to the full
            // 7 days, then we could end up in a situation where data has expired and
            // aggregate metric get overwritten with partial data.
            DateTime start = end.minus(configuration.getRawRetention().toPeriod().minusHours(1));
            DataAggregator rawAggregator = createRawAggregator(persistFunctions, permits);
            rawAggregator.setBatchFinishedListener(new DataAggregator.BatchFinishedListener() {
                @Override
                public void onFinish(List<AggregateNumericMetric> metrics) {
                    oneHourData.addAll(metrics);
                }
            });
            num1Hour = rawAggregator.execute(start, end);

            end = dtService.get6HourTimeSlice(endTime);
            start = dtService.get6HourTimeSlice(endTime).minus(configuration.getRawRetention());
            num6Hour = create1HourAggregator(persistFunctions, permits).execute(start, end);

            end = dtService.get24HourTimeSlice(endTime);
            start = dtService.get24HourTimeSlice(endTime).minus(configuration.getRawRetention());
            num24Hour = create6HourAggregator(persistFunctions, permits).execute(start, end);

            return oneHourData;
        } catch (InterruptedException e) {
            log.info("There was an interrupt while waiting for aggregation to finish. Aggregation will be aborted.");
            return Collections.emptySet();
        }
        catch (AbortedException e) {
            log.warn("Aggregation has been aborted: " + e.getMessage());
            return Collections.emptySet();
        } finally {
            stopwatch.stop();
            totalAggregationTime.addAndGet(stopwatch.elapsed(TimeUnit.MILLISECONDS));
            log.info("Finished aggregation of {\"raw schedules\": " + num1Hour + ", \"1 hour schedules\": " + num6Hour +
                ", \"6 hour schedules\": " + num24Hour + "} in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms");
        }
    }

    private DataAggregator createRawAggregator(PersistFunctions persistFunctions, Semaphore permits) {
        DataAggregator aggregator = new DataAggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setBucket(IndexBucket.RAW);
        aggregator.setTimeSliceDuration(configuration.getRawTimeSliceDuration());
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setDateTimeService(dtService);
        aggregator.setPersistMetrics(persistFunctions.persist1HourMetrics());
        aggregator.setConfiguration(configuration);
        aggregator.setBatchSize(batchSize);

        return aggregator;
    }

    private DataAggregator create1HourAggregator(PersistFunctions persistFunctions, Semaphore permits) {
        DataAggregator aggregator = new DataAggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setBucket(IndexBucket.ONE_HOUR);
        aggregator.setTimeSliceDuration(configuration.getOneHourTimeSliceDuration());
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setDateTimeService(dtService);
        aggregator.setPersistMetrics(persistFunctions.persist6HourMetrics());
        aggregator.setConfiguration(configuration);
        aggregator.setBatchSize(batchSize);

        return aggregator;
    }

    private DataAggregator create6HourAggregator(PersistFunctions persistFunctions, Semaphore permits) {
        DataAggregator aggregator = new DataAggregator();
        aggregator.setAggregationTasks(aggregationTasks);
        aggregator.setBucket(IndexBucket.SIX_HOUR);
        aggregator.setTimeSliceDuration(configuration.getSixHourTimeSliceDuration());
        aggregator.setDao(dao);
        aggregator.setPermits(permits);
        aggregator.setDateTimeService(dtService);
        aggregator.setPersistMetrics(persistFunctions.persist24HourMetrics());
        aggregator.setConfiguration(configuration);
        aggregator.setBatchSize(batchSize);

        return aggregator;
    }

}
