package com.pab.ficc.ibp.modelgate.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实例生命周期状态，供容器平台探测端点读取。
 *
 * <p>只有两个 volatile 标志，<b>不做任何 IO</b>：探测请求是高频的（平台通常几秒一次），
 * 且它的判断结果直接决定容器是否被重启、是否被摘流量，必须又快又稳。
 *
 * <p><b>两个状态刻意分开</b>，对应容器平台的两类探测：
 * <ul>
 *   <li>{@code ready}：启动是否完成。{@link ApplicationReadyEvent} 之后为 true。
 *       在此之前流量打进来只会得到一堆失败，必须先说"我还没好"。</li>
 *   <li>{@code draining}：是否已进入优雅退出流程。平台调用退出端点后置位，
 *       此后流量检查返回 503，平台据此把本实例从负载均衡摘掉；
 *       已在处理中的请求继续跑完（LLM 请求动辄几十秒到几分钟，切不得）。</li>
 * </ul>
 *
 * <p><b>存活检查不看这两个标志</b>——理由见 {@code ContainerDetectionController}。
 */
@Slf4j
@Component
public class ServiceState {

    /** 启动是否完成（ApplicationReadyEvent 之后为 true） */
    private volatile boolean ready = false;

    /** 是否已进入优雅退出流程；用 AtomicBoolean 保证退出动作只触发一次 */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = true;
        log.info("[ServiceState] 启动完成，开始接受流量（流量检查将返回 200）");
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isDraining() {
        return draining.get();
    }

    /** 可以接流量 = 启动完成 且 未在退出流程中 */
    public boolean isAvailable() {
        return ready && !draining.get();
    }

    /**
     * 置为退出中。
     *
     * @return true 表示本次调用完成了状态翻转；false 表示此前已经是退出中（平台重复调用）。
     *         区分这两者是为了让"退出时该做的收尾动作"只执行一次，
     *         同时保证端点本身<b>幂等</b>——重复调用一律回 200，不能因为重复调用就报错，
     *         否则平台会误判退出失败。
     */
    public boolean markDraining() {
        return draining.compareAndSet(false, true);
    }
}
