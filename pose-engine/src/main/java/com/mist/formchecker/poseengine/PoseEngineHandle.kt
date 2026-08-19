package com.mist.formchecker.poseengine

/**
 * [PoseEngine]의 수명을 관리하는 핸들.
 *
 * ## 왜 필요한가 (실제 크래시로 확인됨)
 * 추론은 CameraX 분석 스레드에서 돌고, 엔진 교체·해제는 다른 스레드에서 일어난다.
 * 엔진을 그냥 `close()`하면 **분석 스레드가 아직 추론 중인 네이티브 핸들을 해제**해
 * use-after-free로 프로세스가 죽는다 (SIGSEGV in `CompiledModel.nativeRun`).
 *
 * `PoseAnalyzer`가 엔진 참조를 직접 들고 있어서, ViewModel에서 참조를 끊어도 이미
 * 생성된 분석기는 계속 그 엔진을 호출한다. CameraX가 분석기를 교체하는 시점을
 * 호출부가 알 수 없으므로, **해제 자체를 안전하게 만드는 쪽**이 맞다.
 *
 * [use]와 [close]가 같은 락을 공유하므로:
 * - 추론 중에 [close]가 호출되면 추론이 끝날 때까지 대기한 뒤 해제한다
 * - 해제된 뒤의 [use]는 엔진을 건드리지 않고 null을 돌려준다
 *
 * 대기 시간은 한 프레임 추론(수십 ms)이라 화면 전환 체감에 영향이 없다.
 */
class PoseEngineHandle(private val engine: PoseEngine) {

    private val lock = Any()
    private var closed = false

    val modelName: String get() = engine.modelName
    val activeDelegate: Delegate get() = engine.activeDelegate
    val modelLoadTimeNanos: Long get() = engine.modelLoadTimeNanos

    /**
     * 엔진을 안전하게 사용한다.
     *
     * @return [block]의 결과. 이미 해제된 핸들이면 null.
     */
    fun <T> use(block: (PoseEngine) -> T): T? = synchronized(lock) {
        if (closed) null else block(engine)
    }

    /** 진행 중인 추론이 끝난 뒤 엔진을 해제한다. 여러 번 호출해도 안전하다. */
    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            engine.close()
        }
    }
}
