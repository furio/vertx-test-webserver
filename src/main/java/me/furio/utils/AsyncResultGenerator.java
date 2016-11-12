package me.furio.utils;

import io.vertx.core.AsyncResult;

/**
 * Created by furione on 05/11/16.
 */
public class AsyncResultGenerator {
    public static <T> AsyncResult<T> Generate(final Throwable error, final boolean hasFailed, final T result) {
        return new AsyncResult<T>() {
            @Override
            public T result() {
                return result;
            }

            @Override
            public Throwable cause() {
                return error;
            }

            @Override
            public boolean succeeded() {
                return !hasFailed;
            }

            @Override
            public boolean failed() {
                return hasFailed;
            }
        };
    }
}
