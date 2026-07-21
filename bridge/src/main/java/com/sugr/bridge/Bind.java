package com.sugr.bridge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be exposed to JS. The {@code processor} module generates
 * a dispatcher class (no reflection - a direct method call per binding) plus
 * a typed TS stub, so callers never write {@code Json.parse(...)} by hand.
 * See plan.md Giai đoạn 3, Milestone 3.1.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Bind {

    /** JS-visible method name; defaults to the Java method's name. */
    String value() default "";
}
