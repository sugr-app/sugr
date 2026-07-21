package com.sugr.bridge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks API that's less stable than sugr's already-pre-1.0 baseline - the
 * whole public surface can change before v1.0 (standard SemVer 0.x territory,
 * see CONTRIBUTING.md's "Versioning" section), but {@code @Experimental}
 * specifically flags something newer or riskier that's likely to be
 * redesigned in the very next minor version, not just "eventually before
 * v1.0". Remove the annotation once an API has shipped unchanged for a
 * minor version or two.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Experimental {
}
