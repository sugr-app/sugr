package com.sugr.bridge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on an events-declaring interface as a Java -&gt; JS event. The
 * {@code processor} module generates a "&lt;Interface&gt;Emitter" class (a real
 * {@code Window.emit} call per method, no hand-written event-name
 * strings) plus a typed TS stub with a matching {@code on<Name>()}/unsubscribe
 * pair, so callers never write {@code window.emit("some-event", ...)} or
 * {@code events.on('some-event', ...)} by hand. Mirrors {@link Bind}, but for
 * the opposite direction. Methods must return {@code void} and take zero or
 * one parameter (the event's payload) - see plan.md Giai đoạn 3, Milestone 3.1.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Emits {

    /** JS-visible event name; defaults to the Java method's name. */
    String value() default "";
}
