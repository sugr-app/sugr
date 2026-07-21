package app.__pkg__;

import com.sugr.bridge.Bind;

/** Add @Bind methods here - each one becomes a typed function in the frontend's generated stub. */
public final class Backend {

    @Bind
    public String hello(String name) {
        return "Hello, " + name + "! (from Java)";
    }
}
