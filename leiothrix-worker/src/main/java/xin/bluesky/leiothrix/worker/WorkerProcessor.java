package xin.bluesky.leiothrix.worker;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bluesky.leiothrix.common.jdbc.JdbcTemplate;
import xin.bluesky.leiothrix.worker.action.ExecutorsPool;
import xin.bluesky.leiothrix.worker.action.ProcessorAnnouncer;
import xin.bluesky.leiothrix.worker.action.TaskExecutor;
import xin.bluesky.leiothrix.worker.background.WorkerProgressReporter;
import xin.bluesky.leiothrix.worker.background.ShutdownHook;
import xin.bluesky.leiothrix.worker.client.ServerChannel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static xin.bluesky.leiothrix.common.util.StringUtils2.COMMA;
import static xin.bluesky.leiothrix.worker.WorkerProcessor.Status.*;

/**
 * @author 张轲
 */
public class WorkerProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WorkerProcessor.class);

    private static WorkerProcessor processor;

    private ExecutorsPool executorsPool;

    private WorkerProgressReporter progressReporter;

    private volatile Status status;

    private ReentrantLock lock = new ReentrantLock();

    private CountDownLatch countDownLatch;

    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.error("线程[id={},name={}]出现异常:{}", t.getId(), t.getName(), ExceptionUtils.getStackTrace(e));
        });
    }

    public WorkerProcessor(WorkerConfiguration configuration) {

        synchronized (WorkerProcessor.class) {
            if (processor != null) {
                throw new RuntimeException("已经创建了一个worker进程,不能重复创建");
            }
            processor = this;
        }

        initSettings(configuration);

        this.executorsPool = new ExecutorsPool();

        this.countDownLatch = new CountDownLatch(executorsPool.getPoolSize());

        this.progressReporter = new WorkerProgressReporter();

        this.status = NOT_STARTED;
    }

    private void initSettings(WorkerConfiguration configuration) {
        Settings.setConfiguration(configuration);

        String serverIpConfig = System.getProperty("server.ip");
        Preconditions.checkNotNull(serverIpConfig, "需要配置server地址");
        Settings.setServersIp(serverIpConfig.split(COMMA));

        Settings.setServerPort(Integer.parseInt(System.getProperty("server.port")));

        Settings.setWorkerIp(System.getProperty("worker.ip"));

        Settings.setTaskId(System.getProperty("taskId"));

        Settings.setRangePageSize(Integer.parseInt(System.getProperty("worker.range.pagesize")));

        Settings.setThreadNumFactor(Integer.parseInt(System.getProperty("worker.processor.threadnum.factor")));
    }

    public void start() throws Exception {
        lock.lock();
        if (status != NOT_STARTED) {
            lock.unlock();
            throw new Exception("worker进程已经启动/或已关闭,不能再次启动");
        }

        logger.info("worker进程开始启动");
        status = RUNNING;
        lock.unlock();

        try {
            ServerChannel.connect(Settings.getServersIp(), Settings.getServerPort());

            ProcessorAnnouncer.increaseProcessorNumber();

            Runtime.getRuntime().addShutdownHook(new ShutdownHook());

            submitExecutor();

            progressReporter.start();

            awaitTermination();
        } catch (Exception e) {
            logger.error("worker启动过程中出现异常:{}", getStackTrace(e));
            throw e;
        } finally {
            shutdown();
        }
    }

    private void submitExecutor() {
        for (int i = 0; i < executorsPool.getPoolSize(); i++) {
            executorsPool.submit(new TaskExecutor(progressReporter, countDownLatch));
        }
    }

    public void awaitTermination() throws InterruptedException {
        countDownLatch.await();
        logger.info("所有工作线程都已结束");
    }

    public boolean isRunning() {
        return status == RUNNING;
    }

    public void shutdown() throws Exception {
        lock.lock();
        if (!isRunning()) {
            lock.unlock();
            return;
        }
        status = SHUTDOWN;
        lock.unlock();

        logger.info("开始退出worker进程");

        status = SHUTDOWN;

        executorsPool.shutdown();

        progressReporter.shutdown();

        ProcessorAnnouncer.decreaseProcessorNumber();

        JdbcTemplate.destroy();

        Thread.sleep(3 * 1000);

        ServerChannel.shutdown();

        logger.info("worker进程成功退出");
//        System.exit(0);
    }

    public Status getStatus() {
        return status;
    }

    public enum Status {
        NOT_STARTED, RUNNING, SHUTDOWN
    }

    public static WorkerProcessor getProcessor() {
        return processor;
    }

}
