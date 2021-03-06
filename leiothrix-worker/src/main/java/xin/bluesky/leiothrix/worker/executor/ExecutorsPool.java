package xin.bluesky.leiothrix.worker.executor;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bluesky.leiothrix.worker.conf.Settings;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static java.math.BigDecimal.ROUND_HALF_DOWN;

/**
 * @author 张轲
 *         worker.processor.threadnum.factor
 */
public class ExecutorsPool {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorsPool.class);

    private ThreadPoolExecutor executors;

    private List<TaskExecutor> reference = new ArrayList();

    public ExecutorsPool() {
        int executorNumber = calExecutorsNumbers();
        this.executors = (ThreadPoolExecutor) Executors.newFixedThreadPool(executorNumber,
                new ThreadFactoryBuilder().setNameFormat("partition-task-runner-%d").build());

        logger.info("创建工作线程池,线程数量为:{}", executorNumber);
    }

    protected int calExecutorsNumbers() {
        // 得到物理机的cpu processor数和可用内存
        int cpuNumbers = Runtime.getRuntime().availableProcessors();
        long freeMemory = Runtime.getRuntime().freeMemory();

        // 用物理机的可用内存除以分配给单个worker进程的内存,以得到最大worker进程数
        int workerHeapSize = (int) ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() >> 20;//以M为单位
        int maxWorkerProcessNum = (int) (freeMemory >> 20) / workerHeapSize;
        if (maxWorkerProcessNum == 0) {
            maxWorkerProcessNum = 1;
        }

        // 用cpu processor数除以最大worker进程数,得到大致每个worker进程占的cpu.保留一位精度并舍弃多余位数
        BigDecimal roughCpuNumbersForMe = new BigDecimal(cpuNumbers / maxWorkerProcessNum).setScale(1, ROUND_HALF_DOWN);

        // 每个cpu processor*单CPU使用的线程数因子,向下取整
        return roughCpuNumbersForMe.multiply(new BigDecimal(Settings.getThreadNumFactor())).intValue();
    }

    public void submit(TaskExecutor taskExecutor) {
        reference.add(taskExecutor);
        executors.submit(taskExecutor);
    }

    public int getPoolSize() {
        return executors.getCorePoolSize();
    }

    public int getRemainingExecutorSize() {
        for (Iterator<TaskExecutor> iterator = reference.iterator(); iterator.hasNext(); ) {
            TaskExecutor te = iterator.next();
            if (te.isFree()) {
                iterator.remove();
            }
        }
        return reference.size();
    }

    public void rescheduleExecutor(int num) {
        if (num > reference.size()) {
            throw new IllegalArgumentException("stop数量不能超过任务的执行线程数");
        }

        for (int i = 0; i < num; i++) {
            TaskExecutor te = reference.get(0);
            te.reschedule();
            reference.remove(te);
        }
    }

    public void shutdown() {
        executors.shutdown();
        waitTerminated();
        logger.info("成功关闭工作线程池");
    }

    private void waitTerminated() {
        while (!executors.isTerminated()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error("工作线程池线程在关闭的时候被中断");
            }
        }
    }
}
