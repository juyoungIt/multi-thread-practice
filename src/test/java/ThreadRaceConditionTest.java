import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

public class ThreadRaceConditionTest {

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
                // start에서 end까지의 모든 수에 포함된 1의 갯수를 구하는 과정
                for (int i=start ; i<=end; i++) {
                    int tmp = i;
                    while (tmp / 10 > 0) {
                        if (tmp % 10 == 1) {
                            oneCount++; // 여러 스레드가 이 부분을 동시에 수행하므로 경합발생(1)
                        }
                        tmp /= 10;
                    }
                    if (tmp % 10 == 1) {
                        oneCount++; // 여러 스레드가 이 부분을 동시에 수행하므로 경합발생(2)
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    long oneCount = 0L; // 동기화를 제공하지 않는 원시타입으로 변수를 사용
    int range = 1_000_000_000;
    Task singleTask; Thread singleThread;   // 싱글 스레드(Task, Thread)
    Task[] multiTask; Thread[] multiThread; // 멀티 스레드(Task, Thread)
    CountDownLatch startGate;
    int threadCount = 10; // 멀티 스레드 적용 시 사용할 스레드의 수

    @BeforeEach
    void init() {
        // latch 초기화 - 멀티 스레드 실행환경에서 모든 스레드를 동시에 실행하기 위함
        startGate = new CountDownLatch(1);

        oneCount = 0L;
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
    @DisplayName("싱글스레드 - 하나의 스레드가 하나의 자원에 접근하므로 race condition 이 발생하지 않는다")
    void countBySingleThread() throws InterruptedException {
        singleThread.start();
        startGate.countDown();
        singleThread.join();
        assertThat(oneCount).isEqualTo(900_000_001);
    }

    @Test
    @DisplayName("멀티스레드 - 여러 스레드가 하나의 공유 자원에 접근하므로 race condition 이 발생한다")
    void countByMultiThread() throws InterruptedException {
        for (int i=0 ; i<threadCount ; i++) multiThread[i].start();
        startGate.countDown(); // 모든 스레드를 동시에 실행
        for (int i=0 ; i<threadCount ; i++) multiThread[i].join();
        // race condition 의 발생으로 기대와 다른 결과값을 얻게 됨 -> 해당 검증이 실패함
        assertThat(oneCount).isEqualTo(900_000_001);
    }

}
