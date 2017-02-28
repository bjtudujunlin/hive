package org.apache.hadoop.hive.ql.exec.tez.monitoring;

import org.apache.hadoop.hive.common.log.InPlaceUpdate;
import org.apache.hadoop.hive.common.log.ProgressMonitor;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.tez.dag.api.client.DAGStatus;
import org.apache.tez.dag.api.client.Progress;

import java.io.StringWriter;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

class RenderStrategy {

  interface UpdateFunction {
    void update(DAGStatus status, Map<String, Progress> vertexProgressMap);
  }

  private abstract static class BaseUpdateFunction implements UpdateFunction {
    private static final int PRINT_INTERVAL = 3000;

    final TezJobMonitor monitor;
    private final PerfLogger perfLogger;

    private long lastPrintTime = 0L;
    private String lastReport = null;

    BaseUpdateFunction(TezJobMonitor monitor) {
      this.monitor = monitor;
      perfLogger = SessionState.getPerfLogger();
    }

    @Override
    public void update(DAGStatus status, Map<String, Progress> vertexProgressMap) {
      renderProgress(monitor.progressMonitor(status, vertexProgressMap));
      String report = getReport(vertexProgressMap);
      if (showReport(report)) {
        renderReport(report);
        lastReport = report;
        lastPrintTime = System.currentTimeMillis();
      }
    }

    private boolean showReport(String report) {
      return !report.equals(lastReport)
          || System.currentTimeMillis() >= lastPrintTime + PRINT_INTERVAL;
    }

    private String getReport(Map<String, Progress> progressMap) {
      StringWriter reportBuffer = new StringWriter();

      SortedSet<String> keys = new TreeSet<String>(progressMap.keySet());
      for (String s : keys) {
        Progress progress = progressMap.get(s);
        final int complete = progress.getSucceededTaskCount();
        final int total = progress.getTotalTaskCount();
        final int running = progress.getRunningTaskCount();
        final int failed = progress.getFailedTaskAttemptCount();
        if (total <= 0) {
          reportBuffer.append(String.format("%s: -/-\t", s));
        } else {
          if (complete == total) {
          /*
           * We may have missed the start of the vertex due to the 3 seconds interval
           */
            if (!perfLogger.startTimeHasMethod(PerfLogger.TEZ_RUN_VERTEX + s)) {
              perfLogger.PerfLogBegin(TezJobMonitor.CLASS_NAME, PerfLogger.TEZ_RUN_VERTEX + s);
            }

            perfLogger.PerfLogEnd(TezJobMonitor.CLASS_NAME, PerfLogger.TEZ_RUN_VERTEX + s);
          }
          if (complete < total && (complete > 0 || running > 0 || failed > 0)) {

            if (!perfLogger.startTimeHasMethod(PerfLogger.TEZ_RUN_VERTEX + s)) {
              perfLogger.PerfLogBegin(TezJobMonitor.CLASS_NAME, PerfLogger.TEZ_RUN_VERTEX + s);
            }

          /* vertex is started, but not complete */
            if (failed > 0) {
              reportBuffer.append(
                  String.format("%s: %d(+%d,-%d)/%d\t", s, complete, running, failed, total));
            } else {
              reportBuffer.append(String.format("%s: %d(+%d)/%d\t", s, complete, running, total));
            }
          } else {
          /* vertex is waiting for input/slots or complete */
            if (failed > 0) {
            /* tasks finished but some failed */
              reportBuffer.append(String.format("%s: %d(-%d)/%d\t", s, complete, failed, total));
            } else {
              reportBuffer.append(String.format("%s: %d/%d\t", s, complete, total));
            }
          }
        }
      }

      return reportBuffer.toString();
    }

    abstract void renderProgress(ProgressMonitor progressMonitor);

    abstract void renderReport(String report);
  }

  /**
   * this adds the required progress update to the session state that is used by HS2 to send the
   * same information to beeline client when requested.
   */
  static class LogToFileFunction extends BaseUpdateFunction {

    LogToFileFunction(TezJobMonitor monitor) {
      super(monitor);
    }

    @Override
    public void renderProgress(ProgressMonitor progressMonitor) {
      SessionState.get().updateProgressMonitor(progressMonitor);
    }

    @Override
    public void renderReport(String report) {
      monitor.console.printInfo(report);
    }
  }

  /**
   * This used when we want the progress update to printed in the same process typically used via
   * hive-cli mode.
   */
  static class InPlaceUpdateFunction extends BaseUpdateFunction {
    /**
     * Have to use the same instance to render else the number lines printed earlier is lost and the
     * screen will print the table again and again.
     */
    private final InPlaceUpdate inPlaceUpdate;

    InPlaceUpdateFunction(TezJobMonitor monitor) {
      super(monitor);
      inPlaceUpdate = new InPlaceUpdate(SessionState.LogHelper.getInfoStream());
    }

    @Override
    public void renderProgress(ProgressMonitor progressMonitor) {
      inPlaceUpdate.render(progressMonitor);
    }

    @Override
    public void renderReport(String report) {
      monitor.console.logInfo(report);
    }
  }
}