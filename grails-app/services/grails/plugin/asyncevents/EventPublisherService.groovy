package grails.plugin.asyncevents

import com.energizedwork.grails.plugin.asyncevents.RetryableNotification
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.ApplicationEvent
import org.springframework.util.ErrorHandler
import java.util.concurrent.*
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

class EventPublisherService implements InitializingBean, DisposableBean, ApplicationContextAware {

	static transactional = false

	ErrorHandler errorHandler
	ApplicationContext applicationContext

	private final Collection<AsyncEventListener> listeners = []
	private final ExecutorService executor = Executors.newSingleThreadExecutor()
	private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor()
	private final BlockingQueue<RetryableNotification> queue = new LinkedBlockingQueue<RetryableNotification>()
	private boolean done = false

	void addListener(AsyncEventListener listener) {
		listeners << listener
	}

	void publishEvent(ApplicationEvent event) {
		listeners.each { AsyncEventListener listener ->
			queue.put(new RetryableNotification(listener, event))
		}
	}

	void afterPropertiesSet() {
		autowireListeners()
		startPollingForEvents()
	}

	void destroy() {
		done = true
		shutdown(executor, 1, SECONDS)
		shutdown(retryExecutor, 1, SECONDS)
	}

	private void startPollingForEvents() {
		executor.execute {
			while (!done) {
				log.debug "polling queue..."
				RetryableNotification event = queue.poll(250, MILLISECONDS)
				if (event) {
					log.info "got event $event from queue"
					notifyListener(event)
				}
			}
			log.warn "event notifier thread exiting..."
		}
	}

	private void notifyListener(RetryableNotification event) {
		try {
			def success = event.tryNotifyingListener()
			if (!success) {
				rescheduleNotification(event)
			}
		} catch (Exception e) {
			log.error "Notififying listener $event.target failed", e
			errorHandler?.handleError(e)
		}
	}

	private void rescheduleNotification(RetryableNotification event) {
		log.warn "Notifying listener $event.target failed"
		if (event.shouldRetry()) {
			long retryDelay = event.retryDelayMillis
			log.warn "Will retry in $retryDelay $MILLISECONDS"
			event.incrementRetryCount()
			retryExecutor.schedule(this.&notifyListener.curry(event), retryDelay, MILLISECONDS)
		} else {
			throw new RetriedTooManyTimesException(event)
		}
	}

	private void autowireListeners() {
		if (!applicationContext) return
		applicationContext.getBeansOfType(AsyncEventListener).each { String name, AsyncEventListener listener ->
			log.debug "Autowiring listener $name"
			listeners << listener
		}
	}

	private void shutdown(ExecutorService executor, int timeout, TimeUnit unit) {
		executor.shutdown()
		if (!executor.awaitTermination(timeout, unit)) {
			log.warn "Executor still alive $timeout $unit after shutdown, forcing..."
			executor.shutdownNow()
			assert executor.awaitTermination(timeout, unit), "Forced shutdown of executor incomplete after $timeout $unit."
		}
	}
}
