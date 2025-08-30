import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import static org.assertj.core.api.Assertions.*;

public class DeadLockTest {

    private static final Object resource1 = new Object();
    private static final Object resource2 = new Object();
    CountDownLatch startGate;

    @BeforeEach
    void init() {
        // 2개의 스레드를 서로 동시에 실행하기 위함
        startGate = new CountDownLatch(1);
    }

    @Test
    @DisplayName("Deadlock : 2개의 Thread가 서로의 자원을 기다리면서 Deadlock 이 발생한다")
    void deadlockTest() {
        // 실행시간이 3초가 넘어가는 경우 자동으로 종료하고 실패처리함
        // -> 간단한 출력이기 때문에 deadlock이 발생하지 않는 이상 실행시간이 3초가 넘어갈 수 없음
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {

            // 첫번째 Thread
            // -> resource1을 먼저 획득한 뒤, resource2 획득을 시도함
            Thread thread1 = new Thread(() -> {
                synchronized (resource1) {
                    System.out.println("Thread1: resource1 획득");
                    try {
                        startGate.await(); // resource1을 획득한 후, 다른 스레드가 resource2를 획득할 때까지 대기
                        System.out.println("Thread1: resource2 획득시도...");
                        synchronized (resource2) {
                            System.out.println("Thread1: resource2 획득");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "Thread1");

            // 두번째 Thread
            // -> resource2을 먼저 획득한 뒤, resource1 획득을 시도함
            Thread thread2 = new Thread(() -> {
                synchronized (resource2) {
                    System.out.println("Thread2: resource2 획득");
                    try {
                        startGate.await(); // resource2를 획득한 후, 다른 스레드가 resource1을 획득할 때까지 대기
                        System.out.println("Thread2: resource1 획득시도...");
                        synchronized (resource1) {
                            System.out.println("Thread2: resource1 획득");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "Thread2");

            thread1.start();
            thread2.start();
            startGate.countDown(); // 2개의 스레드를 동시에 실행

            // 최대 1초 동안 DeadlockProbe로 교착 탐지 폴링
            List<String> deadlockedNames = checkDeadlock(1000);
            // 데드락에 걸린 스레드들을 검증
            assertThat(deadlockedNames).containsExactlyInAnyOrder("Thread1", "Thread2");
        });
    }

    @Test
    @DisplayName("Deadlock 해결1: (예방) 자원에 번호를 붙이고 일관된 방향으로 할당하여 원형 대기(Circular Wait)을 제거")
    void resolveDeadlockByRemoveCircularWaitTest() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {

            // 첫번째 Thread -> resource1을 먼저 획득한 뒤, resource2 획득을 시도함
            Thread thread1 = new Thread(() -> {
                synchronized (resource1) {
                    System.out.println("Thread1: resource1 획득");
                    try {
                        startGate.await();
                        System.out.println("Thread1: resource2 획득시도...");
                        synchronized (resource2) {
                            System.out.println("Thread1: resource2 획득");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "Thread1");

            // 두번째 Thread -> resource1을 먼저 획득한 뒤, resource2 획득을 시도함
            Thread thread2 = new Thread(() -> {
                synchronized (resource1) {
                    System.out.println("Thread2: resource1 획득");
                    try {
                        startGate.await();
                        System.out.println("Thread2: resource2 획득시도...");
                        synchronized (resource2) {
                            System.out.println("Thread2: resource2 획득");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "Thread2");

            thread1.start();
            thread2.start();
            startGate.countDown(); // 2개의 스레드를 동시에 실행

            // 최대 1초 동안 DeadlockProbe로 교착 탐지 폴링
            List<String> deadlockedNames = checkDeadlock(1000);
            // Deadlock이 발생하지 않는다
            assertThat(deadlockedNames).isEmpty();
        });
    }

    /**
     * 데드락 여부를 감지하여, Deadlock이 발생한 Thread의 이름을 List 형태로 반환
     * @param timeoutMillis : 해당 시간동안 Polling 을 통해 Deadlock 발생여부를 감지
     */
    public static List<String> checkDeadlock(long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads(); // deadlock 미검출 시 null 반환
            if (ids != null) {
                // 현재 실행 상태인 Thread ID, 이름 정보를 수집하여 Map으로 구성
                Map<Long, String> threadNameMap = new HashMap<>();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    threadNameMap.put(t.getId(), t.getName());
                }
                // 현재 Deadlock 상태에 빠진 Thread만 이름을 조회하여 List 로 구성
                List<String> names = new ArrayList<>();
                for (long id : ids) {
                    String name = threadNameMap.get(id);
                    if (name != null) names.add(name);
                }
                return names;
            }
            Thread.sleep(10); // 10ms 대기 후 해당 과정을 다시 반복
        }
        return Collections.emptyList(); // deadlock이 감지되지 않은 경우 빈 list 를 반환
    }

}
