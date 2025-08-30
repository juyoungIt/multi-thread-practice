import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

public class ThreadPerformanceTest {

    class Task implements Runnable {
        private final int start;
        private final int end;

        public Task(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public void run() {
            try {
                // 허용할 때까지 실행을 보류 (모든 스레드를 동시에 실행하기 위함)
                startGate.await();
                int localCount = 0; // 스레드가 할당받은 범위의 수에서 부분적으로 카운트한 1의 갯수
                // start에서 end까지의 모든 수에 포함된 1의 갯수를 구하는 과정
                for (int i=start ; i<=end; i++) {
                    int tmp = i;
                    while (tmp / 10 > 0) {
                        if (tmp % 10 == 1) localCount++;
                        tmp /= 10;
                    }
                    if (tmp % 10 == 1) localCount++;
                }
                oneCount.addAndGet(localCount); // 동기화 기능을 제공하는 AtomicInteger 타입 변수를 갱신
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    AtomicInteger oneCount = new AtomicInteger(0);
    int range = 1_000_000_000;
    Task singleTask;
    Task[] multiTask;
    Thread singleThread;
    Thread[] multiThread;
    CountDownLatch startGate;
    int threadCount = 10;

    @BeforeEach
    void init() {
        // latch 초기화 - 멀티 스레드 실행환경에서 모든 스레드를 동시에 실행하기 위함
        startGate = new CountDownLatch(1);

        oneCount = new AtomicInteger(0);
        // 싱글스레드 초기화
        singleTask = new Task(1, range);
        singleThread = new Thread(singleTask);

        // 멀티스레드 초기화 - 스레드 수 만큼 범위를 분할하여 Task 생성
        multiTask = new Task[threadCount];
        for (int i=0 ; i<threadCount ; i++) {
            multiTask[i] = new Task(
                    (int) (((long) i * range / threadCount) + 1),
                    (int) (((long) i * range / threadCount) + range / threadCount)
            );
        }
        multiThread = new Thread[threadCount];
        for (int i=0 ; i<threadCount ; i++) {
            multiThread[i] = new Thread(multiTask[i]);
        }
    }

    @Test
    @DisplayName("싱글스레드: 1 ~ 1,000,000,000까지의 모든 수에서 등장하는 1의 갯수를 셈")
    void countBySingleThread() throws InterruptedException {
        singleThread.start();
        startGate.countDown();
        singleThread.join();
        assertThat(oneCount.intValue()).isEqualTo(900_000_001);
    }

    @Test
    @DisplayName("멀티스레드: 1 ~ 1,000,000,000까지의 모든 수에서 등장하는 1의 갯수를 셈")
    void countByMultiThread() throws InterruptedException {
        for (int i=0 ; i<threadCount ; i++) multiThread[i].start();
        startGate.countDown(); // 모든 스레드를 동시에 실행
        for (int i=0 ; i<threadCount ; i++) multiThread[i].join();
        assertThat(oneCount.intValue()).isEqualTo(900_000_001);
    }

}
