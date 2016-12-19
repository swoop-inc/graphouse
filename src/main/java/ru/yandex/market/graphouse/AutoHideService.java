package ru.yandex.market.graphouse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.yandex.market.graphouse.search.MetricDescription;
import ru.yandex.market.graphouse.search.MetricSearch;
import ru.yandex.market.graphouse.search.MetricStatus;
import ru.yandex.market.graphouse.search.MetricTree;
import ru.yandex.market.graphouse.utils.AppendableList;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Позволяет по таймеру выбирать "мусорные" метрики из кликхауса и автоматически их скрывать.
 * Метрика считается "мусорной", если выполняются условия:
 * - последние значение по ней было {@link #missingDays}
 * - число точек менее {@link #maxValuesCount}
 *
 * @author Dmitry Andreev <a href="mailto:AndreevDm@yandex-team.ru"></a>
 * @date 11/06/15
 */
public class AutoHideService implements InitializingBean, Runnable {

    private static final Logger log = LogManager.getLogger();
    private int stepSize = 10_000;

    private JdbcTemplate clickHouseJdbcTemplate;
    private MetricSearch metricSearch;
    private boolean enabled = true;

    private int maxValuesCount = 200;
    private int missingDays = 7;
    private int runDelayMinutes = 10;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private int retryCount = 10;
    private int retryWaitSeconds = 5000;

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled) {
            log.info("Autohide disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(this, runDelayMinutes, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
        log.info("Autohide scheduled");
    }

    @Override
    public void run() {
        if (metricSearch.isMetricTreeLoaded()) {
            hide();
        }
    }

    private void hide() {
        log.info("Running autohide.");
        try {
            MetricMinMaxChecker metricMinMaxChecker = new MetricMinMaxChecker();
            final AtomicInteger hiddenMetricCounter = new AtomicInteger();
            checkPath(MetricTree.ALL_PATTERN, metricMinMaxChecker, hiddenMetricCounter);

            log.info("Autohide completed. " + hiddenMetricCounter.get() + " metrics hidden");
        } catch (Exception e) {
            log.error("Failed to run autohide.", e);
        }
    }

    private void checkPath(String path, MetricMinMaxChecker metricMinMaxChecker, AtomicInteger hiddenCounter) throws IOException {
        AppendableList appendableList = new AppendableList();
        metricSearch.search(path, appendableList);

        for (MetricDescription metric : appendableList.getSortedList()) {
            if (metric.isDir()) {
                checkPath(metric.getName() + MetricTree.ALL_PATTERN, metricMinMaxChecker, hiddenCounter);
            } else {
                metricMinMaxChecker.addToCheck(metric.getName());
            }
        }

        if (path.equals(MetricTree.ALL_PATTERN) || metricMinMaxChecker.needCheckInDb()) {
            final int hiddenMetricCount = hideMetricsBetween(metricMinMaxChecker.minMetric, metricMinMaxChecker.maxMetric);
            hiddenCounter.addAndGet(hiddenMetricCount);
            metricMinMaxChecker.reset();
        }
    }

    /*
    * Проверяет метрики в диапазоне и возвращает количество скрытых
    * */

    private int hideMetricsBetween(String minMetric, String maxMetric) {
        if (minMetric == null || maxMetric == null) {
            return 0;
        }

        final AtomicInteger counter = new AtomicInteger();

        for (int i = 0; i < retryCount; i++) {
            try {
                clickHouseJdbcTemplate.query(
                    "SELECT Path, count() AS cnt, max(Timestamp) AS ts " +
                        "FROM graphite WHERE Path >= ? AND Path <= ?" +
                        "GROUP BY Path " +
                        "HAVING cnt < ? AND ts < toUInt32(toDateTime(today() - ?))",
                    row -> {
                        final String metric = row.getString(1);
                        metricSearch.modify(metric, MetricStatus.AUTO_HIDDEN);
                        counter.incrementAndGet();
                    },
                    minMetric, maxMetric, maxValuesCount, missingDays
                );

                break;
            } catch (Exception e) {
                boolean isLastTry = (i == retryCount - 1);
                if (!isLastTry) {
                    log.error("Write to clickhouse failed. Retry after " + retryWaitSeconds + " seconds", e);

                    try {
                        TimeUnit.SECONDS.sleep(retryWaitSeconds);
                    } catch (InterruptedException ie) {
                        log.error(ie);
                    }
                } else {
                    throw e;
                }
            }
        }

        log.info(counter.get() + " metrics hidden between <" + minMetric + "> and <" + maxMetric + ">");

        return counter.get();
    }

    private class MetricMinMaxChecker {
        private String minMetric = null;
        private String maxMetric = null;

        int lastCheckCounter = 0;

        private void reset() {
            minMetric = null;
            maxMetric = null;
            lastCheckCounter = 0;
        }

        private void addToCheck(String metric) {
            if (lastCheckCounter == 0) {
                minMetric = metric;
                maxMetric = metric;
            } else {
                if (minMetric.compareTo(metric) > 0) {
                    minMetric = metric;
                }

                if (maxMetric.compareTo(metric) < 0) {
                    maxMetric = metric;
                }
            }

            lastCheckCounter++;
        }

        private boolean needCheckInDb() {
            return lastCheckCounter > stepSize;
        }
    }

    public void setRunDelayMinutes(int runDelayMinutes) {
        this.runDelayMinutes = runDelayMinutes;
    }

    public void setMaxValuesCount(int maxValuesCount) {
        this.maxValuesCount = maxValuesCount;
    }

    public void setMissingDays(int missingDays) {
        this.missingDays = missingDays;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Required
    public void setMetricSearch(MetricSearch metricSearch) {
        this.metricSearch = metricSearch;
    }

    @Required
    public void setClickHouseJdbcTemplate(JdbcTemplate clickHouseJdbcTemplate) {
        this.clickHouseJdbcTemplate = clickHouseJdbcTemplate;
    }

    public void setStepSize(Integer stepSize) {
        this.stepSize = stepSize;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public void setRetryWaitSeconds(int retryWaitSeconds) {
        this.retryWaitSeconds = retryWaitSeconds;
    }
}
