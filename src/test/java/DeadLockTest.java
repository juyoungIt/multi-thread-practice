import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import static org.assertj.core.api.Assertions.*;

public class DeadLockTest {

    // synchronized block 에서 사용하기 위한 동기화 대상 (deadlock 예시에서 점유할 자원을 의미)
    final Object resource1 = new Object();
    final Object resource2 = new Object();
    // synchronized 보다 lock을 더 정교하게 제어하기 위해 선언 (deadlock 예시에서 점유할 자원을 의미)
    final ReentrantLock resource3 = new ReentrantLock();
    final ReentrantLock resource4 = new ReentrantLock();
    // 2개의 Thread 를 서로 동시에 실행하기 위함
    CountDownLatch startGate;

    @BeforeEach
    void init() {
        startGate = new CountDownLatch(1);
    }

    @Test
    @DisplayName("Deadlock : 2개의 Thread가 서로의 자원을 기다리면서 Deadlock 이 발생한다")
    void deadlockTest() throws InterruptedException {
        // 첫번째 Thread -> resource1을 먼저 획득한 뒤, resource2 획득을 시도함
        Thread thread1 = new Thread(() -> {
            synchronized (resource1) {
                System.out.println("Thread1: resource1 획득");
                try {
                    startGate.await(); // resource1을 획득한 후, 다른 스레드가 resource2를 획득할 때까지 대기
                    System.out.println("Thread1: resource2 획득시도...");
                    synchronized (resource2) {
                        System.out.println("Thread1: resource2 획득");
                        System.out.println("Thread1: 작업완료");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, "Thread1");

        // 두번째 Thread -> resource2을 먼저 획득한 뒤, resource1 획득을 시도함
        Thread thread2 = new Thread(() -> {
            synchronized (resource2) {
                System.out.println("Thread2: resource2 획득");
                try {
                    startGate.await(); // resource2를 획득한 후, 다른 스레드가 resource1을 획득할 때까지 대기
                    System.out.println("Thread2: resource1 획득시도...");
                    synchronized (resource1) {
                        System.out.println("Thread2: resource1 획득");
                        System.out.println("Thread2: 작업완료");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, "Thread2");

        thread1.start();
        thread2.start();
        startGate.countDown(); // 2개의 스레드를 동시에 실행
        // thread1.join(); // 의도적으로 deadlock을 만드는 상황이므로 주석처리(1)
        // thread2.join(); // 의도적으로 deadlock을 만드는 상황이므로 주석처리(2)

        // 최대 1초 동안 Deadlock 상태 조사 Polling
        Set<Long> threadIds = new HashSet<>();
        threadIds.add(thread1.getId());
        threadIds.add(thread2.getId());
        List<String> deadlockedNames = checkDeadlock(1000, threadIds);
        // Deadlock에 걸린 스레드들을 검증
        assertThat(deadlockedNames).containsExactlyInAnyOrder("Thread1", "Thread2");
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
                            System.out.println("Thread1: 작업완료");
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
                            System.out.println("Thread2: 작업완료");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, "Thread2");

            thread1.start();
            thread2.start();
            startGate.countDown(); // 2개의 스레드를 동시에 실행
            thread1.join();
            thread2.join();

            // 최대 1초 동안 Deadlock 상태 조사 Polling
            Set<Long> threadIds = new HashSet<>();
            threadIds.add(thread1.getId());
            threadIds.add(thread2.getId());
            List<String> deadlockedNames = checkDeadlock(1000, threadIds);
            // Deadlock이 발생하지 않는다
            assertThat(deadlockedNames).isEmpty();
        });
    }

    @Test
    @DisplayName("Deadlock 해결2: (예방) 비선점(Non-preemption)을 제거")
    void resolveDeadlockByRemoveNonPreemptionTest() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {

            Thread thread1 = new Thread(() -> {
                try {
                    startGate.await();
                    tryRun(resource3, resource4, "Thread1");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }, "Thread1");

            Thread thread2 = new Thread(() -> {
                try {
                    startGate.await();
                    tryRun(resource4, resource3, "Thread2");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }, "Thread2");

            thread1.start();
            thread2.start();
            startGate.countDown(); // 동시에 시작

            thread1.join();
            thread2.join();

            // 최대 1초 동안 Deadlock 상태 조사 Polling
            Set<Long> threadIds = new HashSet<>();
            threadIds.add(thread1.getId());
            threadIds.add(thread2.getId());
            List<String> deadlockedNames = checkDeadlock(1000, threadIds);
            // Deadlock이 발생하지 않는다
            assertThat(deadlockedNames).isEmpty();
        });
    }

    /**
     * 데드락 여부를 감지하여, Deadlock이 발생한 Thread의 이름을 List 형태로 반환
     * @param timeoutMillis : 해당 시간동안 Polling 을 통해 Deadlock 발생여부를 감지
     */
    public static List<String> checkDeadlock(long timeoutMillis, Set<Long> threadIds) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < end) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads(); // deadlock 미검출 시 null 반환
            if (ids != null) {
                // 현재 실행 상태인 Thread ID, 이름 정보를 수집하여 Map으로 구성
                Map<Long, String> threadNameMap = new HashMap<>();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (!threadIds.contains(t.getId())) continue;
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

    /**
     * 점유해야할 2개의 자원을 인자로 받아 자원 상태에 따라 자원을 가져오거나 다시 반환함
     * @param first : 점유해야할 첫번째 자원
     * @param second : 점유해야할 두번째 자원
     * @param threadName : 실행을 시도하는 Thread 이름
     */
    private void tryRun(ReentrantLock first, ReentrantLock second, String threadName) {
        Random random = new Random();
        while (true) {
            // 첫번째 자원에 대한 lock을 획득함
            first.lock();
            System.out.printf("%s: %s 획득\n", threadName, getResourceName(first));
            try {
                // 2개의 Thread가 서로 자원을 양보하는 상황을 완화하기 위해 random 값 사용
                int tryLockTime = 100 + random.nextInt(100);
                if (second.tryLock(tryLockTime, TimeUnit.MILLISECONDS)) {
                    try {
                        System.out.printf("%s: %s 획득\n", threadName, getResourceName(second));
                        System.out.printf("%s: 작업완료\n", threadName);
                        return; // 성공했으니 종료 (점유한 2개의 자원을 모두 반환) - finally 블록
                    } finally {
                        second.unlock();
                        System.out.printf("%s: %s 반납\n", threadName, getResourceName(second));
                    }
                } else {
                    // 대기시간 동안 자원의 lock을 획득하지 못한 경우 다른 스레드를 위해 보유한 first 자원을 반납 - finally 블록
                    System.out.printf(
                            "%s: %s 대기시간 초과 -> %s 반납 후 재시도\n",
                            threadName,
                            getResourceName(second),
                            getResourceName(first)
                    );
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                // 성공/실패와 무관하게 항상 first를 해제
                if (first.isHeldByCurrentThread()) {
                    first.unlock(); // 획득했던 자원을 반납함
                    System.out.printf("%s: %s 반납\n", threadName, getResourceName(first));
                }
            }
            // Thread 간의 경합이 과도하게 발생하는 것을 막기 위해 의도적으로 실행흐름을 지연
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String getResourceName(ReentrantLock lock) {
        return (lock == resource3) ? "resource3" : "resource4";
    }

}
