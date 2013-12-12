package org.osgi.util.promise;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A Resolver is the manager of a Promise. The Promise is used by the clients
 * but the code that actually does the async work must use the resolver to
 * resolve the promise with a value or an error.
 * 
 * @param <T>
 *            The associated value type
 */
public class Resolver<T> {
	Object lock = new Object();
	boolean resolved;
	T value;
	Throwable error;
	ConcurrentLinkedDeque<Runnable> onresolve = new ConcurrentLinkedDeque<>();

	/**
	 * Create the associated Promise with this resolver. Each resolver has only
	 * one promise.
	 */
	final Promise<T> promise = new Promise<T>() {

		/**
		 * Wait for the result and then return it.
		 */
		@Override
		public T get() throws Exception {
			synchronized (lock) {
				while (!resolved) {
					System.out.println("wait for get");
					lock.wait();
				}

				System.out.println("get is ready = " + value);
				if (error != null)
					throw new InvocationTargetException(error);
				else
					return value;
			}
		}

		/**
		 * Answer if this is promise done.
		 */
		@Override
		public boolean isDone() {
			synchronized (lock) {
				return resolved;
			}
		}

		/**
		 * The chain call. Success and fail will be called depending how this
		 * promise is resolved.
		 */
		@Override
		public <R> Promise<R> then(final Success<R, T> ok, final Failure<T> fail)
				throws Exception {

			final Resolver<R> nextStage = new Resolver<R>();

			onresolve(new Runnable() {
				@Override
				public void run() {
					stage(nextStage, ok, fail);
				}
			});
			dequeue();
			return nextStage.getPromise();
		}

		@Override
		public <R> Promise<R> then(Success<R, T> success) throws Exception {
			return then(success, null);
		}

		/**
		 * Callback when this promise is resolved.
		 */
		@Override
		public void onresolve(Runnable done) throws Exception {
			synchronized (lock) {
				onresolve.add(done);
			}
			dequeue();
		}

		/**
		 * Wait and return the error.
		 */
		@Override
		public Throwable getError() throws InterruptedException {
			synchronized (lock) {
				while (!resolved)
					lock.wait();
			}
			return error;
		}

		/**
		 * Called when this promise is resolved either with an error or a value.
		 * We need to callback our associated success and failure callbacks and
		 * chain the results.
		 * 
		 * @param nextStage
		 *            The next stage to resolve
		 * @param ok
		 *            the success callback
		 * @param fail
		 *            the fail callback
		 */

		private <R> void stage(final Resolver<R> nextStage,
				final Success<R, T> ok, final Failure<T> fail) {
			try {
				if (error == null)
					success(nextStage, ok);
				else
					fail(nextStage, fail);

			} catch (Throwable e) {
				e.printStackTrace();
			}
		}

		/**
		 * If we fail then we fail our promise and the next stage.
		 * 
		 * @param nextStage
		 *            next stage to fail
		 * @param fail
		 *            the callback for this promise
		 * @throws Exception
		 */
		private <R> void fail(final Resolver<R> nextStage, final Failure<T> fail)
				throws Exception {
			if (fail != null)
				fail.fail(this);
			nextStage.fail(error);
		}

		/**
		 * If we succeed then we call the ok callback and use the returned
		 * promise to add a callback that when called will resolve the next
		 * stage.
		 * 
		 * @param nextStage
		 * @param ok
		 * @throws Exception
		 */
		private <R> void success(final Resolver<R> nextStage,
				final Success<R, T> ok) throws Exception {
			try {
				final Promise<R> nextResult = ok.call(this);
				if (nextResult == null) {

					//
					// We directly resolve it if we have no
					// promise
					//

					nextStage.resolve(null);

				} else {

					//
					// We have to wait for the next result
					// to become available so we register
					// a callback
					//

					nextResult.onresolve(new Runnable() {
						public void run() {
							try {
								R value = nextResult.get();
								nextStage.resolve(value);
							} catch (Exception e) {
								nextStage.fail(e);
							}
						}
					});
				}
			} catch (Exception e) {
				// TODO should we call fail??
				nextStage.fail(e);
			}
		}
	};

	/**
	 * Private constructor to directly resolve the promise. This is handy for
	 * cases where you do not need a promise.
	 * 
	 * @param directValue
	 *            the direct value
	 */
	private Resolver(T directValue) {
		this.value = directValue;
		this.resolved = true;
	}

	/**
	 * Default resolver.
	 */
	public Resolver() {
	}

	/**
	 * Return the promise associated with this resolver.
	 * 
	 * @return
	 */
	public Promise<T> getPromise() {
		return promise;
	}

	/**
	 * Resolve this promise with a value.
	 * 
	 * @param value
	 *            the value to resolve with.
	 */
	public void resolve(T value) {
		done(value, null);
	}

	/**
	 * Resolve the promise with a failure.
	 * 
	 * @param t
	 *            the failure
	 */
	public void fail(Throwable t) {
		done(null, t);
	}

	/**
	 * The private method that does the heavy lifting. It atomically sets the
	 * value/error and resolved state. It will also dequeue any callbacks.
	 * 
	 * @param value
	 * @param error
	 */
	private void done(T value, Throwable error) {
		System.out.println("resolved " + value + " " + error);
		synchronized (lock) {
			if (resolved)
				throw new IllegalStateException("Already resolved " + this);
			this.value = value;
			this.error = error;
			resolved = true;
			lock.notifyAll();
		}
		dequeue();
	}

	/**
	 * Dequeue any callbacks if resolved. The access to the resolved 
	 * variable is not synchronized because it does not matter. This method
	 * is called after all the methods that change the list of callback
	 * and the resolve state. It is therefore impossible (in theory) to miss
	 * dequeueing
	 */
	private void dequeue() {
		if (!resolved)
			return;

		Runnable r;
		while ((r = onresolve.pollFirst()) != null) {
			try {
				r.run();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Convenience to create a resolved Promise with an immediate value
	 * @param value
	 * @return
	 */
	public static <T> Promise<T> getDirectPromise(T value) {
		return new Resolver<T>(value).getPromise();
	}
}
